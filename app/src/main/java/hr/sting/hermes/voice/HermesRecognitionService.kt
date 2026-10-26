package hr.sting.hermes.voice

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * The platform's voice-interaction metadata requires a RecognitionService.
 * Hermes does its own microphone capture (AudioRecord -> Hermes STT), so this
 * implementation only answers "not handled" and is never used for audio.
 */
class HermesRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        notifyUnsupported(listener)
    }

    override fun onCancel(listener: Callback) {
        notifyUnsupported(listener)
    }

    override fun onStopListening(listener: Callback) {
        notifyUnsupported(listener)
    }

    private fun notifyUnsupported(listener: Callback) {
        runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }
            .onFailure { Log.d("HermesRecognition", "error callback failed", it) }
    }
}
