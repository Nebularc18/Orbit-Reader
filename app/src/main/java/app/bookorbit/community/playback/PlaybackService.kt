package app.bookorbit.community.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.*
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.*
import app.bookorbit.community.MainActivity
import app.bookorbit.community.OrbitApplication
import app.bookorbit.community.data.*
import app.bookorbit.community.download.enqueueDownload
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock

const val PAUSE_CONNECTION = "orbit.pause-connection"
const val OPEN_BOOK = "orbit.open"
const val SLEEP_TIMER = "orbit.sleep"
const val RELOAD_LISTENING = "orbit.listening"

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private val app
        get() = application as OrbitApplication

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: ExoPlayer
    private lateinit var listeningPlayer: ListeningPlayer
    private lateinit var session: MediaLibrarySession
    private var listeningJob: Job? = null
    private var manifest: AudioManifest? = null
    private var accountScope: String? = null
    private var saveJob: Job? = null
    private var timer: Job? = null
    private var chapterTimer: PlayerMessage? = null
    private var chapterTarget: AudioSeek? = null
    private var openJob: Job? = null
    private var completionJob: Job? = null

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
                    chapterTarget?.let {
                        if (it.reachedBy(newPosition.mediaItemIndex, newPosition.positionMs))
                            finishSleepTimer()
                    }
                    capture()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        val sleeping = chapterTarget != null || timer?.isActive == true
                        if (chapterTarget != null) finishSleepTimer()
                        val book = manifest
                        val owner = accountScope
                        if (
                            !sleeping &&
                                book != null &&
                                owner != null &&
                                completionJob?.isActive != true
                        )
                            completionJob = scope.launch { completeBook(book, owner) }
                    }
                }

                override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                    val bookId = manifest?.book?.id ?: return
                    val owner = accountScope ?: return
                    if (app.sessions.read()?.scope != owner) return
                    val previous = listeningJob
                    listeningJob =
                        scope.launch(Dispatchers.IO) {
                            previous?.join()
                            try {
                                ListeningStorage(this@PlaybackService, owner)
                                    .saveSpeed(bookId, playbackParameters.speed)
                            } catch (_: Exception) {
                                app.playbackMessage.value = "Could not save playback speed."
                            }
                        }
                }

                override fun onPlayerError(error: PlaybackException) {
                    app.playbackMessage.value =
                        "Playback stopped. Check your connection or download this audiobook."
                }
            }
        )
        listeningPlayer = ListeningPlayer(player)
        session =
            MediaLibrarySession.Builder(
                    this,
                    listeningPlayer,
                    object : MediaLibrarySession.Callback {
                        override fun onGetLibraryRoot(
                            session: MediaLibrarySession,
                            browser: MediaSession.ControllerInfo,
                            params: LibraryParams?,
                        ): ListenableFuture<LibraryResult<MediaItem>> =
                            Futures.immediateFuture(
                                LibraryResult.ofItem(CarLibrary(app).root(), params)
                            )

                        override fun onGetChildren(
                            session: MediaLibrarySession,
                            browser: MediaSession.ControllerInfo,
                            parentId: String,
                            page: Int,
                            pageSize: Int,
                            params: LibraryParams?,
                        ): ListenableFuture<
                            LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>
                        > = carChildren(parentId, page, pageSize, "", params)

                        override fun onGetSearchResult(
                            session: MediaLibrarySession,
                            browser: MediaSession.ControllerInfo,
                            query: String,
                            page: Int,
                            pageSize: Int,
                            params: LibraryParams?,
                        ): ListenableFuture<
                            LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>
                        > = carChildren("orbit-search", page, pageSize, query.take(500), params)

                        override fun onSearch(
                            session: MediaLibrarySession,
                            browser: MediaSession.ControllerInfo,
                            query: String,
                            params: LibraryParams?,
                        ): ListenableFuture<LibraryResult<Void>> {
                            val future =
                                com.google.common.util.concurrent.SettableFuture.create<
                                    LibraryResult<Void>
                                >()
                            scope.launch {
                                try {
                                    val account = app.sessions.read() ?: throw ApiFailure(401)
                                    val count =
                                        withContext(Dispatchers.IO) {
                                            app.api
                                                .books(1, query.take(500), owner = account.scope)
                                                .total
                                        }
                                    if (app.sessions.read()?.scope == account.scope)
                                        session.notifySearchResultChanged(
                                            browser,
                                            query,
                                            count,
                                            params,
                                        )
                                    future.set(LibraryResult.ofVoid(params))
                                } catch (_: Exception) {
                                    future.set(LibraryResult.ofError(SessionError.ERROR_IO))
                                }
                            }
                            return future
                        }

                        override fun onSetMediaItems(
                            session: MediaSession,
                            controller: MediaSession.ControllerInfo,
                            mediaItems: MutableList<MediaItem>,
                            startIndex: Int,
                            startPositionMs: Long,
                        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                            val future =
                                com.google.common.util.concurrent.SettableFuture.create<
                                    MediaSession.MediaItemsWithStartPosition
                                >()
                            scope.launch {
                                try {
                                    val account = app.sessions.read() ?: throw ApiFailure(401)
                                    val requested =
                                        mediaItems.singleOrNull()
                                            ?: throw IllegalArgumentException(
                                                "Choose one audiobook"
                                            )
                                    val query =
                                        requested.requestMetadata.searchQuery?.take(500)?.trim()
                                    val id =
                                        if (!query.isNullOrBlank()) {
                                            withContext(Dispatchers.IO) {
                                                app.api
                                                    .books(1, query, owner = account.scope)
                                                    .items
                                                    .firstOrNull()
                                                    ?.id
                                            }
                                                ?: throw IllegalArgumentException(
                                                    "No matching audiobook was found"
                                                )
                                        } else
                                            parseCarBookId(requested.mediaId, account.scope)
                                                ?: throw IllegalArgumentException(
                                                    "Choose a book from this server"
                                                )
                                    check(app.sessions.read()?.scope == account.scope)
                                    completionJob?.cancelAndJoin()
                                    openJob?.cancelAndJoin()
                                    check(open(id, null, startPlayback = false)) {
                                        "Open Orbit Reader on your phone to review playback."
                                    }
                                    future.set(
                                        MediaSession.MediaItemsWithStartPosition(
                                            (0 until player.mediaItemCount).map(
                                                player::getMediaItemAt
                                            ),
                                            player.currentMediaItemIndex,
                                            player.currentPosition,
                                        )
                                    )
                                } catch (e: Exception) {
                                    future.setException(e)
                                }
                            }
                            return future
                        }

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
                                    MediaSession.ConnectionResult
                                        .DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                                        .buildUpon()
                                        .add(SessionCommand(PAUSE_CONNECTION, Bundle.EMPTY))
                                        .add(SessionCommand(OPEN_BOOK, Bundle.EMPTY))
                                        .add(SessionCommand(SLEEP_TIMER, Bundle.EMPTY))
                                        .add(SessionCommand(RELOAD_LISTENING, Bundle.EMPTY))
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
                                PAUSE_CONNECTION -> {
                                    val result =
                                        com.google.common.util.concurrent.SettableFuture.create<
                                            SessionResult
                                        >()
                                    scope.launch {
                                        try {
                                            completionJob?.cancelAndJoin()
                                            openJob?.cancelAndJoin()
                                            cancelSleepTimer()
                                            player.pause()
                                            capture()
                                            saveJob?.join()
                                            listeningJob?.join()
                                            player.stop()
                                            player.clearMediaItems()
                                            manifest = null
                                            accountScope = null
                                            app.currentManifest.value = null
                                            app.progressConflict.value = null
                                            app.playbackMessage.value = null
                                            result.set(SessionResult(SessionResult.RESULT_SUCCESS))
                                        } catch (error: Exception) {
                                            result.setException(error)
                                        }
                                    }
                                    return result
                                }

                                OPEN_BOOK -> {
                                    completionJob?.cancel()
                                    openJob?.cancel()
                                    openJob =
                                        scope.launch {
                                            open(
                                                args.getInt("bookId"),
                                                args.getString("resolution"),
                                            )
                                        }
                                }
                                RELOAD_LISTENING -> {
                                    val owner = accountScope
                                    scope.launch {
                                        if (owner != null && app.sessions.read()?.scope == owner) {
                                            try {
                                                val intervals =
                                                    withContext(Dispatchers.IO) {
                                                        ListeningStorage(
                                                                this@PlaybackService,
                                                                owner,
                                                            )
                                                            .intervals()
                                                    }
                                                if (
                                                    accountScope == owner &&
                                                        app.sessions.read()?.scope == owner
                                                ) {
                                                    listeningPlayer.setIntervals(intervals)
                                                    val options =
                                                        withContext(Dispatchers.IO) {
                                                            PlaybackToolsStorage(
                                                                    this@PlaybackService,
                                                                    owner,
                                                                )
                                                                .options()
                                                        }
                                                    if (
                                                        accountScope == owner &&
                                                            app.sessions.read()?.scope == owner
                                                    )
                                                        listeningPlayer.smartResume(
                                                            options.smartResume,
                                                            options.rewindSeconds,
                                                        )
                                                }
                                            } catch (error: CancellationException) {
                                                throw error
                                            } catch (_: Exception) {
                                                app.playbackMessage.value =
                                                    "Could not apply skip intervals. Reopen this book to try again."
                                            }
                                        }
                                    }
                                }
                                SLEEP_TIMER -> {
                                    val endOfChapter = args.getBoolean("endOfChapter")
                                    val minutes = args.getInt("minutes")
                                    val target =
                                        if (endOfChapter)
                                            manifest?.let {
                                                AudioTimeline(it)
                                                    .chapterEnd(
                                                        player.currentMediaItemIndex,
                                                        player.currentPosition,
                                                    )
                                            }
                                        else null
                                    if (
                                        (endOfChapter && target == null) ||
                                            (!endOfChapter && minutes !in listOf(0, 15, 30, 45, 60))
                                    ) {
                                        app.playbackMessage.value =
                                            "A chapter boundary is not available at this position."
                                        return Futures.immediateFuture(
                                            SessionResult(SessionError.ERROR_BAD_VALUE)
                                        )
                                    }
                                    cancelSleepTimer()
                                    if (target != null) {
                                        chapterTarget = target
                                        app.sleepAtChapterEnd.value = true
                                        chapterTimer =
                                            player
                                                .createMessage { _, _ ->
                                                    if (chapterTarget === target) finishSleepTimer()
                                                }
                                                .setLooper(mainLooper)
                                                .setPosition(target.assetIndex, target.positionMs)
                                                .send()
                                    } else if (minutes > 0) {
                                        app.sleepMinutes.value = minutes
                                        timer =
                                            scope.launch {
                                                delay(minutes * 60_000L)
                                                finishSleepTimer()
                                            }
                                    }
                                }
                            }
                            return Futures.immediateFuture(
                                SessionResult(SessionResult.RESULT_SUCCESS)
                            )
                        }
                    },
                )
                .setSessionActivity(
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                )
                .build()
        scope.launch {
            while (isActive) {
                delay(if (networkAvailable(this@PlaybackService)) 5_000 else 10_000)
                if (player.isPlaying) capture()
            }
        }
    }

    private fun carChildren(
        parent: String,
        page: Int,
        size: Int,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>> {
        val future =
            com.google.common.util.concurrent.SettableFuture.create<
                LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>
            >()
        scope.launch {
            try {
                val items =
                    withContext(Dispatchers.IO) {
                        CarLibrary(app).children(parent, page, size, query)
                    }
                future.set(LibraryResult.ofItemList(items, params))
            } catch (e: Exception) {
                future.set(
                    LibraryResult.ofError(
                        if (e is ApiFailure && e.status == 401)
                            SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED
                        else SessionError.ERROR_IO
                    )
                )
            }
        }
        return future
    }

    private suspend fun open(
        bookId: Int,
        resolution: String?,
        startPlayback: Boolean = true,
    ): Boolean {
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
                            if (cached != null && !networkAvailable(this@PlaybackService)) null
                            else app.api.progress(bookId)
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
            listeningJob?.join()
            val settings =
                withContext(Dispatchers.IO) {
                    val listening = ListeningStorage(this@PlaybackService, account.scope)
                    listening.speed(bookId) to listening.intervals()
                }
            val isDownloaded = withContext(Dispatchers.IO) { storage.downloaded(result.first) }
            if (app.sessions.read()?.scope != account.scope) return false
            capture()
            saveJob?.join()
            if (app.sessions.read()?.scope != account.scope) return false
            if (chapterTarget != null) cancelSleepTimer()
            manifest = result.first
            accountScope = account.scope
            listeningPlayer.setManifest(result.first)
            listeningPlayer.setIntervals(settings.second)
            player.setPlaybackSpeed(settings.first)
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
            val options =
                withContext(Dispatchers.IO) {
                    PlaybackToolsStorage(this@PlaybackService, account.scope).options()
                }
            listeningPlayer.smartResume(options.smartResume, options.rewindSeconds)
            var start = AudioSeek(startIndex, result.second?.positionMs ?: 0L)
            if (resolution == null && result.second != null) {
                val age =
                    runCatching {
                            java.time.Duration.between(
                                    Instant.parse(result.second!!.capturedAt),
                                    Instant.now(),
                                )
                                .toMillis()
                        }
                        .getOrDefault(0)
                val rewind = smartRewindMillis(options.smartResume, age, options.rewindSeconds)
                if (rewind > 0)
                    start =
                        AudioTimeline(result.first)
                            .skip(
                                start.assetIndex,
                                start.positionMs,
                                -rewind,
                                result.first.assets[start.assetIndex].durationMs ?: 0,
                            )
            }
            player.setMediaItems(items, start.assetIndex, start.positionMs)
            player.prepare()
            if (startPlayback) player.play() else player.pause()
            app.currentManifest.value = result.first
            scope.launch { prefetchNext(result.first.book.id, account.scope) }
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (error: ApiFailure) {
            if (error.status == 409) app.progressConflict.value = bookId
            app.playbackMessage.value = error.message
        } catch (_: Exception) {
            app.playbackMessage.value =
                "Could not open this audiobook. Check your server connection."
        }
        return false
    }

    private suspend fun nextBook(
        bookId: Int,
        owner: String,
        options: PlaybackOptions,
        prefetch: Boolean = false,
    ): Int? {
        val tools = PlaybackToolsStorage(this, owner)
        if (if (prefetch) options.prefetchNext else options.autoplayQueue)
            tools
                .queue()
                .firstOrNull { it.id != bookId }
                ?.let {
                    return it.id
                }
        if (!options.continueSeries) return null
        val detail = app.api.ebookDetail(bookId, owner)
        return detail.seriesId?.let { app.api.nextSeriesAudio(it, bookId, owner) }
    }

    private suspend fun prefetchNext(bookId: Int, owner: String) {
        try {
            withContext(Dispatchers.IO) {
                val options = PlaybackToolsStorage(this@PlaybackService, owner).options()
                val account =
                    app.sessions.read()?.takeIf { it.scope == owner && it.canDownload }
                        ?: return@withContext
                if (options.prefetchNext)
                    nextBook(bookId, owner, options, prefetch = true)?.let {
                        enqueueDownload(this@PlaybackService, account, it, true)
                    }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {}
    }

    private suspend fun completeBook(book: AudioManifest, owner: String) {
        capture()
        saveJob?.join()
        try {
            val options =
                withContext(Dispatchers.IO) {
                    val tools = PlaybackToolsStorage(this@PlaybackService, owner)
                    tools.remove(book.book.id)
                    tools.options()
                }
            if (app.sessions.read()?.scope != owner) return
            val next =
                withContext(Dispatchers.IO) {
                    if (options.autoplayQueue || options.continueSeries)
                        nextBook(book.book.id, owner, options)
                    else null
                }
            if (app.sessions.read()?.scope != owner) return
            if (options.removeFinishedAudio)
                withContext(Dispatchers.IO) {
                    app.downloadLock(owner, book.book.id).withLock {
                        AudioStorage(this@PlaybackService, owner).removeAudioFiles(book.book.id)
                    }
                }
            if (options.removeFinishedAudio) player.clearMediaItems()
            if (next != null && next != book.book.id && open(next, null))
                withContext(Dispatchers.IO) {
                    PlaybackToolsStorage(this@PlaybackService, owner).remove(next)
                }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            app.playbackMessage.value =
                "Could not continue to the next book. Open your queue to try again."
        }
    }

    private fun cancelSleepTimer() {
        timer?.cancel()
        timer = null
        chapterTimer?.cancel()
        chapterTimer = null
        chapterTarget = null
        app.sleepMinutes.value = 0
        app.sleepAtChapterEnd.value = false
    }

    private fun finishSleepTimer() {
        cancelSleepTimer()
        player.pause()
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession =
        session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        cancelSleepTimer()
        capture()
        // Persist the final position before the service's coroutine scope disappears.
        runBlocking {
            saveJob?.join()
            listeningJob?.join()
        }
        scope.cancel()
        listeningPlayer.release()
        session.release()
        app.currentManifest.value = null
        super.onDestroy()
    }
}
