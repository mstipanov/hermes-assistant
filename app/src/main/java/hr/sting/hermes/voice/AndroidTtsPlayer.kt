package hr.sting.hermes.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import hr.sting.hermes.R
import hr.sting.hermes.data.TtsVoiceOption
import hr.sting.hermes.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Low-latency TTS through the Android speech engine (Samsung/Google). It speaks
 * sentence by sentence as the reply streams in, so the first words start
 * immediately and no server round trip is needed.
 */
class AndroidTtsPlayer(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private val buffer = StringBuilder()

    @Volatile private var ready = false
    @Volatile private var available = false
    @Volatile private var stopped = false
    @Volatile private var finished = false
    @Volatile private var reportedError = false
    @Volatile private var doneSent = false
    @Volatile private var speaking = false
    private val pendingUtterances = AtomicInteger(0)
    private val _voices = MutableStateFlow<List<TtsVoiceOption>>(emptyList())
    val voices: StateFlow<List<TtsVoiceOption>> = _voices.asStateFlow()
    @Volatile private var requestedVoice: String? = null

    private var onStart: (() -> Unit)? = null
    private var onDone: (() -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    val isReady: Boolean get() = ready

    /** True only when the engine is initialized and has a Croatian voice. */
    val isAvailable: Boolean get() = ready && available

    init {
        VoiceDiagnostics.update { it.copy(lastTts = "android: init") }
        engine = TextToSpeech(appContext) { status ->
            // The listener may arrive on a binder thread; the engine field is
            // assigned after the constructor, so hop to main before using it.
            mainHandler.post { handleInit(status) }
        }
    }

    /** Pick one of the phone's Croatian voices; null restores the engine default. */
    fun selectVoice(id: String?) {
        requestedVoice = id
        if (ready) applyVoice(id)
    }

    fun start(
        onStart: () -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit,
    ) {
        stop()
        stopped = false
        finished = false
        reportedError = false
        doneSent = false
        speaking = false
        pendingUtterances.set(0)
        buffer.clear()
        this.onStart = onStart
        this.onDone = onDone
        this.onError = onError
        if (ready && !available) {
            fail(appContext.getString(R.string.android_tts_no_croatian))
        }
    }

    fun feed(delta: String) {
        if (delta.isBlank() || stopped) return
        buffer.append(delta)
        drainSentences(flush = false)
    }

    fun finish() {
        if (stopped) return
        finished = true
        if (ready && available) {
            drainSentences(flush = true)
            if (pendingUtterances.get() == 0 && !reportedError) finishDone()
        }
        // If the engine is still initializing, handleInit() flushes the buffer.
    }

    fun stop() {
        stopped = true
        finished = false
        speaking = false
        pendingUtterances.set(0)
        buffer.clear()
        doneSent = false
        runCatching { engine?.stop() }
    }

    fun shutdown() {
        stop()
        runCatching { engine?.shutdown() }
        engine = null
    }

    private fun handleInit(status: Int) {
        if (engine == null) return
        ready = true
        val tts = engine ?: return
        if (status != TextToSpeech.SUCCESS) {
            available = false
            VoiceDiagnostics.update { it.copy(lastTts = "failed: android init $status") }
            fail("Android TTS init failed ($status)")
            return
        }
        var language = tts.setLanguage(Locale.forLanguageTag("hr-HR"))
        if (language < TextToSpeech.LANG_AVAILABLE) {
            language = tts.setLanguage(Locale.forLanguageTag("hr"))
        }
        available = language >= TextToSpeech.LANG_AVAILABLE
        val voices = runCatching { tts.voices }.getOrNull()
        val croatian = voices
            ?.filter { it.locale.language.equals("hr", ignoreCase = true) }
            ?.sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.name }))
            ?: emptyList()
        _voices.value = croatian.map { voice ->
            val shortName = when {
                voice.name == "hr-HR-language" ->
                    appContext.getString(R.string.tts_default_croatian_voice)

                voice.name.contains("x-hra", ignoreCase = true) ->
                    appContext.getString(R.string.tts_croatian_voice_a)

                voice.name.contains("x-hrb", ignoreCase = true) ->
                    appContext.getString(R.string.tts_croatian_voice_b)

                else -> voice.name
            }
            TtsVoiceOption(
                id = voice.name,
                label = shortName + if (voice.isNetworkConnectionRequired) {
                    appContext.getString(R.string.tts_network_suffix)
                } else {
                    appContext.getString(R.string.tts_local_suffix)
                },
                network = voice.isNetworkConnectionRequired,
            )
        }
        // A saved choice wins; otherwise prefer an on-device Croatian voice.
        // Google often offers only a *-server voice, which needs network.
        if (requestedVoice != null) {
            applyVoice(requestedVoice)
        } else {
            croatian.firstOrNull { !it.isNetworkConnectionRequired }?.let { local ->
                runCatching { tts.voice = local }
            }
        }
        AppLog.add(
            "android tts voices: " + croatian.joinToString {
                "${it.name}(net=${it.isNetworkConnectionRequired})"
            },
        )
        tts.setSpeechRate(1.0f)
        tts.setPitch(1.0f)
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    if (stopped || reportedError) return
                    if (!speaking) {
                        speaking = true
                        VoiceDiagnostics.update { it.copy(lastTts = "android speaking") }
                        onStart?.invoke()
                    }
                }

                override fun onDone(utteranceId: String?) {
                    if (stopped || reportedError) return
                    if (pendingUtterances.decrementAndGet() <= 0) {
                        pendingUtterances.set(0)
                        if (finished) finishDone()
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    fail("Android TTS error")
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    fail("Android TTS error $errorCode")
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    // stop() is handled by the controller; no callback here.
                }
            },
        )
        VoiceDiagnostics.update {
            it.copy(
                lastTts = if (available) {
                    "android ready (${tts.voice?.name ?: "hr-HR"})"
                } else {
                    "failed: " + appContext.getString(R.string.tts_no_croatian_voice)
                },
            )
        }
        if (!available) {
            fail(appContext.getString(R.string.android_tts_no_croatian))
            return
        }
        AppLog.add("android tts ready: ${tts.voice?.name ?: "default"}")
        drainSentences(flush = finished)
        if (finished && pendingUtterances.get() == 0 && !reportedError) finishDone()
    }

    private fun applyVoice(id: String?) {
        val tts = engine ?: return
        if (id == null) {
            tts.setLanguage(Locale.forLanguageTag("hr-HR"))
            VoiceDiagnostics.update {
                it.copy(lastTts = "android ready (${tts.voice?.name ?: "default"})")
            }
            return
        }
        val voice = runCatching { tts.voices }.getOrNull()?.firstOrNull { it.name == id } ?: return
        runCatching { tts.voice = voice }
        VoiceDiagnostics.update { it.copy(lastTts = "android ready (${voice.name})") }
    }

    private fun drainSentences(flush: Boolean) {
        if (!ready || !available || stopped) return
        while (true) {
            val text = buffer.toString()
            val boundary = boundaryIndex(text, flush)
            if (boundary <= 0) break
            val chunk = text.substring(0, boundary).trim()
            buffer.delete(0, boundary)
            if (chunk.isNotEmpty()) speakChunk(chunk)
        }
        if (flush && buffer.isNotBlank()) {
            val chunk = buffer.toString().trim()
            buffer.clear()
            if (chunk.isNotEmpty()) speakChunk(chunk)
        }
    }

    /**
     * Prefer a real sentence boundary. Before the sentence is complete, cut at
     * the last space after ~100 chars so long clauses start playing early.
     */
    private fun boundaryIndex(text: String, flush: Boolean): Int {
        for (index in text.indices) {
            val char = text[index]
            if (char == '\n') return index + 1
            if (char == '.' || char == '!' || char == '?') {
                val next = text.getOrNull(index + 1)
                if (next == null || next.isWhitespace()) return index + 1
            }
        }
        if (flush) return text.length
        if (text.length >= 160) {
            val cut = text.lastIndexOf(' ', 160)
            if (cut >= 100) return cut + 1
        }
        return -1
    }

    private fun speakChunk(chunk: String) {
        if (stopped) return
        val tts = engine ?: return
        val id = UUID.randomUUID().toString()
        pendingUtterances.incrementAndGet()
        val result = tts.speak(chunk, TextToSpeech.QUEUE_ADD, null, id)
        if (result == TextToSpeech.ERROR) {
            pendingUtterances.decrementAndGet()
            fail("Android TTS speak error")
        }
    }

    @Synchronized
    private fun finishDone() {
        if (doneSent || stopped) return
        doneSent = true
        speaking = false
        VoiceDiagnostics.update { it.copy(lastTts = "android done") }
        onDone?.invoke()
    }

    private fun fail(message: String) {
        if (reportedError || stopped) return
        reportedError = true
        VoiceDiagnostics.update { it.copy(lastTts = "failed: $message") }
        AppLog.add("android tts: $message")
        onError?.invoke(message)
    }
}
