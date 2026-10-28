package hr.sting.hermes.device

import android.Manifest
import android.app.DownloadManager
import android.app.SearchManager
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.util.Base64
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import hr.sting.hermes.MainActivity
import hr.sting.hermes.R
import hr.sting.hermes.data.bool
import hr.sting.hermes.data.int
import hr.sting.hermes.data.jsonObject
import hr.sting.hermes.data.long
import hr.sting.hermes.data.str
import hr.sting.hermes.util.AppLog
import hr.sting.hermes.util.TrustedLaunch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/**
 * Android-side executor for the Hermes `android_device` tool. Every action is a
 * normal public API call: no root, no accessibility hacks, no silent purchases.
 * The user gates each action in Settings → Action permissions with
 * Allow / Confirm / Deny; this object only executes what the controller allowed.
 */
object DeviceActions {
    /** Set by the controller so `speak` reuses the app's own TTS engine. */
    var speaker: ((String) -> Unit)? = null

    @Volatile private var torchOn = false

    suspend fun execute(context: Context, params: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val raw = params.str("action").lowercase()
        val action = DeviceActionCatalog.canonical(raw)
        AppLog.add("device.action $action")
        try {
            when (action) {
                "open_app" -> launchApp(context, params)
                "list_apps" -> listApps(context, params)
                "play_music" -> playMusic(context, params)
                "media_control" -> mediaControl(context, params)
                "place_call", "dial_number" -> call(context, params)
                "open_url" -> openUrl(context, params)
                "set_alarm" -> setAlarm(context, params)
                "set_timer" -> setTimer(context, params)
                "show_alarms" -> showAlarms(context)
                "navigate" -> mapIntent(context, params, navigate = true)
                "show_on_map" -> mapIntent(context, params, navigate = false)
                "web_search" -> webSearch(context, params)
                "share_text" -> shareText(context, params)
                "voice_command" -> voiceCommand(context)
                "vibrate" -> vibrate(context, params)
                "flashlight" -> flashlight(context, params)
                "speak" -> speak(params)
                "device_status" -> deviceStatus(context)
                "set_volume" -> setVolume(context, params)
                "add_calendar_event" -> addCalendarEvent(context, params)
                "read_calendar" -> readCalendar(context, params)
                "save_file" -> saveFile(context, params)
                "open_file" -> openFile(context, params)
                "pick_file" -> pickFile(context)
                "close_app" -> closeApp(context)
                "send_sms" -> sendSms(context, params)
                "read_sms" -> readSms(context, params)
                "lookup_contact" -> lookupContact(context, params)
                "location" -> location(context)
                "do_not_disturb" -> doNotDisturb(context, params)
                "toggle_ringer" -> toggleRinger(context, params)
                "brightness" -> setBrightness(context, params)
                "read_notifications" -> readNotifications(context, params)
                "read_clipboard" -> readClipboard(context)
                "write_clipboard" -> writeClipboard(context, params)
                "screen_capture" -> screenCapture(context)
                else -> errorResult(context.getString(R.string.unknown_action, raw))
            }
        } catch (error: Exception) {
            AppLog.add("device.action $action failed: ${error.message}")
            errorResult(error.message ?: error.javaClass.simpleName)
        }
    }

    // ── Apps and media ────────────────────────────────────────────────────

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
            "action" to "open_app",
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
            "rewind" -> KeyEvent.KEYCODE_MEDIA_REWIND
            "fast_forward", "forward" -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
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

    private fun setVolume(context: Context, params: JsonObject): JsonObject {
        val audio = context.getSystemService(AudioManager::class.java)
            ?: return errorResult(context.getString(R.string.audio_manager_unavailable))
        val stream = when (params.str("stream").lowercase()) {
            "ring", "ringtone" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification", "notifications" -> AudioManager.STREAM_NOTIFICATION
            "voice", "call" -> AudioManager.STREAM_VOICE_CALL
            else -> AudioManager.STREAM_MUSIC
        }
        val max = audio.getStreamMaxVolume(stream)
        val percent = when {
            params.int("percent", -1) >= 0 -> params.int("percent")
            params.int("level", -1) >= 0 -> params.int("level")
            params.str("value").isNotBlank() -> params.str("value").replace("%", "").trim().toIntOrNull() ?: -1
            params.str("direction").isNotBlank() -> {
                val up = params.str("direction").lowercase() in setOf("up", "raise", "increase")
                val current = audio.getStreamVolume(stream)
                val next = (if (up) current + 1 else current - 1).coerceIn(0, max)
                audio.setStreamVolume(stream, next, 0)
                return jsonObject(
                    "ok" to true,
                    "action" to "set_volume",
                    "percent" to next * 100 / max.coerceAtLeast(1),
                    "stream" to params.str("stream").ifBlank { "media" },
                )
            }
            else -> -1
        }
        if (percent !in 0..100) return errorResult(context.getString(R.string.invalid_volume))
        val index = (percent * max / 100f).toInt().coerceIn(0, max)
        audio.setStreamVolume(stream, index, 0)
        return jsonObject(
            "ok" to true,
            "action" to "set_volume",
            "percent" to percent,
            "stream" to params.str("stream").ifBlank { "media" },
        )
    }

    // ── Timers, alarms, maps, searches ────────────────────────────────────

    private fun setTimer(context: Context, params: JsonObject): JsonObject {
        val seconds = params.int("seconds", 0).takeIf { it > 0 }
            ?: parseDurationSeconds(
                params.str("duration").ifBlank { params.str("length") }.ifBlank { params.str("time") },
            )
            ?: return errorResult(context.getString(R.string.invalid_duration))
        val label = params.str("label").ifBlank { context.getString(R.string.default_timer_label) }
        context.startActivity(
            Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return jsonObject(
            "ok" to true,
            "action" to "set_timer",
            "seconds" to seconds,
            "label" to label,
            "message" to context.getString(R.string.timer_set, formatDuration(seconds)),
        )
    }

    private fun showAlarms(context: Context): JsonObject {
        context.startActivity(
            Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return jsonObject("ok" to true, "action" to "show_alarms")
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

    private fun mapIntent(context: Context, params: JsonObject, navigate: Boolean): JsonObject {
        val target = listOf("destination", "address", "query", "location")
            .firstNotNullOfOrNull { key -> params.str(key).trim().takeIf { it.isNotBlank() } }
            ?: return errorResult(context.getString(R.string.missing_destination))
        val encoded = Uri.encode(target)
        val uri = if (navigate) "google.navigation:q=$encoded" else "geo:0,0?q=$encoded"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (navigate) {
            intent.setPackage("com.google.android.apps.maps")
        }
        if (intent.resolveActivity(context.packageManager) == null) {
            val fallback = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("geo:0,0?q=$encoded"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(fallback)
        } else {
            context.startActivity(intent)
        }
        return jsonObject(
            "ok" to true,
            "action" to if (navigate) "navigate" else "show_on_map",
            "destination" to target,
            "mode" to if (navigate) "navigation" else "map",
        )
    }

    private fun webSearch(context: Context, params: JsonObject): JsonObject {
        val query = params.str("query").trim().ifBlank { params.str("text").trim() }
        if (query.isBlank()) return errorResult(context.getString(R.string.missing_query))
        val intent = Intent(Intent.ACTION_WEB_SEARCH)
            .putExtra(SearchManager.QUERY, query)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            return jsonObject("ok" to true, "action" to "web_search", "query" to query)
        }
        val encoded = URLEncoder.encode(query, "UTF-8")
        context.startActivity(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/search?q=$encoded"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return jsonObject("ok" to true, "action" to "web_search", "query" to query)
    }

    private fun shareText(context: Context, params: JsonObject): JsonObject {
        val text = params.str("text").trim().ifBlank { params.str("message").trim() }
        if (text.isBlank()) return errorResult(context.getString(R.string.missing_text))
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            params.str("subject").takeIf { it.isNotBlank() }?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }
        context.startActivity(
            Intent.createChooser(send, context.getString(R.string.share_text_title))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return jsonObject("ok" to true, "action" to "share_text")
    }

    private fun voiceCommand(context: Context): JsonObject {
        TrustedLaunch.expectListen()
        context.startActivity(
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
            },
        )
        return jsonObject("ok" to true, "action" to "voice_command")
    }

    // ── Haptics, torch, speech, status ────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun vibrate(context: Context, params: JsonObject): JsonObject {
        if (!hasPermission(context, Manifest.permission.VIBRATE)) {
            return needPermission(
                context,
                "VIBRATE",
                context.getString(R.string.vibrate_permission_needed),
            )
        }
        val vibrator = context.getSystemService(Vibrator::class.java)
            ?: return errorResult(context.getString(R.string.vibrator_unavailable))
        val durationMs = params.int("duration_ms", params.int("duration", 400))
            .coerceIn(50, 10_000)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(durationMs.toLong(), VibrationEffect.DEFAULT_AMPLITUDE),
            )
        } else {
            vibrator.vibrate(durationMs.toLong())
        }
        return jsonObject("ok" to true, "action" to "vibrate", "duration_ms" to durationMs)
    }

    private fun flashlight(context: Context, params: JsonObject): JsonObject {
        val manager = context.getSystemService(CameraManager::class.java)
            ?: return errorResult(context.getString(R.string.flashlight_unavailable))
        val command = params.str("state").ifBlank { params.str("command") }.ifBlank { "toggle" }
            .lowercase()
        val target = when (command) {
            "on", "true", "enable" -> true
            "off", "false", "disable" -> false
            "toggle" -> !torchOn
            else -> return errorResult(context.getString(R.string.unknown_flashlight_command, command))
        }
        val cameraId = runCatching {
            manager.cameraIdList.firstOrNull { id ->
                val characteristics = manager.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull() ?: return errorResult(context.getString(R.string.flashlight_unavailable))
        manager.setTorchMode(cameraId, target)
        torchOn = target
        return jsonObject("ok" to true, "action" to "flashlight", "state" to if (target) "on" else "off")
    }

    private fun speak(params: JsonObject): JsonObject {
        val text = params.str("text").trim().ifBlank { params.str("message").trim() }
        if (text.isBlank()) return errorResult("text is required")
        val speak = speaker ?: return errorResult("Text-to-speech is not ready")
        speak(text)
        return jsonObject("ok" to true, "action" to "speak")
    }

    @Suppress("DEPRECATION")
    private fun deviceStatus(context: Context): JsonObject {
        val battery = context.getSystemService(BatteryManager::class.java)
        val level = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = battery?.isCharging ?: false
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
        val networkType = when {
            network == null -> "none"
            network.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            network.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            network.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            network.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
            else -> "other"
        }
        val audio = context.getSystemService(AudioManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val stat = StatFs(Environment.getDataDirectory().path)
        val freeGb = stat.availableBytes / (1024.0 * 1024.0 * 1024.0)
        val totalGb = stat.totalBytes / (1024.0 * 1024.0 * 1024.0)
        val bluetooth = runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled
        }.getOrNull()
        return jsonObject(
            "ok" to true,
            "action" to "device_status",
            "battery_percent" to level,
            "charging" to charging,
            "network" to networkType,
            "wifi" to (networkType == "wifi"),
            "bluetooth" to (bluetooth == true),
            "ringer" to ringerName(audio?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL),
            "volume_media" to percentOf(audio, AudioManager.STREAM_MUSIC),
            "volume_ring" to percentOf(audio, AudioManager.STREAM_RING),
            "volume_alarm" to percentOf(audio, AudioManager.STREAM_ALARM),
            "dnd" to (context.getSystemService(android.app.NotificationManager::class.java)
                ?.currentInterruptionFilter != android.app.NotificationManager.INTERRUPTION_FILTER_ALL),
            "screen_on" to (power?.isInteractive ?: false),
            "brightness" to runCatching {
                Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            }.getOrNull(),
            "storage_free_gb" to String.format(Locale.US, "%.1f", freeGb),
            "storage_total_gb" to String.format(Locale.US, "%.1f", totalGb),
            "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "android" to Build.VERSION.RELEASE,
        )
    }

    private fun percentOf(audio: AudioManager?, stream: Int): Int {
        if (audio == null) return 0
        val max = audio.getStreamMaxVolume(stream).coerceAtLeast(1)
        return audio.getStreamVolume(stream) * 100 / max
    }

    private fun ringerName(mode: Int): String = when (mode) {
        AudioManager.RINGER_MODE_SILENT -> "silent"
        AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
        else -> "normal"
    }

    // ── Calendar ──────────────────────────────────────────────────────────

    private fun addCalendarEvent(context: Context, params: JsonObject): JsonObject {
        if (!hasPermission(context, Manifest.permission.WRITE_CALENDAR)) {
            return needPermission(
                context,
                "WRITE_CALENDAR",
                context.getString(R.string.calendar_write_permission_needed),
            )
        }
        val title = params.str("title").trim().ifBlank { params.str("text").trim() }
        if (title.isBlank()) return errorResult(context.getString(R.string.missing_event_title))
        val start = parseDateTime(params.str("begin").ifBlank { params.str("start") })
            ?: return errorResult(context.getString(R.string.invalid_event_time))
        val minutes = params.int("duration_minutes", 60).coerceIn(1, 24 * 60)
        val end = start.plusMinutes(minutes.toLong())
        val calendarId = writableCalendarId(context)
            ?: return errorResult(context.getString(R.string.no_writable_calendar))
        val values = android.content.ContentValues().apply {
            put(CalendarContract.Events.DTSTART, start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
            put(CalendarContract.Events.DTEND, end.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
            params.str("location").takeIf { it.isNotBlank() }?.let {
                put(CalendarContract.Events.EVENT_LOCATION, it)
            }
            params.str("description").takeIf { it.isNotBlank() }?.let {
                put(CalendarContract.Events.DESCRIPTION, it)
            }
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: return errorResult(context.getString(R.string.event_not_saved))
        return jsonObject(
            "ok" to true,
            "action" to "add_calendar_event",
            "event" to title,
            "start" to start.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            "end" to end.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            "uri" to uri.toString(),
            "message" to context.getString(R.string.event_added, title),
        )
    }

    private fun readCalendar(context: Context, params: JsonObject): JsonObject {
        if (!hasPermission(context, Manifest.permission.READ_CALENDAR)) {
            return needPermission(
                context,
                "READ_CALENDAR",
                context.getString(R.string.calendar_read_permission_needed),
            )
        }
        val days = params.int("days", 7).coerceIn(1, 365)
        val limit = params.int("limit", 20).coerceIn(1, 100)
        val now = System.currentTimeMillis()
        val until = now + days * 86_400_000L
        val projection = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.ALL_DAY,
        )
        var eventCount = 0
        val events = buildJsonArray {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                "${CalendarContract.Events.DTEND} >= ? AND ${CalendarContract.Events.DTSTART} <= ?",
                arrayOf(now.toString(), until.toString()),
                "${CalendarContract.Events.DTSTART} ASC",
            )?.use { cursor ->
                val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                while (cursor.moveToNext() && eventCount < limit) {
                    eventCount++
                    val start = cursor.getLong(1)
                    val end = cursor.getLong(2)
                    add(
                        buildJsonObject {
                            put("title", cursor.getString(0).orEmpty())
                            put("start", format.format(Date(start)))
                            put("end", format.format(Date(end)))
                            put("location", cursor.getString(3).orEmpty())
                            put("all_day", cursor.getInt(4) == 1)
                        },
                    )
                }
            }
        }
        return jsonObject("ok" to true, "action" to "read_calendar", "days" to days, "events" to events)
    }

    private fun writableCalendarId(context: Context): Long? {
        val projection = arrayOf(CalendarContract.Calendars._ID)
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND " +
                "${CalendarContract.Calendars.VISIBLE} = 1",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            "${CalendarContract.Calendars.IS_PRIMARY} DESC",
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        return null
    }

    // ── Files ─────────────────────────────────────────────────────────────

    private fun saveFile(context: Context, params: JsonObject): JsonObject {
        val name = params.str("name").trim().ifBlank { "hermes-${System.currentTimeMillis()}.txt" }
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
        val content = params.str("content")
        val bytes = if (params.str("content_base64").isNotBlank()) {
            Base64.decode(params.str("content_base64"), Base64.DEFAULT)
        } else {
            content.toByteArray(Charsets.UTF_8)
        }
        if (bytes.isEmpty()) return errorResult(context.getString(R.string.empty_file))
        val mime = params.str("mime").ifBlank {
            when (safeName.substringAfterLast('.', "").lowercase()) {
                "txt", "md", "log", "csv" -> "text/plain"
                "json" -> "application/json"
                "pdf" -> "application/pdf"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                else -> "application/octet-stream"
            }
        }
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Hermes")
            }
            val target = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return errorResult(context.getString(R.string.file_not_saved))
            context.contentResolver.openOutputStream(target)?.use { it.write(bytes) }
                ?: return errorResult(context.getString(R.string.file_not_saved))
            target
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Hermes")
            if (!dir.exists() && !dir.mkdirs()) return errorResult(context.getString(R.string.file_not_saved))
            val file = File(dir, safeName)
            file.writeBytes(bytes)
            Uri.fromFile(file)
        }
        return jsonObject(
            "ok" to true,
            "action" to "save_file",
            "name" to safeName,
            "uri" to uri.toString(),
            "location" to "Download/Hermes/$safeName",
            "bytes" to bytes.size,
        )
    }

    private fun openFile(context: Context, params: JsonObject): JsonObject {
        val path = params.str("path").trim()
        if (path.isBlank() || params.bool("folder")) {
            context.startActivity(
                Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return jsonObject("ok" to true, "action" to "open_file", "mode" to "downloads")
        }
        val name = path.substringAfterLast('/')
        val uri = findDownload(context, name)
            ?: return errorResult(context.getString(R.string.file_not_found, path))
        val mime = context.contentResolver.getType(uri) ?: "*/*"
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return jsonObject("ok" to true, "action" to "open_file", "uri" to uri.toString())
    }

    private fun findDownload(context: Context, name: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val projection = arrayOf(MediaStore.Downloads._ID)
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Downloads.DISPLAY_NAME} = ?",
            arrayOf(name),
            "${MediaStore.Downloads.DATE_ADDED} DESC",
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                return Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
            }
        }
        return null
    }

    private fun pickFile(context: Context): JsonObject = jsonObject(
        "ok" to false,
        "error" to context.getString(R.string.pick_file_manual),
        "hint" to context.getString(R.string.pick_file_manual_hint),
    )

    private fun closeApp(context: Context): JsonObject {
        context.startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return jsonObject(
            "ok" to true,
            "action" to "close_app",
            "message" to context.getString(R.string.close_app_message),
        )
    }

    // ── Phone, SMS, contacts ──────────────────────────────────────────────

    private fun call(context: Context, params: JsonObject): JsonObject {
        var number = params.str("number").trim()
        var contactName = params.str("contact_name").trim()
        val action = DeviceActionCatalog.canonical(params.str("action"))
        val direct = params.bool("direct") || action == "place_call"
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

    private fun lookupContact(context: Context, params: JsonObject): JsonObject {
        val query = params.str("name").trim().ifBlank { params.str("query").trim() }
        if (query.isBlank()) return errorResult(context.getString(R.string.missing_contact))
        if (!hasPermission(context, Manifest.permission.READ_CONTACTS)) {
            return needPermission(
                context,
                "READ_CONTACTS",
                context.getString(R.string.contacts_permission_needed),
            )
        }
        val matches = findContacts(context, query)
        return jsonObject(
            "ok" to true,
            "action" to "lookup_contact",
            "query" to query,
            "contacts" to JsonArray(matches.take(10).map {
                buildJsonObject {
                    put("name", it.first)
                    put("number", it.second)
                }
            }),
        )
    }

    private fun sendSms(context: Context, params: JsonObject): JsonObject {
        var number = params.str("number").trim()
        val name = params.str("contact_name").trim()
        val body = params.str("message").trim()
        val direct = params.bool("direct") || params.bool("send")
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
        if (direct) {
            if (!hasPermission(context, Manifest.permission.SEND_SMS)) {
                return needPermission(
                    context,
                    "SEND_SMS",
                    context.getString(R.string.sms_permission_needed),
                )
            }
            @Suppress("DEPRECATION")
            val manager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(android.telephony.SmsManager::class.java)
            } else {
                android.telephony.SmsManager.getDefault()
            } ?: return errorResult(context.getString(R.string.sms_unavailable))
            manager.sendTextMessage(number, null, body, null, null)
            return jsonObject(
                "ok" to true,
                "action" to "send_sms",
                "number" to number,
                "mode" to "sent",
                "message" to context.getString(R.string.sms_sent, number),
            )
        }
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}"))
            .putExtra("sms_body", body)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return jsonObject(
            "ok" to true,
            "action" to "send_sms",
            "number" to number,
            "mode" to "composer",
            "message" to context.getString(R.string.sms_opened),
        )
    }

    @Suppress("DEPRECATION")
    private fun readSms(context: Context, params: JsonObject): JsonObject {
        if (!hasPermission(context, Manifest.permission.READ_SMS)) {
            return needPermission(
                context,
                "READ_SMS",
                context.getString(R.string.sms_read_permission_needed),
            )
        }
        var from = params.str("from").trim().ifBlank { params.str("number").trim() }
        val contact = params.str("contact_name").trim()
        if (from.isBlank() && contact.isNotBlank() &&
            hasPermission(context, Manifest.permission.READ_CONTACTS)
        ) {
            from = findContacts(context, contact).firstOrNull()?.second.orEmpty()
        }
        val limit = params.int("limit", 5).coerceIn(1, 20)
        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.READ,
        )
        var messageCount = 0
        val messages = buildJsonArray {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                projection,
                if (from.isNotBlank()) "${Telephony.Sms.ADDRESS} LIKE ?" else null,
                if (from.isNotBlank()) arrayOf("%$from%") else null,
                "${Telephony.Sms.DATE} DESC",
            )?.use { cursor ->
                val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                while (cursor.moveToNext() && messageCount < limit) {
                    messageCount++
                    add(
                        buildJsonObject {
                            put("from", cursor.getString(0).orEmpty())
                            put("text", cursor.getString(1).orEmpty())
                            put("date", format.format(Date(cursor.getLong(2))))
                            put("unread", cursor.getInt(3) == 0)
                        },
                    )
                }
            }
        }
        return jsonObject(
            "ok" to true,
            "action" to "read_sms",
            "from" to from,
            "messages" to messages,
        )
    }

    // ── Location and system settings ──────────────────────────────────────

    private suspend fun location(context: Context): JsonObject {
        if (!hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) &&
            !hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            return needPermission(
                context,
                "ACCESS_FINE_LOCATION",
                context.getString(R.string.location_permission_needed),
            )
        }
        val manager = context.getSystemService(LocationManager::class.java)
            ?: return errorResult(context.getString(R.string.location_unavailable))
        val providers = manager.getProviders(true)
        val last = providers.mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }
        val current = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            withTimeoutOrNull(12_000) {
                kotlinx.coroutines.suspendCancellableCoroutine<android.location.Location?> { continuation ->
                    val signal = android.os.CancellationSignal()
                    continuation.invokeOnCancellation { signal.cancel() }
                    runCatching {
                        manager.getCurrentLocation(
                            LocationManager.GPS_PROVIDER,
                            signal,
                            ContextCompat.getMainExecutor(context),
                            { location -> continuation.resumeWith(Result.success(location)) },
                        )
                    }.onFailure { continuation.resumeWith(Result.success(null)) }
                }
            }
        } else {
            null
        }
        val fix = current ?: last
            ?: return errorResult(context.getString(R.string.location_unknown))
        return jsonObject(
            "ok" to true,
            "action" to "location",
            "latitude" to fix.latitude,
            "longitude" to fix.longitude,
            "accuracy_m" to fix.accuracy.toInt(),
            "provider" to fix.provider.orEmpty(),
            "age_s" to ((System.currentTimeMillis() - fix.time) / 1000).coerceAtLeast(0),
            "maps_url" to "https://maps.google.com/?q=${fix.latitude},${fix.longitude}",
        )
    }

    private fun doNotDisturb(context: Context, params: JsonObject): JsonObject {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
            ?: return errorResult(context.getString(R.string.dnd_unavailable))
        if (!manager.isNotificationPolicyAccessGranted) {
            return needAccess(
                context,
                "do_not_disturb",
                context.getString(R.string.dnd_access_needed),
                Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS,
            )
        }
        val requested = params.bool("on", params.bool("enabled")) ||
            params.str("state").lowercase() in setOf("on", "true", "enable", "priority")
        val mode = params.str("mode").lowercase()
        val filter = when {
            mode == "alarms" -> android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS
            mode == "none" || mode == "total" -> android.app.NotificationManager.INTERRUPTION_FILTER_NONE
            requested -> android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY
            else -> android.app.NotificationManager.INTERRUPTION_FILTER_ALL
        }
        manager.setInterruptionFilter(filter)
        return jsonObject(
            "ok" to true,
            "action" to "do_not_disturb",
            "enabled" to (filter != android.app.NotificationManager.INTERRUPTION_FILTER_ALL),
        )
    }

    private fun toggleRinger(context: Context, params: JsonObject): JsonObject {
        val audio = context.getSystemService(AudioManager::class.java)
            ?: return errorResult(context.getString(R.string.audio_manager_unavailable))
        val requested = params.str("mode").ifBlank { params.str("state") }.lowercase()
        val mode = when (requested) {
            "silent", "mute", "off" -> AudioManager.RINGER_MODE_SILENT
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
            "normal", "sound", "on" -> AudioManager.RINGER_MODE_NORMAL
            "toggle" -> when (audio.ringerMode) {
                AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
                AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_NORMAL
                else -> AudioManager.RINGER_MODE_NORMAL
            }
            else -> return errorResult(context.getString(R.string.unknown_ringer_mode, requested))
        }
        return try {
            audio.ringerMode = mode
            jsonObject("ok" to true, "action" to "toggle_ringer", "ringer" to ringerName(mode))
        } catch (error: SecurityException) {
            needAccess(
                context,
                "toggle_ringer",
                context.getString(R.string.dnd_access_needed),
                Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS,
            )
        }
    }

    private fun setBrightness(context: Context, params: JsonObject): JsonObject {
        if (!Settings.System.canWrite(context)) {
            return needAccess(
                context,
                "brightness",
                context.getString(R.string.write_settings_needed),
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
            )
        }
        val percent = params.int("percent", params.int("level", -1))
        if (percent !in 0..100) return errorResult(context.getString(R.string.invalid_brightness))
        val value = (percent * 255 / 100).coerceIn(1, 255)
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
        )
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
        return jsonObject("ok" to true, "action" to "brightness", "percent" to percent)
    }

    // ── Notifications and clipboard ───────────────────────────────────────

    private fun readNotifications(context: Context, params: JsonObject): JsonObject {
        if (!HermesNotificationListener.isEnabled(context)) {
            return needAccess(
                context,
                "read_notifications",
                context.getString(R.string.notifications_access_needed),
                Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
            )
        }
        val limit = params.int("limit", 10).coerceIn(1, 50)
        val includeOngoing = params.bool("include_ongoing")
        val items = HermesNotificationListener.recent(context, limit, includeOngoing)
        return jsonObject(
            "ok" to true,
            "action" to "read_notifications",
            "notifications" to items,
        )
    }

    private fun readClipboard(context: Context): JsonObject {
        val manager = context.getSystemService(ClipboardManager::class.java)
            ?: return errorResult(context.getString(R.string.clipboard_unavailable))
        val clip = manager.primaryClip
        val text = (0 until (clip?.itemCount ?: 0))
            .mapNotNull { clip?.getItemAt(it)?.coerceToText(context)?.toString() }
            .firstOrNull { it.isNotBlank() }
            ?: return jsonObject(
                "ok" to false,
                "error" to context.getString(R.string.clipboard_empty),
                "hint" to context.getString(R.string.clipboard_foreground_hint),
            )
        return jsonObject("ok" to true, "action" to "read_clipboard", "text" to text)
    }

    private fun writeClipboard(context: Context, params: JsonObject): JsonObject {
        val text = params.str("text").trim().ifBlank { params.str("message").trim() }
        if (text.isBlank()) return errorResult(context.getString(R.string.missing_text))
        val manager = context.getSystemService(ClipboardManager::class.java)
            ?: return errorResult(context.getString(R.string.clipboard_unavailable))
        manager.setPrimaryClip(ClipData.newPlainText("Hermes", text))
        return jsonObject("ok" to true, "action" to "write_clipboard", "chars" to text.length)
    }

    // ── Screen capture ────────────────────────────────────────────────────

    private suspend fun screenCapture(context: Context): JsonObject {
        val deferred = ScreenCapture.request(context)
            ?: return errorResult(context.getString(R.string.screen_capture_busy))
        val result = withTimeoutOrNull(90_000) { deferred.await() }
        return result ?: errorResult(context.getString(R.string.screen_capture_timeout))
    }

    // ── Shared helpers ────────────────────────────────────────────────────

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

    private fun needAccess(
        context: Context,
        action: String,
        message: String,
        settingsAction: String,
    ): JsonObject = jsonObject(
        "ok" to false,
        "error" to message,
        "need_access" to action,
        "hint" to context.getString(R.string.access_hint, settingsAction),
        "access_action" to settingsAction,
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

    private fun parseDurationSeconds(raw: String): Int? {
        val text = raw.trim().lowercase()
        if (text.isBlank()) return null
        text.toIntOrNull()?.let { return it }
        if (text.contains(":")) {
            val parts = text.split(":").mapNotNull { it.toIntOrNull() }
            if (parts.size >= 2) {
                return when (parts.size) {
                    2 -> parts[0] * 60 + parts[1]
                    else -> parts[0] * 3600 + parts[1] * 60 + parts[2]
                }
            }
        }
        val match = Regex("(\\d+)\\s*(sek|sec|s|min|m|sat|h)").find(text) ?: return null
        val value = match.groupValues[1].toIntOrNull() ?: return null
        return when (match.groupValues[2]) {
            "sat", "h" -> value * 3600
            "min", "m" -> value * 60
            else -> value
        }
    }

    private fun formatDuration(seconds: Int): String = when {
        seconds % 3600 == 0 -> "${seconds / 3600} h"
        seconds >= 60 -> "${seconds / 60} min"
        else -> "$seconds s"
    }

    /** Accepts "HH:mm" (today/tomorrow), ISO local date-time, or epoch millis. */
    private fun parseDateTime(raw: String): LocalDateTime? {
        val text = raw.trim()
        if (text.isBlank()) {
            return LocalDateTime.now().withSecond(0).withNano(0)
        }
        text.toLongOrNull()?.let { millis ->
            return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDateTime()
        }
        runCatching { LocalDateTime.parse(text) }.getOrNull()?.let { return it }
        runCatching { OffsetDateTime.parse(text) }.getOrNull()?.let {
            return it.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
        }
        runCatching { Instant.parse(text) }.getOrNull()?.let {
            return it.atZone(ZoneId.systemDefault()).toLocalDateTime()
        }
        val time = runCatching { LocalTime.parse(text.take(5)) }.getOrNull() ?: return null
        val now = LocalDateTime.now()
        val candidate = LocalDate.now().atTime(time)
        return if (candidate.isBefore(now)) candidate.plusDays(1) else candidate
    }

    private data class AppInfo(val label: String, val packageName: String)
}
