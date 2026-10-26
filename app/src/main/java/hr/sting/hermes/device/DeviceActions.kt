package hr.sting.hermes.device

import android.Manifest
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import hr.sting.hermes.R
import hr.sting.hermes.data.bool
import hr.sting.hermes.data.int
import hr.sting.hermes.data.jsonObject
import hr.sting.hermes.data.str
import hr.sting.hermes.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder

/**
 * Android-side executor for the Hermes `android_device` tool. Every action is a
 * normal public intent: no root, no accessibility hacks, no silent purchases.
 * Sensitive actions (calls/SMS) open the system dialer/composer unless the user
 * explicitly granted CALL_PHONE and asked for a direct call.
 */
object DeviceActions {
    suspend fun execute(context: Context, params: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val action = params.str("action").lowercase()
        AppLog.add("device.action $action")
        try {
            when (action) {
                "launch_app" -> launchApp(context, params)
                "list_apps" -> listApps(context, params)
                "play_music" -> playMusic(context, params)
                "media_control" -> mediaControl(context, params)
                "call_contact", "dial_number" -> call(context, params)
                "open_url" -> openUrl(context, params)
                "set_alarm" -> setAlarm(context, params)
                "send_sms" -> sendSms(context, params)
                else -> errorResult(context.getString(R.string.unknown_action, action))
            }
        } catch (error: Exception) {
            AppLog.add("device.action $action failed: ${error.message}")
            errorResult(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun launchApp(context: Context, params: JsonObject): JsonObject {
        val query = (params.str("app_name").ifBlank { params.str("query") }).trim()
        if (query.isBlank()) return errorResult(context.getString(R.string.missing_app_name))
        val apps = installedApps(context)
        val normalized = normalize(query)
        val exact = apps.firstOrNull {
            normalize(it.label) == normalized || it.packageName.equals(query, ignoreCase = true)
        }
        val partial = apps.filter {
            normalize(it.label).contains(normalized) || it.packageName.contains(normalized)
        }
        val target = exact ?: partial.singleOrNull()
        if (target == null) {
            if (partial.size > 1) {
                return jsonObject(
                    "ok" to false,
                    "error" to context.getString(R.string.multiple_apps),
                    "candidates" to JsonArray(partial.take(8).map { JsonPrimitive(it.label) }),
                )
            }
            return jsonObject(
                "ok" to false,
                "error" to context.getString(R.string.app_not_found, query),
                "candidates" to JsonArray(apps.take(12).map { JsonPrimitive(it.label) }),
            )
        }
        val intent = context.packageManager.getLaunchIntentForPackage(target.packageName)
            ?: return errorResult(context.getString(R.string.app_no_launcher, target.label))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return jsonObject(
            "ok" to true,
            "action" to "launch_app",
            "app" to target.label,
            "package" to target.packageName,
        )
    }

    private fun listApps(context: Context, params: JsonObject): JsonObject {
        val query = params.str("query").trim()
        val apps = installedApps(context)
        val filtered = if (query.isBlank()) apps else apps.filter {
            normalize(it.label).contains(normalize(query))
        }
        return jsonObject(
            "ok" to true,
            "action" to "list_apps",
            "apps" to JsonArray(filtered.take(40).map {
                buildJsonObject {
                    put("label", it.label)
                    put("package", it.packageName)
                }
            }),
        )
    }

    private fun playMusic(context: Context, params: JsonObject): JsonObject {
        val query = params.str("query").trim().ifBlank { params.str("song").trim() }
        if (query.isBlank()) return errorResult(context.getString(R.string.missing_song))
        val encoded = URLEncoder.encode(query, "UTF-8")

        val playIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(SearchManager.QUERY, query)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (playIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(playIntent)
            return jsonObject(
                "ok" to true,
                "action" to "play_music",
                "query" to query,
                "mode" to "media_search",
                "message" to context.getString(R.string.music_requested, query),
            )
        }

        val spotify = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("spotify:search:$encoded"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (spotify.resolveActivity(context.packageManager) != null) {
            context.startActivity(spotify)
            return jsonObject(
                "ok" to true,
                "action" to "play_music",
                "query" to query,
                "mode" to "spotify_search",
                "message" to context.getString(R.string.spotify_search, query),
            )
        }

        val youtube = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://music.youtube.com/search?q=$encoded"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(youtube)
        return jsonObject(
            "ok" to true,
            "action" to "play_music",
            "query" to query,
            "mode" to "youtube_music_search",
            "message" to context.getString(R.string.youtube_search, query),
        )
    }

    private fun mediaControl(context: Context, params: JsonObject): JsonObject {
        val command = params.str("command").ifBlank { "play_pause" }.lowercase()
        val keyCode = when (command) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "play_pause", "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> return errorResult(context.getString(R.string.unknown_media_command, command))
        }
        val audio = context.getSystemService(AudioManager::class.java)
            ?: return errorResult(context.getString(R.string.audio_manager_unavailable))
        return try {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            jsonObject("ok" to true, "action" to "media_control", "command" to command)
        } catch (error: Exception) {
            errorResult(
                context.getString(
                    R.string.media_control_not_allowed,
                    error.message ?: error.javaClass.simpleName,
                ),
            )
        }
    }

    private fun call(context: Context, params: JsonObject): JsonObject {
        var number = params.str("number").trim()
        var contactName = params.str("contact_name").trim()
        val direct = params.bool("direct")
        if (number.isBlank()) {
            if (contactName.isBlank()) {
                return errorResult(context.getString(R.string.missing_contact_or_number))
            }
            if (!hasPermission(context, Manifest.permission.READ_CONTACTS)) {
                return needPermission(
                    context,
                    "READ_CONTACTS",
                    context.getString(R.string.contacts_permission_needed),
                )
            }
            val matches = findContacts(context, contactName)
            if (matches.isEmpty()) {
                return errorResult(context.getString(R.string.contact_not_found, contactName))
            }
            if (matches.size > 1) {
                return buildJsonObject {
                    put("ok", false)
                    put("error", context.getString(R.string.multiple_contacts))
                    put("candidates", buildJsonArray {
                        matches.take(8).forEach { match ->
                            add(buildJsonObject {
                                put("name", match.first)
                                put("number", match.second)
                            })
                        }
                    })
                }
            }
            contactName = matches.first().first
            number = matches.first().second
        }
        val canCall = direct && hasPermission(context, Manifest.permission.CALL_PHONE)
        val intent = Intent(
            if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL,
            Uri.parse("tel:${Uri.encode(number)}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return jsonObject(
            "ok" to true,
            "action" to "call",
            "contact" to contactName,
            "number" to number,
            "mode" to if (canCall) "call" else "dial",
            "message" to if (canCall) {
                context.getString(R.string.calling, contactName)
            } else {
                context.getString(R.string.dialer_with_number, contactName)
            },
        )
    }

    private fun findContacts(context: Context, query: String): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$query%"),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext() && results.size < 20) {
                val name = cursor.getString(nameIndex) ?: continue
                val number = cursor.getString(numberIndex) ?: continue
                if (results.none { it.first == name && it.second == number }) {
                    results += name to number
                }
            }
        }
        return results
    }

    private fun openUrl(context: Context, params: JsonObject): JsonObject {
        val raw = params.str("url").trim()
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
            return errorResult(context.getString(R.string.only_http_urls))
        }
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(raw)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return jsonObject("ok" to true, "action" to "open_url", "url" to raw)
    }

    private fun setAlarm(context: Context, params: JsonObject): JsonObject {
        val raw = params.str("time").trim()
        val parts = raw.split(":")
        if (parts.size < 2) return errorResult(context.getString(R.string.time_format_hhmm))
        val hour = parts[0].toIntOrNull() ?: return errorResult(context.getString(R.string.invalid_hour))
        val minute = parts[1].toIntOrNull()
            ?: return errorResult(context.getString(R.string.invalid_minute))
        if (hour !in 0..23 || minute !in 0..59) {
            return errorResult(context.getString(R.string.invalid_time))
        }
        val label = params.str("label").ifBlank { context.getString(R.string.default_alarm_label) }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        val formatted = "%02d:%02d".format(hour, minute)
        return jsonObject(
            "ok" to true,
            "action" to "set_alarm",
            "time" to formatted,
            "label" to label,
            "message" to context.getString(R.string.alarm_set, formatted),
        )
    }

    private fun sendSms(context: Context, params: JsonObject): JsonObject {
        var number = params.str("number").trim()
        val name = params.str("contact_name").trim()
        val body = params.str("message").trim()
        if (number.isBlank() && name.isNotBlank()) {
            if (!hasPermission(context, Manifest.permission.READ_CONTACTS)) {
                return needPermission(
                    context,
                    "READ_CONTACTS",
                    context.getString(R.string.contacts_permission_needed_short),
                )
            }
            val matches = findContacts(context, name)
            if (matches.isEmpty()) {
                return errorResult(context.getString(R.string.contact_not_found, name))
            }
            if (matches.size > 1) {
                return jsonObject(
                    "ok" to false,
                    "error" to context.getString(R.string.multiple_contacts),
                    "candidates" to JsonArray(matches.take(8).map { JsonPrimitive(it.first) }),
                )
            }
            number = matches.first().second
        }
        if (number.isBlank()) {
            return errorResult(context.getString(R.string.missing_contact_or_number))
        }
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}"))
            .putExtra("sms_body", body)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return jsonObject(
            "ok" to true,
            "action" to "send_sms",
            "number" to number,
            "message" to context.getString(R.string.sms_opened),
        )
    }

    private fun installedApps(context: Context): List<AppInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val resolved = context.packageManager.queryIntentActivities(intent, 0)
        return resolved
            .map {
                AppInfo(
                    label = it.loadLabel(context.packageManager).toString(),
                    packageName = it.activityInfo.packageName,
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    private fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun needPermission(
        context: Context,
        permission: String,
        message: String,
    ): JsonObject = jsonObject(
        "ok" to false,
        "error" to message,
        "need_permission" to permission,
        "hint" to context.getString(R.string.permission_hint),
    )

    private fun errorResult(message: String): JsonObject = jsonObject(
        "ok" to false,
        "error" to message,
    )

    private fun normalize(value: String): String = value
        .lowercase()
        .replace("č", "c").replace("ć", "c").replace("ž", "z")
        .replace("š", "s").replace("đ", "d")
        .trim()

    private data class AppInfo(val label: String, val packageName: String)
}
