package hr.sting.hermes.util

import android.content.Context
import android.content.SharedPreferences
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

    // ── Session opens from a notification ─────────────────────────────────
    //
    // Job session ids this app itself announced in a notification (JobNotifier). Opening a
    // conversation is not privileged, but an external app must not be able to steer the chat
    // into any session it likes, so MainActivity honours a "hermes_session_id" extra only for
    // an id listed here. Kept in private preferences rather than in memory: a notification
    // outlives the process, so tapping it after the app was killed must still open its job.

    private const val PREFS = "trusted-launch"
    private const val KEY_SESSIONS = "job-sessions"
    private const val MAX_SESSIONS = 20
    private const val SEPARATOR = "\n"

    fun expectSession(context: Context, sessionId: String) {
        if (sessionId.isBlank()) return
        val store = prefs(context)
        val ids = announced(store).filterNot { it == sessionId } + sessionId
        store.edit()
            .putString(KEY_SESSIONS, ids.takeLast(MAX_SESSIONS).joinToString(SEPARATOR))
            .apply()
    }

    fun isAnnouncedSession(context: Context, sessionId: String): Boolean =
        sessionId.isNotBlank() && announced(prefs(context)).contains(sessionId)

    private fun announced(store: SharedPreferences): List<String> =
        store.getString(KEY_SESSIONS, "").orEmpty().split(SEPARATOR).filter { it.isNotBlank() }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
