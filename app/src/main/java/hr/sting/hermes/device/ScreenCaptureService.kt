package hr.sting.hermes.device

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.MediaStore
import hr.sting.hermes.R
import hr.sting.hermes.data.jsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.Locale

/**
 * Keeps the projection alive only long enough to grab one frame, saves it under
 * Pictures/Hermes and completes the pending ScreenCapture request.
 */
class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (resultCode == 0 || data == null) {
            fail(getString(R.string.screen_capture_unavailable))
            return START_NOT_STICKY
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            fail(getString(R.string.screen_capture_unavailable))
            return START_NOT_STICKY
        }
        try {
            projection = manager.getMediaProjection(resultCode, data)
            captureOneFrame()
        } catch (error: Exception) {
            fail(error.message ?: error.javaClass.simpleName)
        }
        return START_NOT_STICKY
    }

    private fun captureOneFrame() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = imageReader
        val handlerThread = HandlerThread("hermes-screen-capture").also { it.start() }
        thread = handlerThread
        val handler = Handler(handlerThread.looper)
        val display = projection?.createVirtualDisplay(
            "hermes-capture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            handler,
        )
        virtualDisplay = display
        imageReader.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                val bitmap = toBitmap(image, width, height)
                val saved = save(bitmap)
                ScreenCapture.complete(
                    jsonObject(
                        "ok" to true,
                        "action" to "screen_capture",
                        "uri" to saved.first.toString(),
                        "location" to saved.second,
                        "width" to width,
                        "height" to height,
                    ),
                )
            } catch (error: Exception) {
                fail(error.message ?: error.javaClass.simpleName)
            } finally {
                image.close()
                cleanup()
                stopSelf()
            }
        }, handler)
        handler.postDelayed({
            if (ScreenCapture.active() != null) {
                fail(getString(R.string.screen_capture_timeout))
                cleanup()
                stopSelf()
            }
        }, 20_000)
    }

    private fun toBitmap(image: Image, width: Int, height: Int): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val padded = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888,
        )
        padded.copyPixelsFromBuffer(buffer)
        val bitmap = Bitmap.createBitmap(padded, 0, 0, width, height)
        if (bitmap !== padded) padded.recycle()
        return bitmap
    }

    private fun save(bitmap: Bitmap): Pair<Uri, String> {
        val name = "hermes-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
            .format(java.util.Date()) + ".png"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Hermes")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create the screenshot")
            contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return uri to "Pictures/Hermes/$name"
        }
        val dir = File(getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "Hermes")
        dir.mkdirs()
        val file = File(dir, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(file) to file.absolutePath
    }

    private fun fail(message: String) {
        ScreenCapture.complete(jsonObject("ok" to false, "error" to message))
    }

    private fun cleanup() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { reader?.close() }
        reader = null
        runCatching { projection?.stop() }
        projection = null
        thread?.quitSafely()
        thread = null
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.screen_capture_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.screen_capture_title))
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "hermes-screen-capture"
        private const val NOTIFICATION_ID = 4712
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "data"

        fun intent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
    }
}
