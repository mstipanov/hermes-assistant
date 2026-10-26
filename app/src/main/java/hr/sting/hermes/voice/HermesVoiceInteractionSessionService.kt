package hr.sting.hermes.voice

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * Bound by the system when Hermes holds the default-assistant role. The
 * session immediately opens the app in voice mode; the system gesture
 * (long-press side/home) is the entry point, the microphone work stays in the
 * app's existing foreground voice service.
 */
class HermesVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = HermesVoiceSession(this)
}

private class HermesVoiceSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        hr.sting.hermes.util.TrustedLaunch.expectListen()
        val intent = android.content.Intent(context, hr.sting.hermes.MainActivity::class.java).apply {
            addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP,
            )
        }
        context.startActivity(intent)
        finish()
    }
}
