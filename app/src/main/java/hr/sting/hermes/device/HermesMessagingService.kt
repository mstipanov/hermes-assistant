package hr.sting.hermes.device

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import hr.sting.hermes.R
import hr.sting.hermes.util.AppLog

/**
 * Firebase Cloud Messaging entry point: the push half of job notifications.
 *
 * The in-app poll ([hr.sting.hermes.HermesController]) only notices a parked job while the
 * process is alive. A job can park on a DECISION hours later with the app killed, so the server
 * pushes it here and Android starts this service on its own — the notification then appears
 * without the app ever having been opened.
 *
 * Payload is data-only (so it also arrives when the app is in the background):
 *
 *   type       "job_decision" | "job_done"
 *   session_id job session to open on tap
 *   title      job title (optional; the job's name)
 *   body       the question (optional; job_decision) or a short result (optional; job_done)
 */
class HermesMessagingService : FirebaseMessagingService() {
    /** A new token invalidates the one the relay knows; register it before it is ever needed. */
    override fun onNewToken(token: String) {
        AppLog.add("Push token refreshed")
        // Firebase calls this on its own worker thread, but uploading here would hold that
        // thread for the whole request, so the POST runs on a thread of its own.
        PushRegistration.uploadAsync(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        when (val type = data["type"].orEmpty()) {
            "job_decision" -> showDecision(data)
            "job_done" -> showDone(data)
            else -> {
                // No type: fall back to whatever text the message carries rather than dropping it.
                val text = message.notification
                if (text?.title.isNullOrBlank() && text?.body.isNullOrBlank()) {
                    AppLog.add("Push ignored: type '${type.ifBlank { "none" }}'")
                } else {
                    JobNotifier.notifyMessage(
                        this,
                        data["session_id"].orEmpty(),
                        text?.title.orEmpty(),
                        text?.body.orEmpty(),
                    )
                }
            }
        }
    }

    /** Same notification the app builds when it sees the job parked itself. */
    private fun showDecision(data: Map<String, String>) {
        val sessionId = data["session_id"].orEmpty().trim()
        if (sessionId.isEmpty()) {
            AppLog.add("Push ignored: job_decision without session_id")
            return
        }
        val jobTitle = data["title"].orEmpty().trim().ifBlank { getString(R.string.app_name) }
        val decision = data["body"].orEmpty().trim().ifBlank { data["decision"].orEmpty().trim() }
        JobNotifier.notify(this, sessionId, jobTitle, decision)
    }

    private fun showDone(data: Map<String, String>) {
        val sessionId = data["session_id"].orEmpty().trim()
        val detail = data["title"].orEmpty().trim().ifBlank { data["body"].orEmpty().trim() }
            .ifBlank { getString(R.string.job_finished_body) }
        JobNotifier.notifyMessage(
            this,
            sessionId,
            getString(R.string.job_finished_title),
            detail,
        )
    }
}
