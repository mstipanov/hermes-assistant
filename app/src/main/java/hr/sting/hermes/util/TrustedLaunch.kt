package hr.sting.hermes.util

import android.os.SystemClock

/**
 * One-shot, in-process gate for "open the app and start listening".
 *
 * MainActivity has to be exported (launcher) and external apps can send it any
 * intent they like. The microphone must not open because of a crafted intent,
 * so only components that run inside this process (Quick Settings tile, voice
 * interaction service, shortcuts handled in-app) may raise this flag right
 * before starting the activity.
 */
object TrustedLaunch {
    private const val MAX_AGE_MS = 10_000L

    @Volatile private var requestedAt = 0L

    fun expectListen() {
        requestedAt = SystemClock.elapsedRealtime()
    }

    fun consumeListen(): Boolean {
        val at = requestedAt
        requestedAt = 0L
        return at != 0L && SystemClock.elapsedRealtime() - at <= MAX_AGE_MS
    }
}
