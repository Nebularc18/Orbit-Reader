package app.bookorbit.community.reader

import android.content.Context
import android.media.*
import android.os.*
import android.speech.tts.*
import java.io.File
import java.net.URI
import java.text.BreakIterator
import java.util.Locale
import java.util.zip.ZipFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.jsoup.Jsoup

fun speechSentences(text: String, locale: Locale = Locale.getDefault()): List<String> {
    val clean = text.replace(Regex("\\s+"), " ").trim().take(300_000)
    if (clean.isBlank()) return emptyList()
    val iterator = BreakIterator.getSentenceInstance(locale).apply { setText(clean) }
    val result = mutableListOf<String>()
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE && result.size < 5000) {
        clean
            .substring(start, end)
            .trim()
            .chunked(3000)
            .filter { it.isNotBlank() }
            .forEach { if (result.size < 5000) result.add(it) }
        start = end
        end = iterator.next()
    }
    return result
}

fun epubSpeechText(file: File, href: String): String =
    ZipFile(file).use { zip ->
        val uri = URI(href)
        require(uri.scheme == null && !href.startsWith("//"))
        val path = uri.path.removePrefix("/")
        require(!path.split('/').contains(".."))
        val entry = zip.getEntry(path) ?: error("This chapter is not available for read-aloud.")
        val bytes =
            zip.getInputStream(entry).use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(32768)
                while (output.size() <= 2 * 1024 * 1024) {
                    val count =
                        input.read(
                            buffer,
                            0,
                            minOf(buffer.size, 2 * 1024 * 1024 + 1 - output.size()),
                        )
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        require(bytes.size <= 2 * 1024 * 1024) { "This chapter is too large for read-aloud." }
        Jsoup.parse(bytes.inputStream(), null, "")
            .apply { select("script,style,svg,math,nav").remove() }
            .body()
            .text()
    }

data class SpeechVoice(val name: String, val label: String)

data class ReadAloudState(
    val ready: Boolean = false,
    val playing: Boolean = false,
    val loading: Boolean = false,
    val sentence: String = "",
    val index: Int = 0,
    val count: Int = 0,
    val speed: Float = 1f,
    val voice: String = "",
    val voices: List<SpeechVoice> = emptyList(),
    val message: String? = null,
)

class ReadAloudController(
    private val context: Context,
    private val owner: String,
    private val highlight: (String, String, String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = context.getSharedPreferences("read-aloud", 0)
    private val mutable =
        MutableStateFlow(
            ReadAloudState(
                speed = prefs.getFloat("$owner/speed", 1f),
                voice = prefs.getString("$owner/voice", "").orEmpty(),
            )
        )
    val state = mutable.asStateFlow()
    private var tts: TextToSpeech? = null
    private var sentences: List<String> = emptyList()
    private var generation = 0
    private var loadJob: Job? = null
    private val audio = context.getSystemService(AudioManager::class.java)
    private val focus =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { if (it < 0) pause() }
            .build()

    init {
        tts =
            TextToSpeech(context.applicationContext) { result ->
                handler.post {
                    val engine = tts ?: return@post
                    if (result != TextToSpeech.SUCCESS) {
                        mutable.update {
                            it.copy(
                                message =
                                    "No speech engine is available. Install a text-to-speech engine in Android settings."
                            )
                        }
                        return@post
                    }
                    val voices =
                        engine.voices
                            .orEmpty()
                            .filter {
                                !it.isNetworkConnectionRequired &&
                                    "notInstalled" !in it.features.orEmpty()
                            }
                            .sortedWith(
                                compareBy<Voice> { it.locale.displayLanguage }.thenBy { it.name }
                            )
                            .take(200)
                    val selected =
                        voices.firstOrNull { it.name == mutable.value.voice }
                            ?: voices.firstOrNull {
                                it.locale.language == Locale.getDefault().language
                            }
                            ?: voices.firstOrNull()
                    if (selected != null) engine.voice = selected
                    engine.setSpeechRate(mutable.value.speed)
                    engine.setOnUtteranceProgressListener(
                        object : UtteranceProgressListener() {
                            override fun onStart(id: String?) {}

                            override fun onDone(id: String?) {
                                handler.post {
                                    if (
                                        id == "$generation:${mutable.value.index}" &&
                                            mutable.value.playing
                                    ) {
                                        val next = mutable.value.index + 1
                                        if (next < sentences.size) {
                                            mutable.update { it.copy(index = next) }
                                            speak()
                                        } else {
                                            pause()
                                            mutable.update {
                                                it.copy(
                                                    message =
                                                        "End of this chapter. Open the next chapter to continue."
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            @Deprecated("Platform callback")
                            override fun onError(id: String?) {
                                handler.post {
                                    if (id != "$generation:${mutable.value.index}") return@post
                                    pause()
                                    mutable.update {
                                        it.copy(
                                            message =
                                                "This voice could not read the text. Choose another installed voice."
                                        )
                                    }
                                }
                            }
                        }
                    )
                    mutable.update {
                        it.copy(
                            ready = selected != null,
                            voices =
                                voices.map { v ->
                                    SpeechVoice(v.name, "${v.locale.displayName} · ${v.name}")
                                },
                            voice = selected?.name.orEmpty(),
                            message =
                                if (selected == null)
                                    "No installed offline voice was found. Add a voice in Android speech settings."
                                else null,
                        )
                    }
                }
            }
    }

    fun load(file: File, href: String, progression: Double, excerpt: String?) {
        pause()
        sentences = emptyList()
        mutable.update {
            it.copy(loading = true, message = null, sentence = "", index = 0, count = 0)
        }
        loadJob?.cancel()
        loadJob =
            scope.launch {
                try {
                    sentences =
                        withContext(Dispatchers.IO) { speechSentences(epubSpeechText(file, href)) }
                    val match =
                        excerpt
                            ?.trim()
                            ?.takeIf { it.length > 8 }
                            ?.let { quote ->
                                sentences.indexOfFirst {
                                    it.contains(
                                        speechSentences(quote).firstOrNull()?.take(100)
                                            ?: quote.take(100),
                                        ignoreCase = true,
                                    )
                                }
                            } ?: -1
                    val index =
                        if (match >= 0) match
                        else
                            (progression.coerceIn(0.0, 1.0) * sentences.size)
                                .toInt()
                                .coerceIn(0, (sentences.size - 1).coerceAtLeast(0))
                    mutable.update {
                        it.copy(
                            loading = false,
                            index = index,
                            count = sentences.size,
                            sentence = sentences.getOrNull(index).orEmpty(),
                            message =
                                if (sentences.isEmpty())
                                    "No readable text was found in this chapter."
                                else null,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mutable.update {
                        it.copy(
                            loading = false,
                            message = e.message ?: "Could not read this chapter.",
                        )
                    }
                }
            }
    }

    fun play() {
        if (!mutable.value.ready || sentences.isEmpty() || mutable.value.loading) return
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            mutable.update {
                it.copy(message = "Audio is busy. Try again after the other audio stops.")
            }
            return
        }
        mutable.update { it.copy(playing = true, message = null) }
        speak()
    }

    private fun speak() {
        val index = mutable.value.index
        val text = sentences.getOrNull(index) ?: return
        mutable.update { it.copy(sentence = text) }
        highlight(
            text,
            sentences.getOrNull(index - 1).orEmpty().takeLast(100),
            sentences.getOrNull(index + 1).orEmpty().take(100),
        )
        if (
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "$generation:$index") ==
                TextToSpeech.ERROR
        ) {
            pause()
            mutable.update { it.copy(message = "Could not start speech with this voice.") }
        }
    }

    fun pause() {
        generation++
        tts?.stop()
        mutable.update { it.copy(playing = false) }
        audio.abandonAudioFocusRequest(focus)
    }

    fun move(delta: Int) {
        val playing = mutable.value.playing
        pause()
        mutable.update {
            it.copy(index = (it.index + delta).coerceIn(0, (sentences.size - 1).coerceAtLeast(0)))
        }
        if (playing) play()
        else mutable.update { it.copy(sentence = sentences.getOrNull(it.index).orEmpty()) }
    }

    fun speed(value: Float) {
        require(value in .5f..2f)
        val playing = mutable.value.playing
        pause()
        tts?.setSpeechRate(value)
        prefs.edit().putFloat("$owner/speed", value).apply()
        mutable.update { it.copy(speed = value) }
        if (playing) play()
    }

    fun voice(value: String) {
        val voice =
            tts?.voices?.firstOrNull { it.name == value && !it.isNetworkConnectionRequired }
                ?: return
        val playing = mutable.value.playing
        pause()
        tts?.voice = voice
        prefs.edit().putString("$owner/voice", value).apply()
        mutable.update { it.copy(voice = value) }
        if (playing) play()
    }

    fun close() {
        pause()
        tts?.shutdown()
        tts = null
        scope.cancel()
    }
}
