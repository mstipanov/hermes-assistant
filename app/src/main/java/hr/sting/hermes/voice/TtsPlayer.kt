package hr.sting.hermes.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Build
import hr.sting.hermes.data.HermesJson
import hr.sting.hermes.data.HermesRest
import hr.sting.hermes.data.int
import hr.sting.hermes.data.obj
import hr.sting.hermes.data.str
import hr.sting.hermes.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Streams assistant text to `/api/audio/speak-stream` and plays the raw PCM
 * frames as they arrive, so speech starts with the first sentence of the
 * reply. Stops on barge-in and ducks other audio via audio focus.
 */
class TtsPlayer(private val context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val sentinel = ByteArray(0)
    private val fullText = StringBuilder()
    private val pending = StringBuilder()

    private var socket: WebSocket? = null
    private var writer: ExecutorService? = null
    private var track: AudioTrack? = null
    private var fallbackPlayer: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var scope: CoroutineScope? = null
    private var rest: HermesRest? = null
    private var startupFallbackJob: Job? = null

    @Volatile private var open = false
    @Volatile private var stopped = false
    @Volatile private var finished = false
    @Volatile private var speaking = false
    @Volatile private var focusPaused = false

    var onSpeaking: ((Boolean) -> Unit)? = null

    fun start(rest: HermesRest, scope: CoroutineScope) {
        stop()
        this.rest = rest
        this.scope = scope
        stopped = false
        open = false
        finished = false
        fullText.clear()
        pending.clear()
        queue.clear()
        startupFallbackJob?.cancel()
        requestFocus()
        AppLog.add("tts start")
        VoiceDiagnostics.update { it.copy(lastTts = "starting") }
        scope.launch(Dispatchers.IO) {
            try {
                val ticket = rest.wsTicket()
                val request = Request.Builder().url(rest.audioWebSocketUrl(ticket)).build()
                socket = rest.client.newWebSocket(request, Listener())
            } catch (error: Exception) {
                AppLog.add("tts start failed: ${error.message ?: error.javaClass.simpleName}")
                VoiceDiagnostics.update { it.copy(lastTts = "failed: ${error.message}") }
                fallback()
            }
        }
    }

    fun feed(delta: String) {
        if (delta.isBlank() || stopped) return
        fullText.append(delta)
        synchronized(pending) {
            if (open) {
                sendText(delta)
            } else {
                pending.append(delta)
            }
        }
    }

    fun finish() {
        if (stopped) return
        finished = true
        if (open) {
            socket?.send("""{"done":true}""")
        }
        // If the stream produces no audible frame shortly after the full text
        // is known, fall back to the complete MP3 POST. This covers a dead
        // speak-stream provider and a prebuffer that never fills.
        startupFallbackJob?.cancel()
        startupFallbackJob = scope?.launch {
            delay(5_000)
            if (!stopped && !speaking && fullText.isNotBlank()) {
                AppLog.add("tts startup timeout -> MP3 fallback")
                fallback()
            }
        }
    }

    fun stop() {
        if (stopped && socket == null && track == null) return
        startupFallbackJob?.cancel()
        stopped = true
        open = false
        runCatching { socket?.send("""{"stop":true}""") }
        runCatching { socket?.close(1000, "barge in") }
        socket = null
        queue.clear()
        queue.offer(sentinel)
        writer?.shutdown()
        writer = null
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        runCatching { track?.release() }
        track = null
        runCatching { fallbackPlayer?.stop() }
        runCatching { fallbackPlayer?.release() }
        fallbackPlayer = null
        focusPaused = false
        abandonFocus()
        setSpeaking(false)
    }

    private fun sendText(text: String) {
        val payload = buildJsonObject { put("text", text) }
        socket?.send(payload.toString())
    }

    private fun ensureTrack(sampleRate: Int) {
        if (track != null || stopped) return
        val min = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4_096)
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        // A larger buffer plus a pre-roll absorbs the gaps between TTS sentences
        // and the start-up latency of the first sentence. Bluetooth needs this.
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(min * 4, 32_768))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = audioTrack
        VoiceDiagnostics.update { it.copy(lastTts = "buffering") }
        val executor = Executors.newSingleThreadExecutor()
        writer = executor
        executor.execute {
            var started = false
            var totalBytes = 0L
            val pending = ArrayList<ByteArray>()
            var pendingBytes = 0

            fun startPlayback() {
                if (started || stopped) return
                started = true
                startupFallbackJob?.cancel()
                runCatching { audioTrack.play() }
                setSpeaking(true)
                VoiceDiagnostics.update { it.copy(lastTts = "playing") }
                pending.forEach { chunk ->
                    totalBytes += writeFully(audioTrack, chunk)
                }
                pending.clear()
                pendingBytes = 0
            }

            try {
                var firstPendingAt = 0L
                while (true) {
                    val chunk = queue.poll(200, TimeUnit.MILLISECONDS)
                    if (chunk == null) {
                        if (!started && pendingBytes > 0 &&
                            System.currentTimeMillis() - firstPendingAt >= 450
                        ) {
                            startPlayback()
                        }
                        if (stopped) break
                        continue
                    }
                    if (chunk.isEmpty()) {
                        // End of stream: if nothing was long enough to pre-roll,
                        // still play whatever we have.
                        if (!started) startPlayback()
                        break
                    }
                    if (!started) {
                        if (pending.isEmpty()) firstPendingAt = System.currentTimeMillis()
                        pending += chunk
                        pendingBytes += chunk.size
                        // ~340 ms of 24 kHz PCM16 before the first audible sample.
                        if (pendingBytes >= PREBUFFER_BYTES) startPlayback()
                    } else {
                        totalBytes += writeFully(audioTrack, chunk)
                    }
                }

                if (!stopped && started) {
                    // Let the kernel/AudioTrack drain the buffered tail instead of
                    // cutting the last words with stop().
                    val totalFrames = (totalBytes / 2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    val deadline = System.currentTimeMillis() + 4_000
                    while (!stopped && System.currentTimeMillis() < deadline) {
                        val position = runCatching { audioTrack.playbackHeadPosition }.getOrDefault(0)
                        if (position >= totalFrames - 512) break
                        Thread.sleep(30)
                    }
                }
            } catch (_: Exception) {
                // Released underneath us on barge-in.
            } finally {
                runCatching { audioTrack.stop() }
                runCatching { audioTrack.release() }
                if (track === audioTrack) track = null
                setSpeaking(false)
            }
        }
    }

    private fun writeFully(track: AudioTrack, chunk: ByteArray): Long {
        var offset = 0
        var writtenTotal = 0L
        while (offset < chunk.size) {
            val written = runCatching {
                track.write(chunk, offset, chunk.size - offset, AudioTrack.WRITE_BLOCKING)
            }.getOrDefault(-1)
            if (written <= 0) break
            offset += written
            writtenTotal += written
        }
        return writtenTotal
    }

    private fun setSpeaking(value: Boolean) {
        if (speaking == value) return
        speaking = value
        onSpeaking?.invoke(value)
    }

    private fun requestFocus() {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { change -> handleFocusChange(change) }
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                { change -> handleFocusChange(change) },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            )
        }
    }

    /**
     * Rotation and a short notification should not kill an answer. Pause the
     * track on a transient loss and resume it when focus returns; only a
     * permanent loss stops the player.
     */
    private fun handleFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> stop()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> {
                if (track != null && speaking) {
                    focusPaused = true
                    runCatching { track?.pause() }
                    VoiceDiagnostics.update { it.copy(lastTts = "paused (audio focus)") }
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (focusPaused) {
                    focusPaused = false
                    runCatching { track?.play() }
                    VoiceDiagnostics.update { it.copy(lastTts = "playing") }
                }
            }
        }
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        focusRequest = null
    }

    private fun fallback() {
        startupFallbackJob?.cancel()
        runCatching { socket?.close(1000, "fallback") }
        socket = null
        open = false
        AppLog.add("tts fallback (POST /api/audio/speak)")
        val text = fullText.toString().trim()
        val client = rest
        val coroutineScope = scope
        if (text.isBlank() || client == null || coroutineScope == null || stopped) {
            setSpeaking(false)
            return
        }
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val bytes = client.speak(text)
                val file = File.createTempFile("hermes-tts-", ".mp3", context.cacheDir)
                file.writeBytes(bytes)
                val player = MediaPlayer()
                fallbackPlayer = player
                player.setDataSource(file.absolutePath)
                player.setOnCompletionListener {
                    it.release()
                    file.delete()
                    if (fallbackPlayer === it) fallbackPlayer = null
                    VoiceDiagnostics.update { it.copy(lastTts = "fallback finished") }
                    setSpeaking(false)
                }
                player.prepare()
                player.start()
                setSpeaking(true)
            } catch (error: Exception) {
                AppLog.add("tts fallback failed: ${error.message ?: error.javaClass.simpleName}")
                VoiceDiagnostics.update { it.copy(lastTts = "failed: ${error.message}") }
                setSpeaking(false)
            }
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (stopped) {
                webSocket.close(1000, "cancelled")
                return
            }
            open = true
            val buffered = synchronized(pending) {
                val text = pending.toString()
                pending.clear()
                text
            }
            if (buffered.isNotBlank()) sendText(buffered)
            if (finished) webSocket.send("""{"done":true}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val obj = runCatching { HermesJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            when (obj.str("type")) {
                "start" -> {
                    val rate = obj.int("sample_rate", 24_000)
                    AppLog.add("tts stream rate=$rate")
                    ensureTrack(rate)
                }
                "fallback" -> {
                    webSocket.close(1000, "fallback")
                    fallback()
                }
                "end" -> {
                    AppLog.add("tts stream end")
                    queue.offer(sentinel)
                    webSocket.close(1000, "done")
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
            if (!stopped) queue.offer(bytes.toByteArray())
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            AppLog.add("tts stream failed: ${t.message ?: t.javaClass.simpleName}")
            VoiceDiagnostics.update { it.copy(lastTts = "failed: ${t.message ?: t.javaClass.simpleName}") }
            if (!stopped) fallback()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            open = false
        }
    }
}

private const val PREBUFFER_BYTES = 16_384
