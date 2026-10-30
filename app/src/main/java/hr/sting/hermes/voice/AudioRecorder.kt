package hr.sting.hermes.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioRecord.STATE_INITIALIZED
import android.media.MediaRecorder
import hr.sting.hermes.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * 16 kHz mono PCM16 recorder with a simple energy VAD so a tap on the mic
 * ends the utterance after a natural pause instead of requiring a second tap.
 */
class AudioRecorder(private val context: Context) {
    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    @Volatile private var cancelled = false
    @Volatile private var stopRequested = false
    private var activeRecord: AudioRecord? = null

    @SuppressLint("MissingPermission")
    suspend fun record(
        maxDurationMs: Long = 30_000,
        silenceWindowMs: Long = 1_600,
        minDurationMs: Long = 900,
        /** Start-stop mode: keep recording (no VAD) until [finish] is called. */
        manual: Boolean = false,
        /** Receives each PCM16 frame while recording (live dictation). */
        onFrame: ((ByteArray) -> Unit)? = null,
    ): ByteArray = withContext(Dispatchers.IO) {
        cancelled = false
        stopRequested = false
        val sampleRate = 16_000
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) error(context.getString(R.string.mic_unavailable))
        val bufferSize = maxOf(minBuffer * 4, 16_384)
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        if (record.state != STATE_INITIALIZED) {
            record.release()
            error(context.getString(R.string.mic_cannot_open))
        }
        activeRecord = record

        val output = ByteArrayOutputStream()
        val buffer = ShortArray(1024)
        val start = System.currentTimeMillis()
        var lastVoiceAt = start
        var heardVoice = false
        var noiseSum = 0.0
        var noiseFrames = 0
        var threshold = 0.012
        try {
            record.startRecording()
            while (isActive && !cancelled && !stopRequested) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                val bytes = ByteArray(read * 2)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(buffer, 0, read)
                output.write(bytes)
                runCatching { onFrame?.invoke(bytes) }

                var sum = 0.0
                for (i in 0 until read) {
                    val value = buffer[i].toDouble() / 32768.0
                    sum += value * value
                }
                val rms = sqrt(sum / read)
                val level = (rms * 8.0).coerceIn(0.0, 1.0).toFloat()
                _amplitude.value = level
                val now = System.currentTimeMillis()
                val elapsed = now - start
                // Calibrate the noise floor from the first ~400 ms, then require
                // ~2.5x that floor (with a hard minimum) to count as speech. This
                // survives phone mics that pick up fans/room hum but still opens
                // for a quiet first word.
                if (elapsed < 400) {
                    noiseSum += rms
                    noiseFrames++
                    if (noiseFrames > 0) {
                        threshold = (noiseSum / noiseFrames * 2.5).coerceAtLeast(0.010)
                    }
                }
                if (rms > threshold) {
                    heardVoice = true
                    lastVoiceAt = now
                }
                if (elapsed >= maxDurationMs) break
                // Start-stop mode: only the user's second tap ends the utterance,
                // so a pause to think never cuts the sentence in half.
                if (!manual) {
                    if (!heardVoice && elapsed >= 8_000) break
                    if (heardVoice && elapsed >= minDurationMs &&
                        now - lastVoiceAt >= silenceWindowMs
                    ) {
                        break
                    }
                }
            }
        } finally {
            runCatching { record.stop() }
            record.release()
            activeRecord = null
            _amplitude.value = 0f
        }
        WavCodec.wrapPcm(output.toByteArray(), sampleRate)
    }

    /** Fixed-duration capture with no VAD; used by the microphone/wake-word diagnostics. */
    @SuppressLint("MissingPermission")
    suspend fun recordFixed(durationMs: Long = 6_000): ByteArray = withContext(Dispatchers.IO) {
        cancelled = false
        val sampleRate = 16_000
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) error(context.getString(R.string.mic_unavailable))
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer * 4, 16_384),
        )
        if (record.state != STATE_INITIALIZED) {
            record.release()
            error(context.getString(R.string.mic_cannot_open))
        }
        activeRecord = record
        val output = ByteArrayOutputStream()
        val buffer = ShortArray(1024)
        val start = System.currentTimeMillis()
        try {
            record.startRecording()
            while (isActive && !cancelled && System.currentTimeMillis() - start < durationMs) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                val bytes = ByteArray(read * 2)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(buffer, 0, read)
                output.write(bytes)
                var peak = 0
                for (i in 0 until read) peak = maxOf(peak, kotlin.math.abs(buffer[i].toInt()))
                _amplitude.value = (peak / 32768f).coerceIn(0f, 1f)
            }
        } finally {
            runCatching { record.stop() }
            record.release()
            activeRecord = null
            _amplitude.value = 0f
        }
        WavCodec.wrapPcm(output.toByteArray(), sampleRate)
    }

    /** Ends a start-stop capture and keeps the audio (unlike [cancel], which discards it). */
    fun finish() {
        stopRequested = true
    }

    fun cancel() {
        cancelled = true
        runCatching { activeRecord?.stop() }
    }
}

object WavCodec {
    fun wrapPcm(pcm: ByteArray, sampleRate: Int): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort((channels * bitsPerSample / 8).toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray())
        header.putInt(pcm.size)
        return header.array() + pcm
    }
}
