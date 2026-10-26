package hr.sting.hermes.voice

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.QnnConfig

/**
 * Offline open-vocabulary keyword spotting (sherpa-onnx zipformer KWS).
 * The model and keyword list live in assets/kws; nothing leaves the device.
 */
class WakeWordDetector(private val context: Context) {
    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null

    fun initialize(): Boolean {
        if (spotter != null) return true
        return try {
            val config = KeywordSpotterConfig().apply {
                featConfig = FeatureConfig(16_000, 80, 0f)
                modelConfig = OnlineModelConfig().apply {
                    transducer = OnlineTransducerModelConfig(
                        "kws/encoder.int8.onnx",
                        "kws/decoder.int8.onnx",
                        "kws/joiner.int8.onnx",
                        QnnConfig(),
                    )
                    tokens = "kws/tokens.txt"
                    numThreads = 2
                    debug = false
                    provider = "cpu"
                }
                keywordsFile = "kws/keywords.txt"
                keywordsScore = 2.0f
                keywordsThreshold = 0.25f
                maxActivePaths = 4
                numTrailingBlanks = 1
            }
            spotter = KeywordSpotter(context.assets, config)
            stream = spotter?.createStream("")
            if (spotter == null || stream == null) error("KeywordSpotter nije stvorio stream")
            VoiceDiagnostics.update {
                it.copy(kwsReady = true, lastError = null)
            }
            Log.i(TAG, "KWS model loaded")
            true
        } catch (error: Throwable) {
            Log.e(TAG, "KWS init failed", error)
            VoiceDiagnostics.update {
                it.copy(kwsReady = false, lastError = "KWS: ${error.message ?: error.javaClass.simpleName}")
            }
            false
        }
    }

    /** Feed 16 kHz mono PCM16 samples. Returns the detected keyword or null. */
    fun accept(samples: ShortArray, count: Int): String? {
        val localSpotter = spotter ?: return null
        val localStream = stream ?: return null
        if (count <= 0) return null
        val floats = normalized(samples, count)
        localStream.acceptWaveform(floats, 16_000)
        while (localSpotter.isReady(localStream)) {
            localSpotter.decode(localStream)
            val result = localSpotter.getResult(localStream)
            val keyword = result.keyword
            if (keyword.isNotBlank()) {
                localSpotter.reset(localStream)
                VoiceDiagnostics.update { it.copy(lastDetection = keyword) }
                Log.i(TAG, "KWS detected: $keyword")
                return keyword
            }
        }
        return null
    }

    /** One-shot detection over a complete PCM16 buffer (used by the wake-word test). */
    fun detectPcm(samples: ShortArray): String? {
        val localSpotter = spotter ?: return null
        val localStream = localSpotter.createStream("") ?: return null
        return try {
            var offset = 0
            while (offset < samples.size) {
                val count = minOf(1_600, samples.size - offset)
                val floats = normalized(samples, count, offset)
                localStream.acceptWaveform(floats, 16_000)
                offset += count
                while (localSpotter.isReady(localStream)) {
                    localSpotter.decode(localStream)
                    val keyword = localSpotter.getResult(localStream).keyword
                    if (keyword.isNotBlank()) {
                        localSpotter.reset(localStream)
                        return keyword
                    }
                }
            }
            null
        } finally {
            runCatching { localStream.release() }
        }
    }

    /** Quiet phone mics often sit at 10-20% of full scale; lift them for the KWS model. */
    private fun normalized(samples: ShortArray, count: Int, offset: Int = 0): FloatArray {
        var peak = 0
        for (i in 0 until count) {
            val value = kotlin.math.abs(samples[offset + i].toInt())
            if (value > peak) peak = value
        }
        val gain = if (peak in 300..12_000) (12_000f / peak).coerceAtMost(4f) else 1f
        return FloatArray(count) {
            ((samples[offset + it] * gain) / 32768.0f).coerceIn(-1f, 1f)
        }
    }

    fun release() {
        VoiceDiagnostics.update { it.copy(kwsReady = false) }
        runCatching { stream?.release() }
        runCatching { spotter?.release() }
        stream = null
        spotter = null
    }

    private companion object {
        const val TAG = "HermesKws"
    }
}
