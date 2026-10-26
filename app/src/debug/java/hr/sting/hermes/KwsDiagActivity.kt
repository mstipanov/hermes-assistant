package hr.sting.hermes

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.content.Intent
import hr.sting.hermes.data.AppSettings
import hr.sting.hermes.data.jsonObject
import hr.sting.hermes.device.DeviceActions
import hr.sting.hermes.voice.AndroidSttRecognizer
import hr.sting.hermes.voice.AndroidTtsPlayer
import hr.sting.hermes.voice.VoiceDiagnostics
import hr.sting.hermes.voice.WakeWordDetector
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Debug-only diagnostic: loads the KWS model and runs it over a bundled sample. */
class KwsDiagActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TextView(this).apply { textSize = 18f; setPadding(32, 64, 32, 32) }
        setContentView(view)
        Thread {
            val result = runCatching {
                val detector = WakeWordDetector(applicationContext)
                val initialized = detector.initialize()
                val wav = assets.open("kws_test.wav").readBytes()
                val pcm = ShortArray((wav.size - 44) / 2)
                ByteBuffer.wrap(wav, 44, pcm.size * 2)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer()
                    .get(pcm)
                val keyword = if (initialized) detector.detectPcm(pcm) else null
                detector.release()
                hr.sting.hermes.voice.HermesService.start(applicationContext)
                Thread.sleep(2_000)
                val apps = runBlocking {
                    DeviceActions.execute(applicationContext, jsonObject("action" to "list_apps", "query" to ""))
                }
                val launch = runBlocking {
                    DeviceActions.execute(applicationContext, jsonObject("action" to "launch_app", "app_name" to "Settings"))
                }
                val googleSttAvailable = AndroidSttRecognizer(applicationContext).isAvailable()
                val ttsDone = CountDownLatch(1)
                var ttsStarted = false
                val androidTts = AndroidTtsPlayer(applicationContext)
                Thread.sleep(900)
                androidTts.start(
                    onStart = { ttsStarted = true },
                    onDone = { ttsDone.countDown() },
                    onError = { Log.w("KwsDiag", "Android TTS error: $it") },
                )
                androidTts.feed("Bok, ovo je test lokalnog glasa na Androidu.")
                androidTts.finish()
                val ttsFinished = ttsDone.await(20, TimeUnit.SECONDS)
                val ttsDiag = VoiceDiagnostics.state.value.lastTts
                androidTts.shutdown()
                (application as HermesApp).core.saveSettings(
                    AppSettings(
                        serverUrl = "http://10.0.2.2:9119",
                        apiToken = "test-token",
                        hasCompletedOnboarding = true,
                        defaultView = "voice",
                    ),
                )
                startActivity(Intent(this@KwsDiagActivity, MainActivity::class.java))
                "init=$initialized detected=$keyword googleStt=$googleSttAvailable apps=$apps launch=$launch ttsStarted=$ttsStarted ttsFinished=$ttsFinished ttsDiag=$ttsDiag diag=${VoiceDiagnostics.state.value}"
            }
            Log.i("KwsDiag", result.getOrElse { "FAILED: ${it.stackTraceToString()}" })
            runOnUiThread { view.text = result.getOrElse { "FAILED: ${it.message}" } }
        }.start()
    }
}
