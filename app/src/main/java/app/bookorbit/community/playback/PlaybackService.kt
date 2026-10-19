package app.bookorbit.community.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.*
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.*
import app.bookorbit.community.MainActivity
import app.bookorbit.community.OrbitApplication
import app.bookorbit.community.data.*
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.*

const val OPEN_BOOK = "orbit.open"
const val SLEEP_TIMER = "orbit.sleep"

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private val app
        get() = application as OrbitApplication

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private var manifest: AudioManifest? = null
    private var accountScope: String? = null
    private var saveJob: Job? = null
    private var timer: Job? = null
    private var openJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val factory =
            DefaultDataSource.Factory(this, OkHttpDataSource.Factory(app.api.authenticatedClient))
        player =
            ExoPlayer.Builder(this)
                .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                        .setUsage(C.USAGE_MEDIA)
                        .build(),
                    true,
                )
                .setHandleAudioBecomingNoisy(true)
                .setSeekBackIncrementMs(15_000)
                .setSeekForwardIncrementMs(30_000)
                .build()
        player.setWakeMode(C.WAKE_MODE_LOCAL)
        player.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (!isPlaying) capture()
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    capture()
                }

                override fun onPlayerError(error: PlaybackException) {
                    app.playbackMessage.value =
                        "Playback stopped. Check your connection or download this audiobook."
                }
            }
        )
        session =
            MediaSession.Builder(this, player)
                .setSessionActivity(
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                )
                .setCallback(
                    object : MediaSession.Callback {
                        override fun onConnect(
                            session: MediaSession,
                            controller: MediaSession.ControllerInfo,
                        ): MediaSession.ConnectionResult {
                            if (controller.uid != applicationInfo.uid) {
                                return if (
                                    controller.isTrusted ||
                                        session.isMediaNotificationController(controller)
                                )
                                    MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                                        .build()
                                else MediaSession.ConnectionResult.reject()
                            }
                            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                                .setAvailableSessionCommands(
                                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                                        .buildUpon()
                                        .add(SessionCommand(OPEN_BOOK, Bundle.EMPTY))
                                        .add(SessionCommand(SLEEP_TIMER, Bundle.EMPTY))
                                        .build()
                                )
                                .build()
                        }

                        override fun onCustomCommand(
                            session: MediaSession,
                            controller: MediaSession.ControllerInfo,
                            customCommand: SessionCommand,
                            args: Bundle,
                        ): ListenableFuture<SessionResult> {
                            when (customCommand.customAction) {
                                OPEN_BOOK -> {
                                    openJob?.cancel()
                                    openJob =
                                        scope.launch {
                                            open(
                                                args.getInt("bookId"),
                                                args.getString("resolution"),
                                                args.getBoolean("offline"),
                                            )
                                        }
                                }
                                SLEEP_TIMER -> {
                                    timer?.cancel()
                                    val minutes = args.getInt("minutes")
                                    app.sleepMinutes.value = minutes
                                    if (minutes > 0)
                                        timer =
                                            scope.launch {
                                                delay(minutes * 60_000L)
                                                player.pause()
                                                app.sleepMinutes.value = 0
                                            }
                                }
                            }
                            return Futures.immediateFuture(
                                SessionResult(SessionResult.RESULT_SUCCESS)
                            )
                        }
                    }
                )
                .build()
        scope.launch {
            while (isActive) {
                delay(10_000)
                if (player.isPlaying) capture()
            }
        }
    }

    private suspend fun open(bookId: Int, resolution: String?, offline: Boolean) {
        app.playbackMessage.value = null
        app.progressConflict.value = null
        try {
            val account = app.sessions.read() ?: throw ApiFailure(401)
            val storage = AudioStorage(this, account.scope)
            val result =
                withContext(Dispatchers.IO) {
                    val cached = storage.manifest(bookId)?.takeIf(storage::downloaded)
                    val loaded = cached ?: app.api.manifest(bookId)
                    val remote =
                        try {
                            if (offline && cached != null) null else app.api.progress(bookId)
                        } catch (error: ApiFailure) {
                            throw error
                        } catch (error: IOException) {
                            if (cached == null) throw error else null
                        }
                    val local =
                        storage.position(bookId)?.takeIf { it.manifestRevision == loaded.revision }
                    val conflict =
                        local?.dirty == true && remote != null && remote.revision != local.revision
                    if (conflict && resolution == null) throw ApiFailure(409)
                    val position =
                        when {
                            resolution == "server" && remote != null ->
                                LocalPosition(
                                    remote.assetId,
                                    remote.positionMs,
                                    remote.capturedAt,
                                    remote.revision,
                                    remote.manifestRevision,
                                    false,
                                )
                            local?.dirty == true ->
                                local.copy(revision = remote?.revision ?: local.revision)
                            remote != null ->
                                LocalPosition(
                                    remote.assetId,
                                    remote.positionMs,
                                    remote.capturedAt,
                                    remote.revision,
                                    remote.manifestRevision,
                                    false,
                                )
                            else -> local
                        }
                    storage.saveManifest(loaded)
                    if (position != null) storage.savePosition(bookId, position)
                    loaded to position
                }
            if (app.sessions.read()?.scope != account.scope) return
            capture()
            saveJob?.join()
            manifest = result.first
            accountScope = account.scope
            val isDownloaded = withContext(Dispatchers.IO) { storage.downloaded(result.first) }
            val items =
                result.first.assets.map { asset ->
                    val file = storage.asset(bookId, asset)
                    val uri =
                        if (isDownloaded) android.net.Uri.fromFile(file).toString()
                        else app.api.url("audiobooks/$bookId/assets/${asset.assetId}/content")
                    MediaItem.Builder()
                        .setMediaId(asset.assetId)
                        .setUri(uri)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(result.first.book.title)
                                .setArtist(result.first.book.authors.joinToString(", "))
                                .setAlbumTitle("Part ${asset.sequence + 1}")
                                .build()
                        )
                        .build()
                }
            val startIndex =
                result.first.assets
                    .indexOfFirst { it.assetId == result.second?.assetId }
                    .coerceAtLeast(0)
            player.setMediaItems(items, startIndex, result.second?.positionMs ?: 0L)
            player.prepare()
            player.play()
            app.currentManifest.value = result.first
        } catch (error: CancellationException) {
            throw error
        } catch (error: ApiFailure) {
            if (error.status == 409) app.progressConflict.value = bookId
            app.playbackMessage.value = error.message
        } catch (_: Exception) {
            app.playbackMessage.value =
                "Could not open this audiobook. Check your server connection."
        }
    }

    private fun capture() {
        val book = manifest ?: return
        val owner = accountScope ?: return
        val assetId = player.currentMediaItem?.mediaId ?: return
        if (app.sessions.read()?.scope != owner) return
        val milliseconds = player.currentPosition.coerceAtLeast(0)
        val capturedAt = Instant.now().toString()
        val previous = saveJob
        saveJob =
            scope.launch(Dispatchers.IO) {
                previous?.join()
                withContext(Dispatchers.IO) {
                    val storage = AudioStorage(this@PlaybackService, owner)
                    storage.capturePosition(
                        book.book.id,
                        assetId,
                        milliseconds,
                        capturedAt,
                        book.revision,
                    )
                    enqueueProgress(this@PlaybackService, owner, book.book.id)
                }
            }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        capture()
        // Persist the final position before the service's coroutine scope disappears.
        runBlocking { saveJob?.join() }
        scope.cancel()
        player.release()
        session.release()
        app.currentManifest.value = null
        super.onDestroy()
    }
}
