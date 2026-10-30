package hr.sting.hermes.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AppSettings(
    val serverUrl: String = DEFAULT_SERVER,
    val accessClientId: String = "",
    val accessClientSecret: String = "",
    val apiToken: String = "",
    val username: String = "marko",
    val password: String = "",
    val lastSessionId: String? = null,
    val ttsEnabled: Boolean = true,
    val handsFree: Boolean = false,
    val wakeWordEnabled: Boolean = false,
    val confirmDeviceActions: Boolean = true,
    /** Per-action override: action id -> allow / confirm / deny. */
    val actionPolicies: Map<String, String> = emptyMap(),
    val listenOnOpen: Boolean = false,
    val defaultView: String = "voice",
    val continuousVoice: Boolean = true,
    val sttEngine: String = "google",
    /** "auto" ends the capture after a pause; "manual" records until the user taps again. */
    val micMode: String = "auto",
    val ttsEngine: String = "android",
    val androidTtsVoice: String? = null,
    /**
     * Last FCM registration token this install saw. Cached so `device_status` can still answer
     * while Firebase is slow to answer or the phone is offline.
     */
    val fcmToken: String = "",
    val hasCompletedOnboarding: Boolean = false,
) {
    val isConfigured: Boolean
        get() = serverUrl.isNotBlank() &&
            (apiToken.isNotBlank() || (username.isNotBlank() && password.isNotBlank()))

    companion object {
        const val DEFAULT_SERVER = "https://hermes.sting.hr"
    }
}

class SettingsStore(context: Context) {
    private val secure = SecureStore(context)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val current: AppSettings get() = _settings.value

    // The service and the controller are different threads writing the same blob; a lost write
    // here would silently revert unrelated settings, so writes are serialized.
    @Synchronized
    fun save(value: AppSettings) {
        // Values pasted from a file/terminal often carry trailing newlines; an
        // Authorization header with a newline is rejected by OkHttp and a token
        // with whitespace is rejected by Hermes. Normalize once, here.
        val normalized = value.copy(
            serverUrl = value.serverUrl.trim().trimEnd('/'),
            accessClientId = value.accessClientId.trim(),
            accessClientSecret = value.accessClientSecret.trim(),
            apiToken = value.apiToken.trim(),
            username = value.username.trim(),
        )
        _settings.value = normalized
        secure.put(KEY, json.encodeToString(AppSettings.serializer(), normalized))
    }

    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings) = save(transform(current))

    fun clearCredentials() {
        save(
            current.copy(
                password = "",
                accessClientSecret = "",
                apiToken = "",
                lastSessionId = null,
                hasCompletedOnboarding = false,
            ),
        )
    }

    private fun load(): AppSettings {
        val raw = secure.get(KEY)
        if (raw.isBlank()) return AppSettings()
        return runCatching { json.decodeFromString(AppSettings.serializer(), raw) }
            .getOrDefault(AppSettings())
    }

    companion object {
        private const val KEY = "settings-v1"

        @Volatile private var sharedInstance: SettingsStore? = null

        /**
         * One store per process. A second instance would keep its own in-memory copy and overwrite
         * the other's writes, and the FCM service caches the push token from a Firebase thread
         * while the controller may be saving user settings.
         */
        fun shared(context: Context): SettingsStore = sharedInstance ?: synchronized(this) {
            sharedInstance ?: SettingsStore(context.applicationContext).also { sharedInstance = it }
        }
    }
}
