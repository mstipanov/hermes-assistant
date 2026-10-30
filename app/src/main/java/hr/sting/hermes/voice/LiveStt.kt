package hr.sting.hermes.voice

import hr.sting.hermes.data.HermesJson
import hr.sting.hermes.data.HermesRest
import hr.sting.hermes.data.str
import hr.sting.hermes.util.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * Live dictation over `/api/audio/transcribe-stream`: the recorder pushes PCM
 * frames while the user speaks, the server answers with partial text and a final
 * transcript on `eos`. When the server has no live STT provider the caller keeps
 * the locally recorded WAV and uses the batch `/api/audio/transcribe` instead.
 */
class LiveStt(private val rest: HermesRest) {
    private val ready = CompletableDeferred<Boolean>()
    private val finalText = CompletableDeferred<String?>()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var sentAudio = false

    /** Called with each partial transcript while the user speaks. */
    var onPartial: ((String) -> Unit)? = null

    suspend fun connect(): Boolean {
        val ticket = runCatching { rest.wsTicket() }.getOrNull() ?: return false
        val request = Request.Builder()
            .url(rest.wsUrl("/api/audio/transcribe-stream", ticket))
            .build()
        socket = rest.client.newWebSocket(request, Listener())
        return withTimeoutOrNull(5_000) { ready.await() } == true
    }

    fun push(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        if (socket?.send(ByteString.of(*pcm)) == true) sentAudio = true
    }

    /** Signals end-of-speech and waits for the server's final transcript. */
    suspend fun finish(): String? {
        val ws = socket ?: return null
        if (!sentAudio) return null
        if (ws.send("""{"eos":true}""")) {
            val text = withTimeoutOrNull(30_000) { finalText.await() }
            runCatching { ws.close(1000, "done") }
            socket = null
            return text
        }
        socket = null
        return null
    }

    fun cancel() {
        val ws = socket
        socket = null
        runCatching { ws?.close(1000, "cancelled") }
        if (!finalText.isCompleted) finalText.complete(null)
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send("""{"sample_rate":16000}""")
            if (!ready.isCompleted) ready.complete(true)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = runCatching { HermesJson.parseToJsonElement(text).jsonObject }.getOrNull()
                ?: return
            when (frame.str("type")) {
                "partial" -> {
                    val partial = frame.str("text")
                    if (partial.isNotBlank()) onPartial?.invoke(partial)
                }

                "final" -> {
                    if (!finalText.isCompleted) finalText.complete(frame.str("transcript"))
                }

                "error" -> {
                    AppLog.add("live stt: ${frame.str("message")}")
                    if (!ready.isCompleted) ready.complete(false)
                    if (!finalText.isCompleted) finalText.complete(null)
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            AppLog.add("live stt socket failed: ${t.message ?: t.javaClass.simpleName}")
            if (!ready.isCompleted) ready.complete(false)
            if (!finalText.isCompleted) finalText.complete(null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!ready.isCompleted) ready.complete(false)
            if (!finalText.isCompleted) finalText.complete(null)
        }
    }
}
