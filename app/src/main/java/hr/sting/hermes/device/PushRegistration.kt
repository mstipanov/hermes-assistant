package hr.sting.hermes.device

import android.content.Context
import android.os.Build
import com.google.firebase.messaging.FirebaseMessaging
import hr.sting.hermes.BuildConfig
import hr.sting.hermes.util.AppLog
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Keeps this install registered with the push relay at [BuildConfig.PUSH_BASE_URL].
 *
 * The relay needs the FCM token, the phone model and the package to know which device a job
 * decision belongs to: the server can only push once it has a token, and the token changes on
 * reinstall, app data clear or an FCM refresh. Registration is best effort — a failed POST only
 * costs a push, the app keeps working, so failures are logged and swallowed.
 */
object PushRegistration {
    private const val TIMEOUT_S = 15L

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** Registration for the current token, off the main thread. Safe to call on every start. */
    fun refresh(context: Context) {
        val app = context.applicationContext
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            val token = if (task.isSuccessful) task.result.orEmpty() else ""
            if (token.isBlank()) {
                AppLog.add("Push token unavailable: ${task.exception?.message ?: "no token"}")
                return@addOnCompleteListener
            }
            uploadAsync(app, token)
        }
    }

    /** [upload] on its own thread: the caller is a service callback that must return quickly. */
    fun uploadAsync(context: Context, token: String) {
        val app = context.applicationContext
        Thread({ upload(app, token) }, "push-token-upload").start()
    }

    /** Blocking; call from a worker thread. Never throws. */
    fun upload(context: Context, token: String) {
        if (token.isBlank()) return
        // A token is a credential for this phone: it goes to the relay, never into AppLog.
        val payload = JSONObject()
            .put("token", token)
            .put("device", Build.MODEL)
            .put("package", context.packageName)
            .toString()
        val request = Request.Builder()
            .url(BuildConfig.PUSH_BASE_URL.trimEnd('/') + "/api/token")
            .post(payload.toRequestBody(jsonMedia))
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    AppLog.add("Push token registered")
                } else {
                    AppLog.add("Push token rejected: HTTP ${response.code}")
                }
            }
        }.onFailure { error ->
            AppLog.add("Push token upload failed: ${error.message}")
        }
    }
}
