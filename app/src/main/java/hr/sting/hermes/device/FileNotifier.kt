package hr.sting.hermes.device

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import hr.sting.hermes.MainActivity
import hr.sting.hermes.R

/** "Datoteka je stigla" notification so a file is noticed even off-screen. */
object FileNotifier {
    private const val CHANNEL_ID = "hermes-files"

    fun notify(context: Context, file: SavedFile) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.files_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(context.getString(R.string.file_received_title))
            .setContentText(file.name)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${file.name}\n${file.location}"))
            .setContentIntent(contentIntent(context, file))
            .setAutoCancel(true)
        runCatching {
            NotificationManagerCompat.from(context).notify(file.name.hashCode(), builder.build())
        }
    }

    private fun contentIntent(context: Context, file: SavedFile): PendingIntent {
        val open = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(file.uri, mimeOf(context, file.uri))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val intent = if (open.resolveActivity(context.packageManager) != null) {
            open
        } else {
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return PendingIntent.getActivity(
            context,
            file.name.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun mimeOf(context: Context, uri: Uri): String =
        runCatching { context.contentResolver.getType(uri) }.getOrNull()
            ?: "application/octet-stream"
}
