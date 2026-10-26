package hr.sting.hermes.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Thin wrapper around Android's SpeechRecognizer — the same Google recognition
 * service Gboard's microphone uses. Google does the endpointing, so a command is
 * returned shortly after the user stops speaking; no WAV upload and no server
 * Whisper round trip.
 */
class AndroidSttRecognizer(private val context: Context) {
    interface Listener {
        fun onPartial(text: String)
        fun onEndOfSpeech()
        fun onFinal(text: String)
        fun onNoSpeech()
        fun onRecoverableError(message: String)
        fun onFatalError(message: String)
    }

    var listener: Listener? = null

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var active = false

    fun isAvailable(): Boolean = runCatching {
        SpeechRecognizer.isRecognitionAvailable(context)
    }.getOrDefault(false)

    fun start(language: String = "hr-HR"): Boolean {
        if (!isAvailable()) return false
        if (active) return true
        active = true
        main.post {
            if (!active) return@post
            val engine = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }
                .getOrElse {
                    active = false
                    listener?.onFatalError("SpeechRecognizer nije dostupan: ${it.message}")
                    return@post
                }
            recognizer = engine
            engine.setRecognitionListener(recognitionListener)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
            }
            runCatching { engine.startListening(intent) }
                .onFailure {
                    active = false
                    runCatching { engine.destroy() }
                    recognizer = null
                    listener?.onFatalError("Mikrofon za Google STT nije dostupan: ${it.message}")
                }
            Log.i(TAG, "Google STT listening ($language)")
        }
        return true
    }

    /** Stop the recognizer and discard the utterance (the Stop button). */
    fun cancel() {
        active = false
        val engine = recognizer
        recognizer = null
        main.post {
            runCatching { engine?.cancel() }
            runCatching { engine?.destroy() }
        }
    }

    fun destroy() = cancel()

    private fun finish() {
        active = false
        val engine = recognizer
        recognizer = null
        main.postDelayed({
            runCatching { engine?.destroy() }
        }, 150)
    }

    private fun textFrom(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            if (active) listener?.onEndOfSpeech()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!active) return
            val text = textFrom(partialResults)
            if (text.isNotBlank()) listener?.onPartial(text)
        }

        override fun onResults(results: Bundle?) {
            if (!active) return
            val text = textFrom(results)
            finish()
            Log.i(TAG, "Google STT final: ${text.length} chars")
            if (text.isBlank()) listener?.onNoSpeech() else listener?.onFinal(text)
        }

        override fun onError(error: Int) {
            if (!active) return
            finish()
            Log.w(TAG, "Google STT error $error")
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                -> listener?.onNoSpeech()

                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER,
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                -> listener?.onRecoverableError(errorMessage(error))

                else -> listener?.onFatalError(errorMessage(error))
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun errorMessage(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "Greška mikrofona (Google STT)."
        SpeechRecognizer.ERROR_CLIENT -> "Google STT klijent je odbio zahtjev."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Nema dozvole za mikrofon."
        SpeechRecognizer.ERROR_NETWORK -> "Google STT nema mrežu."
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Google STT mreža je istekla."
        SpeechRecognizer.ERROR_NO_MATCH -> "Nisam te čuo."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Google STT je zauzet."
        SpeechRecognizer.ERROR_SERVER -> "Google STT server nije dostupan."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Nisam čuo govor."
        else -> "Google STT greška ($code)."
    }

    private companion object {
        const val TAG = "HermesGoogleStt"
    }
}
