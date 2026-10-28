package hr.sting.hermes.device

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import hr.sting.hermes.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import hr.sting.hermes.data.jsonObject

/**
 * One-shot screen capture. Android only allows it after the user approves the
 * system dialog, so the device.action request opens [ScreenCaptureActivity] and
 * waits for the frame; the answer reaches the agent once the PNG is saved.
 */
object ScreenCapture {
    @Volatile private var pending: CompletableDeferred<JsonObject>? = null

    fun request(context: Context): CompletableDeferred<JsonObject>? {
        if (pending?.isActive == true) return null
        val deferred = CompletableDeferred<JsonObject>()
        pending = deferred
        return try {
            context.startActivity(
                Intent(context, ScreenCaptureActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            deferred
        } catch (error: Exception) {
            pending = null
            throw error
        }
    }

    fun active(): CompletableDeferred<JsonObject>? = pending

    fun complete(result: JsonObject) {
        pending?.complete(result)
        pending = null
    }
}

/** Transparent trampoline: asks for the projection consent and starts the service. */
class ScreenCaptureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ScreenCapture.active() == null) {
            finish()
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            ScreenCapture.complete(jsonObject("ok" to false, "error" to getString(R.string.screen_capture_unavailable)))
            finish()
            return
        }
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            ScreenCapture.complete(
                jsonObject("ok" to false, "error" to getString(R.string.screen_capture_denied)),
            )
            finish()
            return
        }
        runCatching {
            ContextCompat.startForegroundService(
                this,
                ScreenCaptureService.intent(this, resultCode, data),
            )
        }.onFailure { error ->
            ScreenCapture.complete(
                jsonObject(
                    "ok" to false,
                    "error" to error.message.orEmpty(),
                ),
            )
        }
        finish()
    }

    private companion object {
        const val REQUEST_CAPTURE = 41
    }
}
