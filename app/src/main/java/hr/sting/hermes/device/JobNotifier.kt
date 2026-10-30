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
 * "A job needs your decision" notification.
 *
 * A job runs headless in its own session, so nothing on the phone sees the turn park: without
 * this the user only finds the question by opening the jobs list. Tapping the notification
 * opens that job's session, where the question can be answered in the chat screen.
 */
object JobNotifier {
    private const val CHANNEL_ID = "jobs"

    /** Extra MainActivity reads to open the job; also the key [TrustedLaunch] vouches for. */
    const val EXTRA_SESSION_ID = "hermes_session_id"

    fun notify(context: Context, sessionId: String, jobTitle: String, decision: String) {
        if (sessionId.isBlank()) return
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
        val body = if (decision.isBlank()) {
            jobTitle
        } else {
            context.getString(R.string.job_decision_body, jobTitle, decision)
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(context.getString(R.string.job_decision_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentIntent(context, sessionId))
            .setAutoCancel(true)
        TrustedLaunch.expectSession(context, sessionId)
        runCatching {
            NotificationManagerCompat.from(context).notify(sessionId.hashCode(), builder.build())
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
            putExtra(EXTRA_SESSION_ID, sessionId)
        }
        return PendingIntent.getActivity(
            context,
            sessionId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
