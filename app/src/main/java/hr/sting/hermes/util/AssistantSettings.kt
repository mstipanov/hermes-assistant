package hr.sting.hermes.util

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * Helpers for sending the user to the exact Android/Samsung screen that controls
 * the default digital assistant and the side-key gesture. No hidden settings are
 * changed from the app; these are ordinary intents with graceful fallbacks.
 */
object AssistantSettings {
    private const val SAMSUNG_SETTINGS = "com.samsung.android.settings"

    fun isRoleHeld(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val manager = context.getSystemService(RoleManager::class.java) ?: return false
        return manager.isRoleAvailable(RoleManager.ROLE_ASSISTANT) &&
            manager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
    }

    fun roleRequestIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val manager = context.getSystemService(RoleManager::class.java) ?: return null
        if (!manager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) return null
        return manager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
    }

    /** Default apps screen: the closest standard destination to "Digital assistant app". */
    fun openDefaultApps(context: Context): Boolean =
        open(context, Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))

    /** Stock Android's "Assist & voice input" screen. */
    fun openVoiceInput(context: Context): Boolean =
        open(context, Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))

    /**
     * Samsung's side-key screen. One UI does not publish a stable action, so ask
     * PackageManager for whatever activity in the Settings package carries the
     * SideKey name; fall back to the Settings home screen.
     */
    fun openSideKey(context: Context): Boolean {
        val component = samsungActivity(context, "sidekey") ?: samsungActivity(context, "side key")
        if (component != null && open(context, Intent().setComponent(component))) return true
        return open(context, Intent(Settings.ACTION_SETTINGS))
    }

    fun openAssistantSettings(context: Context): Boolean {
        val samsung = samsungActivity(context, "assistant")
        if (samsung != null && open(context, Intent().setComponent(samsung))) return true
        return openDefaultApps(context) || openVoiceInput(context) ||
            open(context, Intent(Settings.ACTION_SETTINGS))
    }

    private fun samsungActivity(context: Context, keyword: String): ComponentName? {
        val manager = context.packageManager
        return runCatching {
            @Suppress("DEPRECATION")
            val info = manager.getPackageInfo(SAMSUNG_SETTINGS, PackageManager.GET_ACTIVITIES)
            info.activities
                ?.map { it.name }
                ?.firstOrNull { name ->
                    name.contains(keyword.replace(" ", ""), ignoreCase = true) ||
                        name.contains(keyword, ignoreCase = true)
                }
                ?.let { ComponentName(SAMSUNG_SETTINGS, it) }
        }.getOrNull()
    }

    private fun open(context: Context, intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}
