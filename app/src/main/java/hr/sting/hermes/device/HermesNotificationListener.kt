package hr.sting.hermes.device

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Read-only notification mirror for the `read_notifications` device action.
 * Android requires the user to enable it manually (Settings → Notifications →
 * Device & app notifications); until then the action returns a clear hint.
 */
class HermesNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        // Our own file/voice notifications are noise in an assistant context.
        if (sbn.packageName == packageName) return
        val notification = sbn.notification ?: return
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        record(
            Entry(
                key = sbn.key,
                packageName = sbn.packageName,
                app = appLabel(sbn.packageName),
                title = title,
                text = text,
                postedAt = sbn.postTime,
                ongoing = notification.flags.and(Notification.FLAG_ONGOING_EVENT) != 0,
            ),
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val key = sbn?.key ?: return
        synchronized(entries) { entries.removeAll { it.key == key } }
    }

    private fun appLabel(packageName: String): String = runCatching {
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)

    private data class Entry(
        val key: String,
        val packageName: String,
        val app: String,
        val title: String,
        val text: String,
        val postedAt: Long,
        val ongoing: Boolean,
    )

    companion object {
        private val entries = ArrayDeque<Entry>()
        private const val MAX_ENTRIES = 40

        private fun record(entry: Entry) {
            synchronized(entries) {
                entries.removeAll { it.key == entry.key }
                entries.addFirst(entry)
                while (entries.size > MAX_ENTRIES) entries.removeLast()
            }
        }

        fun isEnabled(context: Context): Boolean = runCatching {
            NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)
        }.getOrDefault(false)

        fun recent(context: Context, limit: Int, includeOngoing: Boolean): JsonArray {
            val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            val snapshot = synchronized(entries) {
                entries.filter { it.postedAt >= cutoff && (includeOngoing || !it.ongoing) }
                    .take(limit)
            }
            return buildJsonArray {
                snapshot.forEach { entry ->
                    add(
                        buildJsonObject {
                            put("app", entry.app)
                            put("package", entry.packageName)
                            put("title", entry.title)
                            put("text", entry.text)
                            put("age_min", ((System.currentTimeMillis() - entry.postedAt) / 60_000))
                            put("ongoing", entry.ongoing)
                        },
                    )
                }
            }
        }

        fun clear() {
            synchronized(entries) { entries.clear() }
        }
    }
}
