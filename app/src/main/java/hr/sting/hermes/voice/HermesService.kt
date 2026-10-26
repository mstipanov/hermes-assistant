package hr.sting.hermes.voice

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioRecord.STATE_INITIALIZED
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import hr.sting.hermes.HermesApp
import hr.sting.hermes.HermesController
import hr.sting.hermes.MainActivity
import hr.sting.hermes.R
import hr.sting.hermes.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Always-on wake word service. It owns only the KWS microphone; after a
 * detection it hands the turn to the app-scoped HermesController, which records
 * the command, talks to the agent and plays the streamed TTS reply - even while
 * the screen is off. The KWS mic is released before the command recorder opens.
 */
class HermesService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var audioJob: Job? = null
    private var observerJob: Job? = null
    private var detector: WakeWordDetector? = null
    @Volatile private var activeRecord: AudioRecord? = null
    private var running = false

    private val core: HermesController get() = (application as HermesApp).core

    override fun onCreate() {
        super.onCreate()
        val micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        VoiceDiagnostics.update {
            it.copy(serviceRunning = true, micPermission = micGranted, lastError = null)
        }
        if (!micGranted) {
            VoiceDiagnostics.update {
                it.copy(lastError = "Dozvola za mikrofon nije dana. Postavke → Dozvole → Mikrofon.")
            }
            stopSelf()
            return
        }
        createChannel()
        try {
            startForegroundWithNotification()
        } catch (error: Exception) {
            Log.e(TAG, "startForeground failed", error)
            VoiceDiagnostics.update {
                it.copy(lastError = "Servis se nije mogao pokrenuti: ${error.message}")
            }
            stopSelf()
            return
        }
        running = true
        observerJob = scope.launch {
            core.ui
                .map { it.voiceState }
                .distinctUntilChanged()
                .collect { state ->
                    if (state == "idle") startWakeLoop() else stopWakeLoop()
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        stopWakeLoop()
        observerJob?.cancel()
        scope.cancel()
        detector?.release()
        detector = null
        VoiceDiagnostics.update { it.copy(serviceRunning = false, kwsReady = false) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startWakeLoop() {
        if (!running || audioJob?.isActive == true) return
        audioJob = scope.launch(Dispatchers.IO) {
            while (isActive && core.ui.value.voiceState == "idle") {
                val localDetector = detector ?: run {
                    val created = WakeWordDetector(applicationContext)
                    if (created.initialize()) {
                        detector = created
                        created
                    } else {
                        created.release()
                        null
                    }
                }
                if (localDetector == null) {
                    VoiceDiagnostics.update {
                        it.copy(
                            lastError = it.lastError
                                ?: "Wake word model se nije učitao (vidi KWS grešku).",
                        )
                    }
                    delay(8_000)
                    continue
                }
                val record = openRecord()
                if (record == null) {
                    VoiceDiagnostics.update {
                        it.copy(lastError = "Mikrofon je zauzet ili nedostupan.")
                    }
                    delay(2_000)
                    continue
                }
                activeRecord = record
                record.startRecording()
                val buffer = ShortArray(1_600)
                try {
                    while (isActive && core.ui.value.voiceState == "idle") {
                        val read = record.read(buffer, 0, buffer.size)
                        if (read <= 0) continue
                        val keyword = localDetector.accept(buffer, read)
                        if (keyword != null) {
                            record.stop()
                            record.release()
                            activeRecord = null
                            core.startVoice()
                            return@launch
                        }
                    }
                } catch (error: Exception) {
                    Log.w(TAG, "KWS mic loop", error)
                    VoiceDiagnostics.update {
                        it.copy(lastError = "Mikrofon: ${error.message ?: error.javaClass.simpleName}")
                    }
                } finally {
                    if (activeRecord === record) activeRecord = null
                    runCatching { record.stop() }
                    runCatching { record.release() }
                }
                delay(300)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openRecord(): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(
            16_000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (min <= 0) return null
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            16_000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(min * 3, 12_288),
        )
        if (record.state != STATE_INITIALIZED) {
            record.release()
            return null
        }
        return record
    }

    private fun stopWakeLoop() {
        runCatching { activeRecord?.stop() }
        activeRecord = null
        audioJob?.cancel()
        audioJob = null
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, HermesService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Hermes sluša")
            .setContentText("Reci \"Hey Hermes\"")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Zaustavi", stopIntent)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Hermes glasovni asistent",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Stalno slušanje wake worda i glasovni odgovori"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "HermesService"
        private const val CHANNEL_ID = "hermes_voice"
        private const val NOTIFICATION_ID = 4101
        const val ACTION_START = "hr.sting.hermes.START_VOICE"
        const val ACTION_STOP = "hr.sting.hermes.STOP_VOICE"

        fun start(context: Context) {
            val intent = Intent(context, HermesService::class.java).setAction(ACTION_START)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {
                // Background-start restriction (Android 12+): the next foreground
                // launch will start it again.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HermesService::class.java))
        }

        fun applySettings(context: Context, settings: AppSettings) {
            if (settings.handsFree && settings.hasCompletedOnboarding) start(context) else stop(context)
        }
    }
}
