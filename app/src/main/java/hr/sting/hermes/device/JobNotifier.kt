package hr.sting.hermes.device

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import hr.sting.hermes.MainActivity
import hr.sting.hermes.R
import hr.sting.hermes.util.TrustedLaunch

/**
 * "A job needs your decision" / "job finished" notifications.
 *
 * A job runs headless in its own session, so nothing on the phone sees the turn park: without
 * this the user only finds the question by opening the jobs list. Tapping the notification
 * opens that job's session, where the question can be answered in the chat screen.
 *
 * Two callers feed it: the session poll in [hr.sting.hermes.HermesController] and an FCM push
 * ([HermesMessagingService]) that arrives when the app is not running at all. The notification
 * itself is built in one place so both look and behave the same.
 */
object JobNotifier {
    private const val CHANNEL_ID = "jobs"

    /** Notification/pending-intent id for a message that has no session to open. */
    private const val UNSCOPED_ID = 0x6a6f62

    /** Extra MainActivity reads to open the job; also the key [TrustedLaunch] vouches for. */
    const val EXTRA_SESSION_ID = "hermes_session_id"

    /** The parked-job notification: the job title plus the question it is blocked on. */
    fun notify(context: Context, sessionId: String, jobTitle: String, decision: String) {
        if (sessionId.isBlank()) return
        val body = if (decision.isBlank()) {
            jobTitle
        } else {
            context.getString(R.string.job_decision_body, jobTitle, decision)
        }
        notifyMessage(context, sessionId, context.getString(R.string.job_decision_title), body)
    }

    /**
     * Any job message, tapped through to [sessionId]'s chat (a blank id just opens the app).
     *
     * Push payloads carry their own title and body, so they cannot go through [notify]'s fixed
     * "needs a decision" wording; everything else — channel, icon, permission gate, tap target —
     * stays identical.
     */
    fun notifyMessage(context: Context, sessionId: String, title: String, body: String) {
        if (title.isBlank() && body.isBlank()) return
        // Notifications off (permission revoked, or the app muted) is not an error: the jobs
        // list still shows the question, so stay silent instead of failing loudly.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.jobs_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentIntent(context, sessionId))
            .setAutoCancel(true)
        if (sessionId.isNotBlank()) TrustedLaunch.expectSession(context, sessionId)
        runCatching {
            NotificationManagerCompat.from(context).notify(idFor(sessionId), builder.build())
        }
    }

    private fun contentIntent(context: Context, sessionId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            // Land in the running app (onNewIntent) instead of stacking a second activity on
            // top, so the job opens in the chat screen the user already has.
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            if (sessionId.isNotBlank()) putExtra(EXTRA_SESSION_ID, sessionId)
        }
        return PendingIntent.getActivity(
            context,
            idFor(sessionId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun idFor(sessionId: String): Int =
        if (sessionId.isBlank()) UNSCOPED_ID else sessionId.hashCode()
}
