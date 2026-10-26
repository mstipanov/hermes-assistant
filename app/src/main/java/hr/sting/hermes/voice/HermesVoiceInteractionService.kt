package hr.sting.hermes.voice

import android.service.voice.VoiceInteractionService

/**
 * Declares Hermes as a candidate digital assistant. Android binds this service
 * when the user selects Hermes under Settings -> Default apps -> Digital
 * assistant app, then the session service opens the app in listening mode.
 */
class HermesVoiceInteractionService : VoiceInteractionService()
