package hr.sting.hermes.data

import android.content.Context
import hr.sting.hermes.R
import hr.sting.hermes.util.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

sealed interface LinkState {
    data object Idle : LinkState
    data object Connecting : LinkState
    data object Ready : LinkState
    data class Failed(val message: String) : LinkState
}

data class GatewayEvent(
    val type: String,
    val sessionId: String?,
    val payload: JsonObject,
    val seq: Long?,
)

data class GatewayRequest(
    val id: String,
    val method: String,
    val params: JsonObject,
)

class RpcError(val code: Int, message: String) : Exception(message)

/**
 * JSON-RPC over the Hermes dashboard `/api/ws` sidecar. Owns one socket,
 * reconnects with backoff, mints a fresh 30s ticket per attempt and fans
 * gateway events / server->client requests out as flows.
 */
class HermesRpc(
    private val rest: HermesRest,
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<GatewayEvent>(
        extraBufferCapacity = 2048,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<GatewayEvent> = _events.asSharedFlow()

    private val _requests = MutableSharedFlow<GatewayRequest>(extraBufferCapacity = 64)
    val requests: SharedFlow<GatewayRequest> = _requests.asSharedFlow()

    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    private val idGenerator = AtomicLong(1)

    @Volatile private var socket: WebSocket? = null
    @Volatile private var stopped = true
    @Volatile private var readySignal: CompletableDeferred<Unit>? = null
    @Volatile private var closedSignal: CompletableDeferred<Unit>? = null

    private var loopJob: Job? = null
    private var heartbeatJob: Job? = null

    fun start() {
        if (!stopped) return
        stopped = false
        AppLog.add("rpc start")
        loopJob = scope.launch { runLoop() }
    }

    fun stop() {
        AppLog.add("rpc stop")
        stopped = true
        loopJob?.cancel()
        loopJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        socket?.close(1000, "client closed")
        socket = null
        failAllPending(appContext.getString(R.string.rpc_connection_closed))
        _state.value = LinkState.Idle
    }

    suspend fun call(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
        timeoutMs: Long = 180_000,
    ): JsonObject {
        if (_state.value !is LinkState.Ready) {
            withTimeout(35_000) { _state.first { it is LinkState.Ready } }
        }
        val id = idGenerator.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        val frame = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        val sent = socket?.send(frame.toString()) == true
        if (!sent) {
            pending.remove(id)
            AppLog.add("rpc $method -> socket closed")
            throw RpcError(0, appContext.getString(R.string.server_connection_not_open))
        }
        AppLog.add("rpc $method -> sent")
        return try {
            val result = withTimeout(timeoutMs) { deferred.await() }
            AppLog.add("rpc $method -> ok")
            result
        } catch (error: Exception) {
            AppLog.add("rpc $method -> ${error.message ?: error.javaClass.simpleName}")
            throw error
        } finally {
            pending.remove(id)
        }
    }

    fun respond(id: String, result: JsonObject) {
        sendFrame(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", result)
            }.toString(),
        )
    }

    fun respondError(id: String, code: Int, message: String) {
        sendFrame(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put(
                    "error",
                    buildJsonObject {
                        put("code", code)
                        put("message", message)
                    },
                )
            }.toString(),
        )
    }

    fun isReady(): Boolean = _state.value is LinkState.Ready

    private fun sendFrame(raw: String) {
        socket?.send(raw)
    }

    private suspend fun runLoop() {
        var backoff = 1_000L
        while (!stopped) {
            var active: WebSocket? = null
            try {
                _state.value = LinkState.Connecting
                AppLog.add("ws connecting")
                rest.ensureAuthenticated()
                val ticket = rest.wsTicket()
                AppLog.add("ws ticket ok")
                val ready = CompletableDeferred<Unit>()
                val closed = CompletableDeferred<Unit>()
                readySignal = ready
                closedSignal = closed
                val request = Request.Builder().url(rest.webSocketUrl(ticket)).build()
                active = rest.client.newWebSocket(request, Listener(ready, closed))
                socket = active
                withTimeout(35_000) { ready.await() }
                _state.value = LinkState.Ready
                AppLog.add("ws ready")
                backoff = 1_000L
                call("client.capabilities", jsonObject("server_requests" to true))
                startHeartbeat()
                closed.await()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (stopped) break
                AppLog.add("ws failed: ${error.message ?: error.javaClass.simpleName}")
                _state.value = LinkState.Failed(
                    error.message ?: appContext.getString(R.string.rpc_connection_failed),
                )
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(15_000L)
            } finally {
                heartbeatJob?.cancel()
                heartbeatJob = null
                active?.close(1000, "reconnect")
                socket = null
                failAllPending(appContext.getString(R.string.rpc_connection_interrupted))
            }
        }
        _state.value = LinkState.Idle
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(20_000)
                socket?.send("""{"jsonrpc":"2.0","id":0,"method":"ping","params":{}}""")
            }
        }
    }

    private fun failAllPending(reason: String) {
        val error = RpcError(0, reason)
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
    }

    private inner class Listener(
        private val ready: CompletableDeferred<Unit>,
        private val closed: CompletableDeferred<Unit>,
    ) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            // gateway.ready arrives as the first frame; the loop waits for it.
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = runCatching {
                HermesJson.parseToJsonElement(text).jsonObject
            }.getOrNull() ?: return

            val method = frame.strOrNull("method")
            val idElement = frame["id"]

            if (method == null && idElement != null) {
                val id = runCatching { idElement.jsonPrimitive.longOrNull }.getOrNull() ?: return
                val deferred = pending.remove(id) ?: return
                val error = frame.obj("error")
                if (error != null) {
                    deferred.completeExceptionally(
                        RpcError(error.int("code", -1), error.str("message")),
                    )
                } else {
                    deferred.complete(frame.obj("result") ?: JsonObject(emptyMap()))
                }
                return
            }

            if (method == null) return

            if (idElement != null) {
                val requestId = runCatching { idElement.jsonPrimitive }.getOrNull()?.let {
                    runCatching { it.content }.getOrNull()
                } ?: idElement.toString()
                if (requestId.startsWith("srq-")) {
                    _requests.tryEmit(
                        GatewayRequest(
                            id = requestId,
                            method = method,
                            params = frame.obj("params") ?: JsonObject(emptyMap()),
                        ),
                    )
                }
                return
            }

            if (method != "event") return
            val params = frame.obj("params") ?: return
            val type = params.str("type")
            if (type.isBlank()) return
            if (type == "gateway.ready") {
                ready.complete(Unit)
            }
            _events.tryEmit(
                GatewayEvent(
                    type = type,
                    sessionId = params.strOrNull("session_id"),
                    payload = params.obj("payload") ?: JsonObject(emptyMap()),
                    seq = params["seq"]?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() },
                ),
            )
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            AppLog.add("ws closed code=$code reason=$reason")
            closed.complete(Unit)
            if (!stopped) {
                _state.value = LinkState.Failed(
                    reason.ifBlank { appContext.getString(R.string.rpc_connection_closed) },
                )
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            AppLog.add("ws onFailure: ${t.message ?: t.javaClass.simpleName}")
            ready.completeExceptionally(t)
            closed.complete(Unit)
            if (!stopped) {
                _state.value = LinkState.Failed(
                    t.message ?: appContext.getString(R.string.rpc_connection_failed),
                )
            }
        }
    }
}
