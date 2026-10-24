package app.bookorbit.community.reader

import android.content.Context
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import app.bookorbit.community.OrbitApplication
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ReadAlongState(
    val loading: Boolean = false,
    val ready: Boolean = false,
    val playing: Boolean = false,
    val index: Int = 0,
    val count: Int = 0,
    val speed: Float = 1f,
    val quote: String = "",
    val error: String? = null,
)

class ReadAlongController(
    context: Context,
    private val owner: String,
    private val fileId: Int,
    private val onClip: (OverlayClip) -> Unit,
) {
    private val app = context.applicationContext as OrbitApplication
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(ReadAlongState())
    val state = mutable.asStateFlow()
    private val prefs = context.getSharedPreferences("read-along-$owner", Context.MODE_PRIVATE)
    private val cache = File(context.cacheDir, "read-along-$owner-$fileId").apply { mkdirs() }
    private var job: Job? = null
    private var clips = emptyList<OverlayClip>()
    private var revision = ""
    private var chapter = ""
    private var active = false
    private val player =
        ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true,
            )
            setHandleAudioBecomingNoisy(true)
            addListener(
                object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        mutable.update { it.copy(playing = isPlaying) }
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        if (active) changed(currentMediaItemIndex)
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        mutable.update {
                            it.copy(
                                playing = false,
                                error =
                                    "Could not play the embedded narration. Check its audio format.",
                            )
                        }
                    }
                }
            )
        }

    fun load(book: SavedEbook, href: String) {
        if (chapter == href && revision == book.file.revision && mutable.value.ready) return
        job?.cancel()
        pause()
        active = false
        clips = emptyList()
        player.clearMediaItems()
        player.setPlaybackSpeed(1f)
        chapter = href
        revision = book.file.revision
        mutable.value = ReadAlongState(loading = true)
        job =
            scope.launch {
                try {
                    val prepared =
                        withContext(Dispatchers.IO) {
                            check(app.sessions.read()?.scope == owner)
                            val file = EbookStorage(app, owner).content(book.file)
                            val clips = mediaOverlays(file, href)
                            require(clips.isNotEmpty()) {
                                "This chapter has no supported EPUB narration timings."
                            }
                            cache.deleteRecursively()
                            cache.mkdirs()
                            val sources = mutableMapOf<String, File>()
                            var total = 0L
                            ZipFile(file).use { zip ->
                                clips
                                    .map { it.audioPath }
                                    .distinct()
                                    .forEach { path ->
                                        ensureActive()
                                        val entry = zip.getEntry(path) ?: error("Missing audio")
                                        require(entry.size in 1..(32 * 1024 * 1024))
                                        total += entry.size
                                        require(total <= 128L * 1024 * 1024)
                                        val name =
                                            MessageDigest.getInstance("SHA-256")
                                                .digest(path.toByteArray())
                                                .joinToString("") { "%02x".format(it) }
                                        val target =
                                            File(cache, "$name.${path.substringAfterLast('.')}")
                                        zip.getInputStream(entry).use { input ->
                                            target.outputStream().use { output ->
                                                val buffer = ByteArray(65536)
                                                var size = 0L
                                                while (true) {
                                                    ensureActive()
                                                    val n = input.read(buffer)
                                                    if (n < 0) break
                                                    size += n
                                                    require(size <= entry.size)
                                                    output.write(buffer, 0, n)
                                                }
                                                require(size == entry.size)
                                            }
                                        }
                                        sources[path] = target
                                    }
                            }
                            clips to
                                clips.map { clip ->
                                    MediaItem.Builder()
                                        .setUri(
                                            requireNotNull(sources[clip.audioPath])
                                                .toURI()
                                                .toString()
                                        )
                                        .setClippingConfiguration(
                                            MediaItem.ClippingConfiguration.Builder()
                                                .setStartPositionMs(clip.startMs)
                                                .setEndPositionMs(clip.endMs)
                                                .build()
                                        )
                                        .build()
                                }
                        }
                    check(app.sessions.read()?.scope == owner)
                    clips = prepared.first
                    val index =
                        if (
                            prefs.getString("$fileId-revision", null) == revision &&
                                prefs.getString("$fileId-chapter", null) == chapter
                        )
                            prefs.getInt("$fileId-index", 0).coerceIn(0, clips.lastIndex)
                        else 0
                    player.setMediaItems(prepared.second, index, 0)
                    player.prepare()
                    mutable.value =
                        ReadAlongState(
                            ready = true,
                            index = index,
                            count = clips.size,
                            quote = clips[index].quote,
                        )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    mutable.value =
                        ReadAlongState(
                            error =
                                "This chapter has no supported narration, or its audio exceeds the offline limits."
                        )
                }
            }
    }

    private fun changed(index: Int) {
        val clip = clips.getOrNull(index) ?: return
        mutable.update { it.copy(index = index, quote = clip.quote) }
        prefs
            .edit()
            .putString("$fileId-revision", revision)
            .putString("$fileId-chapter", chapter)
            .putInt("$fileId-index", index)
            .apply()
        onClip(clip)
    }

    fun play() {
        if (!mutable.value.ready || app.sessions.read()?.scope != owner) return
        active = true
        if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition(0)
        changed(player.currentMediaItemIndex)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }

    fun location(href: String) {
        if (chapter.isNotEmpty() && chapter != href) {
            pause()
            active = false
        }
    }

    fun pause() {
        player.pause()
    }

    fun move(delta: Int) {
        val index =
            (player.currentMediaItemIndex + delta).coerceIn(0, (clips.size - 1).coerceAtLeast(0))
        if (clips.isNotEmpty()) {
            active = true
            player.seekToDefaultPosition(index)
            changed(index)
        }
    }

    fun speed(value: Float) {
        require(value.isFinite() && value in .5f..3f)
        player.setPlaybackSpeed(value)
        mutable.update { it.copy(speed = value) }
    }

    fun close() {
        job?.cancel()
        scope.cancel()
        player.release()
        cache.deleteRecursively()
    }
}
