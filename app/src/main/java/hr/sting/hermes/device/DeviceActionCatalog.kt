package hr.sting.hermes.device

import android.Manifest
import android.os.Build
import hr.sting.hermes.R
import hr.sting.hermes.data.AppSettings

/**
 * The device actions Hermes may ask the phone to perform, together with how
 * dangerous each one is and which Android permission (if any) it needs.
 *
 * The policy per action is stored in [AppSettings.actionPolicies] as
 * `action id -> allow | confirm | deny`. Anything not overridden falls back to
 * the action's default, and the old global "confirm device actions" switch
 * still turns every default-confirm action into a silent one when disabled.
 */
enum class ActionPolicy(val wire: String) {
    ALLOW("allow"),
    CONFIRM("confirm"),
    DENY("deny");

    companion object {
        fun fromWire(value: String?): ActionPolicy? =
            entries.firstOrNull { it.wire == value?.trim()?.lowercase() }
    }
}

/** Android access that cannot be granted with a normal runtime prompt. */
enum class SpecialAccess {
    WRITE_SETTINGS,
    DO_NOT_DISTURB,
    NOTIFICATIONS,
}

data class DeviceActionSpec(
    val id: String,
    val labelRes: Int,
    val descriptionRes: Int,
    val defaultPolicy: ActionPolicy = ActionPolicy.ALLOW,
    val permissions: List<String> = emptyList(),
    val access: SpecialAccess? = null,
    /** Shown in the permission matrix, but never executed by this app. */
    val aliases: List<String> = emptyList(),
)

object DeviceActionCatalog {

    const val ALLOW = "allow"
    const val CONFIRM = "confirm"
    const val DENY = "deny"

    private fun spec(
        id: String,
        label: Int,
        description: Int,
        policy: ActionPolicy = ActionPolicy.ALLOW,
        permissions: List<String> = emptyList(),
        access: SpecialAccess? = null,
        aliases: List<String> = emptyList(),
    ) = DeviceActionSpec(id, label, description, policy, permissions, access, aliases)

    val all: List<DeviceActionSpec> = listOf(
        spec("send_message", R.string.action_send_message, R.string.action_send_message_desc, ActionPolicy.CONFIRM),
        spec("set_timer", R.string.action_set_timer, R.string.action_set_timer_desc, aliases = listOf("timer")),
        spec("show_alarms", R.string.action_show_alarms, R.string.action_show_alarms_desc, aliases = listOf("alarms")),
        spec("navigate", R.string.action_navigate, R.string.action_navigate_desc, aliases = listOf("directions", "route")),
        spec("show_on_map", R.string.action_show_on_map, R.string.action_show_on_map_desc, aliases = listOf("map")),
        spec("open_app", R.string.action_open_app, R.string.action_open_app_desc, aliases = listOf("launch_app")),
        spec("list_apps", R.string.action_list_apps, R.string.action_list_apps_desc),
        spec("open_url", R.string.action_open_url, R.string.action_open_url_desc),
        spec("web_search", R.string.action_web_search, R.string.action_web_search_desc),
        spec("share_text", R.string.action_share_text, R.string.action_share_text_desc),
        spec("play_music", R.string.action_play_music, R.string.action_play_music_desc),
        spec("media_control", R.string.action_media_control, R.string.action_media_control_desc, aliases = listOf("media")),
        spec("set_volume", R.string.action_set_volume, R.string.action_set_volume_desc, aliases = listOf("volume")),
        spec("voice_command", R.string.action_voice_command, R.string.action_voice_command_desc, ActionPolicy.CONFIRM),
        spec("vibrate", R.string.action_vibrate, R.string.action_vibrate_desc),
        spec("flashlight", R.string.action_flashlight, R.string.action_flashlight_desc, aliases = listOf("torch", "lamp")),
        spec("speak", R.string.action_speak, R.string.action_speak_desc),
        spec("device_status", R.string.action_device_status, R.string.action_device_status_desc, aliases = listOf("battery", "status", "wifi", "network", "device_info")),
        spec(
            "add_calendar_event",
            R.string.action_add_calendar_event,
            R.string.action_add_calendar_event_desc,
            ActionPolicy.CONFIRM,
            listOf(Manifest.permission.WRITE_CALENDAR),
        ),
        spec("read_calendar", R.string.action_read_calendar, R.string.action_read_calendar_desc, permissions = listOf(Manifest.permission.READ_CALENDAR)),
        spec("save_file", R.string.action_save_file, R.string.action_save_file_desc, ActionPolicy.CONFIRM),
        spec("open_file", R.string.action_open_file, R.string.action_open_file_desc, ActionPolicy.CONFIRM, aliases = listOf("open_file_folder")),
        spec("pick_file", R.string.action_pick_file, R.string.action_pick_file_desc),
        spec("close_app", R.string.action_close_app, R.string.action_close_app_desc),
        spec("dial_number", R.string.action_dial, R.string.action_dial_desc, ActionPolicy.CONFIRM),
        spec("place_call", R.string.action_place_call, R.string.action_place_call_desc, ActionPolicy.CONFIRM, listOf(Manifest.permission.CALL_PHONE), aliases = listOf("call_contact", "call")),
        spec("send_sms", R.string.action_send_sms, R.string.action_send_sms_desc, ActionPolicy.CONFIRM, aliases = listOf("sms", "send_text")),
        spec("read_sms", R.string.action_read_sms, R.string.action_read_sms_desc, ActionPolicy.CONFIRM, listOf(Manifest.permission.READ_SMS)),
        spec("lookup_contact", R.string.action_lookup_contact, R.string.action_lookup_contact_desc, permissions = listOf(Manifest.permission.READ_CONTACTS), aliases = listOf("contacts")),
        spec("location", R.string.action_location, R.string.action_location_desc, ActionPolicy.CONFIRM, locationPermissions()),
        spec("do_not_disturb", R.string.action_dnd, R.string.action_dnd_desc, ActionPolicy.CONFIRM, access = SpecialAccess.DO_NOT_DISTURB, aliases = listOf("dnd")),
        spec("toggle_ringer", R.string.action_toggle_ringer, R.string.action_toggle_ringer_desc, ActionPolicy.CONFIRM, access = SpecialAccess.DO_NOT_DISTURB, aliases = listOf("ringer", "silent")),
        spec("brightness", R.string.action_brightness, R.string.action_brightness_desc, ActionPolicy.CONFIRM, access = SpecialAccess.WRITE_SETTINGS),
        spec("read_notifications", R.string.action_read_notifications, R.string.action_read_notifications_desc, ActionPolicy.CONFIRM, access = SpecialAccess.NOTIFICATIONS, aliases = listOf("notifications")),
        spec("read_clipboard", R.string.action_read_clipboard, R.string.action_read_clipboard_desc, ActionPolicy.CONFIRM, aliases = listOf("clipboard")),
        spec("write_clipboard", R.string.action_write_clipboard, R.string.action_write_clipboard_desc),
        spec("screen_capture", R.string.action_screen_capture, R.string.action_screen_capture_desc, ActionPolicy.CONFIRM, aliases = listOf("screenshot")),
    )

    private fun locationPermissions(): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    private val byId: Map<String, DeviceActionSpec> = buildMap {
        all.forEach { spec ->
            put(spec.id, spec)
            spec.aliases.forEach { alias -> put(alias, spec) }
        }
    }

    fun find(action: String?): DeviceActionSpec? = byId[action?.trim()?.lowercase()]

    /** Canonical id for aliases, so policies and logs are consistent. */
    fun canonical(action: String): String = find(action)?.id ?: action.trim().lowercase()

    fun policy(settings: AppSettings, action: String): ActionPolicy {
        val spec = find(action)
        val canonical = spec?.id ?: action
        val stored = ActionPolicy.fromWire(settings.actionPolicies[canonical])
        if (stored != null) return stored
        val fallback = spec?.defaultPolicy ?: ActionPolicy.CONFIRM
        // The legacy master switch keeps working: turning confirmations off must
        // not start asking for every action, and must not silently allow the
        // obviously dangerous ones either.
        if (!settings.confirmDeviceActions && fallback == ActionPolicy.CONFIRM) {
            return ActionPolicy.ALLOW
        }
        return fallback
    }

    /** Actions whose policy can still be changed (everything in the catalog). */
    fun policyMap(settings: AppSettings): Map<String, ActionPolicy> =
        all.associate { it.id to policy(settings, it.id) }

    fun isRuntimePermissionRequired(spec: DeviceActionSpec): Boolean =
        spec.permissions.any { it in RUNTIME_PERMISSIONS }

    private val RUNTIME_PERMISSIONS = setOf(
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    /** Android 13+ notifications access is a user toggle, not a runtime prompt. */
    val supportsNotificationListener: Boolean get() = Build.VERSION.SDK_INT >= 21
}
