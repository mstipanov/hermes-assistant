package hr.sting.hermes

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import hr.sting.hermes.data.ApiError
import hr.sting.hermes.data.AppSettings
import hr.sting.hermes.data.ApprovalPrompt
import hr.sting.hermes.data.ChatMessage
import hr.sting.hermes.data.ClarifyPrompt
import hr.sting.hermes.data.DeviceActionPrompt
import hr.sting.hermes.data.GatewayEvent
import hr.sting.hermes.data.GatewayRequest
import hr.sting.hermes.data.AttachmentKind
import hr.sting.hermes.data.FileTransfer
import hr.sting.hermes.data.FileTransferState
import hr.sting.hermes.data.HermesJson
import hr.sting.hermes.data.HermesRest
import hr.sting.hermes.data.HermesRpc
import hr.sting.hermes.data.HermesUiState
import hr.sting.hermes.data.LinkState
import hr.sting.hermes.data.MessageRole
import hr.sting.hermes.data.PendingAttachment
import hr.sting.hermes.data.SessionRow
import hr.sting.hermes.data.RpcError
import hr.sting.hermes.data.SettingsStore
import hr.sting.hermes.data.arr
import hr.sting.hermes.data.bool
import hr.sting.hermes.data.double
import hr.sting.hermes.data.int
import hr.sting.hermes.data.jsonObject
import hr.sting.hermes.data.long
import hr.sting.hermes.data.obj
import hr.sting.hermes.data.objects
import hr.sting.hermes.data.str
import hr.sting.hermes.data.strOrNull
import hr.sting.hermes.data.toClarifyQuestion
import hr.sting.hermes.data.toChatMessage
import hr.sting.hermes.data.toSessionRow
import hr.sting.hermes.device.DeviceActions
import hr.sting.hermes.device.FileInbox
import hr.sting.hermes.device.FileNotifier
import hr.sting.hermes.device.IncomingFile
import hr.sting.hermes.voice.AudioRecorder
import androidx.core.content.ContextCompat
import hr.sting.hermes.voice.AndroidSttRecognizer
import hr.sting.hermes.R
import hr.sting.hermes.voice.HermesService
import hr.sting.hermes.voice.AndroidTtsPlayer
import hr.sting.hermes.voice.SpeechText
import hr.sting.hermes.voice.TtsPlayer
import hr.sting.hermes.voice.VoiceDiagnostics
import hr.sting.hermes.voice.WakeWordDetector
import hr.sting.hermes.util.AppLog
import hr.sting.hermes.util.Updater
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class HermesController(private val app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settingsStore = SettingsStore(app)
    private val recorder = AudioRecorder(app)
    private val tts = TtsPlayer(app)
    private val androidTts = AndroidTtsPlayer(app)
    private val androidStt = AndroidSttRecognizer(app)

    private val _ui = MutableStateFlow(
        run {
            val saved = settingsStore.current
            HermesUiState(
                settings = saved,
                configured = saved.isConfigured && saved.hasCompletedOnboarding,
                showOnboarding = !(saved.isConfigured && saved.hasCompletedOnboarding),
                account = saved.username,
            )
        },
    )
    val ui: StateFlow<HermesUiState> = _ui.asStateFlow()

    private var rest: HermesRest? = null
    private var rpc: HermesRpc? = null
    private var connectJob: Job? = null
    private val collectors = mutableListOf<Job>()
    private var sessionsLoaded = false
    private var wasReady = false
    private var sessionsRefreshJob: Job? = null
    private var voiceJob: Job? = null
    private var ttsStarted = false
    private var lastKnownHistoryCount = 0
    private var historySyncJob: Job? = null
    private var syncingHistory = false
    private var turnWatchdogJob: Job? = null
    private var deviceConfirmJob: Job? = null

    /** Actions that can touch the real world; the user confirms them by default. */
    private val confirmableDeviceActions = setOf(
        "call_contact",
        "dial_number",
        "send_sms",
        "set_alarm",
        "open_url",
        "launch_app",
        "play_music",
        "media_control",
    )
    private var autoListenJob: Job? = null
    private var awaitingAutoListen = false
    private var speakThisTurn = false
    private var usingAndroidTts = false
    private var androidTtsFailed = false
    private val turnReply = StringBuilder()
    private val speechBuffer = StringBuilder()
    @Volatile private var ttsSpeaking = false
    @Volatile private var uiMode = "voice"

    init {
        androidStt.listener = object : AndroidSttRecognizer.Listener {
            override fun onPartial(text: String) {
                _ui.update { it.copy(partialTranscript = text) }
            }

            override fun onEndOfSpeech() {
                _ui.update { it.copy(voiceState = "transcribing") }
            }

            override fun onFinal(text: String) {
                VoiceDiagnostics.update { it.copy(lastStt = "google: $text") }
                _ui.update { it.copy(partialTranscript = text) }
                sendMessage(text, fromVoice = true)
            }

            override fun onNoSpeech() {
                _ui.update {
                    it.copy(
                        voiceState = "idle",
                        partialTranscript = "",
                        notice = app.getString(R.string.did_not_hear),
                    )
                }
            }

            override fun onRecoverableError(message: String) {
                VoiceDiagnostics.update { it.copy(lastStt = "google error: $message") }
                _ui.update {
                    it.copy(
                        notice = app.getString(R.string.switching_to_hermes_stt, message),
                        partialTranscript = "",
                    )
                }
                startVoiceServer()
            }

            override fun onFatalError(message: String) {
                VoiceDiagnostics.update { it.copy(lastStt = "google fatal: $message") }
                _ui.update {
                    it.copy(voiceState = "idle", partialTranscript = "", error = message)
                }
            }
        }
        tts.onSpeaking = { speaking ->
            ttsSpeaking = speaking
            _ui.update {
                it.copy(
                    voiceState = when {
                        speaking -> "speaking"
                        it.running -> "thinking"
                        else -> "idle"
                    },
                )
            }
            if (!speaking) maybeAutoListen()
        }
        androidTts.selectVoice(settingsStore.current.androidTtsVoice)
        scope.launch {
            androidTts.voices.collect { voices ->
                _ui.update { it.copy(androidTtsVoices = voices) }
            }
        }
        val settings = settingsStore.current
        if (settings.isConfigured && settings.hasCompletedOnboarding) {
            connect()
            if (settings.handsFree) HermesService.start(app)
        }
    }

    // ── Connection ─────────────────────────────────────────────────────────

    fun connect() {
        connectJob?.cancel()
        connectJob = scope.launch {
            stopRpc()
            sessionsLoaded = false
            wasReady = false
            val settings = settingsStore.current
            _ui.update {
                it.copy(
                    settings = settings,
                    configured = settings.isConfigured,
                    link = LinkState.Connecting,
                    error = null,
                    notice = null,
                )
            }
            try {
                AppLog.add("connect: ${settings.serverUrl}")
                val newRest = HermesRest(app, settings)
                newRest.ensureAuthenticated()
                AppLog.add("auth ok")
                val me = newRest.me()
                rest = newRest
                val newRpc = HermesRpc(newRest, app, scope)
                rpc = newRpc
                observeRpc(newRpc)
                newRpc.start()
                _ui.update {
                    it.copy(
                        account = me.str("user_id").ifBlank { settings.username },
                        showOnboarding = false,
                        configured = true,
                    )
                }
            } catch (error: Exception) {
                val message = humanError(error)
                AppLog.add("connect failed: $message")
                _ui.update {
                    it.copy(
                        link = LinkState.Failed(message),
                        error = message,
                        showOnboarding = !it.settings.hasCompletedOnboarding,
                    )
                }
            }
        }
    }

    private fun observeRpc(client: HermesRpc) {
        collectors.forEach { it.cancel() }
        collectors.clear()
        collectors += scope.launch {
            client.state.collect { link ->
                _ui.update { it.copy(link = link) }
                if (link is LinkState.Ready) {
                    if (!wasReady) {
                        wasReady = true
                        onReady()
                    }
                } else {
                    wasReady = false
                }
            }
        }
        collectors += scope.launch {
            client.events.collect { handleEvent(it) }
        }
        collectors += scope.launch {
            client.requests.collect { handleRequest(it) }
        }
    }

    private suspend fun onReady() {
        AppLog.add("session ready")
        if (!sessionsLoaded) {
            sessionsLoaded = true
            loadSessions()
            openInitialSession()
        } else {
            refreshCurrentSession()
        }
    }

    private fun stopRpc() {
        collectors.forEach { it.cancel() }
        collectors.clear()
        deviceConfirmJob?.cancel()
        deviceConfirmJob = null
        rpc?.stop()
        rpc = null
        _ui.update { it.copy(deviceConfirm = null) }
    }

    fun disconnect() {
        stopRpc()
        _ui.update { it.copy(link = LinkState.Idle) }
    }

    // ── Sessions ──────────────────────────────────────────────────────────

    private suspend fun loadSessions() {
        val client = rpc ?: return
        runCatching {
            val result = client.call("session.list", jsonObject("limit" to 60))
            result.objects("sessions").map { it.toSessionRow() }
        }.onSuccess { rows ->
            _ui.update { it.copy(sessions = rows) }
            val current = _ui.value.currentStoredId
            if (current != null) {
                rows.firstOrNull { it.id == current }?.let { lastKnownHistoryCount = it.messageCount }
            }
        }
    }

    private suspend fun openInitialSession() {
        val state = _ui.value
        if (state.currentRuntimeId != null) {
            refreshCurrentSession()
            return
        }
        if (state.settings.shareTelegramSession) {
            val last = state.settings.lastSessionId
            val lastIsTelegram = last != null &&
                state.sessions.any { it.id == last && it.source == "telegram" }
            val target = if (lastIsTelegram) last else findLatestTelegramSession()
            if (target != null && resumeSession(target, keepOnFailure = false)) return
        }
        val last = state.settings.lastSessionId
        if (!last.isNullOrBlank()) {
            val resumed = resumeSession(last, keepOnFailure = false)
            if (resumed) return
        }
        createSession()
    }

    private suspend fun findLatestTelegramSession(): String? {
        val client = rpc ?: return null
        return runCatching {
            client.call("session.list", jsonObject("limit" to 80))
                .objects("sessions")
                .firstOrNull { it.str("source").equals("telegram", ignoreCase = true) }
                ?.strOrNull("id")
        }.getOrNull()
    }

    /**
     * The Telegram gateway and the dashboard are separate processes. Telegram reloads the
     * transcript from the DB before every turn, so it sees our messages. Our own live session
     * keeps an in-memory mirror, so if Telegram wrote in the meantime we close and cold-resume
     * the stored session to pick those rows up. The DB turn lease already serializes concurrent
     * writes, so this is only about context freshness.
     */
    private suspend fun syncSharedSession() {
        val state = _ui.value
        if (!state.settings.shareTelegramSession || syncingHistory) return
        val stored = state.currentStoredId ?: return
        val client = rpc ?: return
        syncingHistory = true
        try {
            val rows = client.call("session.list", jsonObject("limit" to 80))
                .objects("sessions")
                .map { it.toSessionRow() }
            val row = rows.firstOrNull { it.id == stored } ?: return
            val dbCount = row.messageCount
            if (dbCount <= lastKnownHistoryCount) return
            if (state.currentRuntimeId != null && !state.running) {
                runCatching {
                    client.call("session.close", jsonObject("session_id" to state.currentRuntimeId))
                }
                val fresh = client.call(
                    "session.resume",
                    jsonObject(
                        "session_id" to stored,
                        "lazy" to false,
                        "omit_messages" to true,
                        "inline_images" to false,
                    ),
                )
                applySessionMeta(fresh, fallbackStoredId = stored, fallbackTitle = row.title)
                _ui.update {
                    it.copy(
                        notice = app.getString(
                            R.string.telegram_history_refreshed,
                            app.resources.getQuantityString(
                                R.plurals.message_count,
                                dbCount,
                                dbCount,
                            ),
                        ),
                    )
                }
                lastKnownHistoryCount = fresh.int("message_count", dbCount)
            } else {
                lastKnownHistoryCount = dbCount
            }
        } catch (error: Exception) {
            _ui.update {
                it.copy(
                    notice = app.getString(
                        R.string.telegram_history_failed,
                        humanError(error),
                    ),
                )
            }
        } finally {
            syncingHistory = false
        }
    }

    private fun scheduleHistoryBaseline() {
        if (!_ui.value.settings.shareTelegramSession) return
        historySyncJob?.cancel()
        historySyncJob = scope.launch {
            delay(800)
            val stored = _ui.value.currentStoredId ?: return@launch
            val client = rpc ?: return@launch
            runCatching {
                val rows = client.call("session.list", jsonObject("limit" to 80))
                    .objects("sessions")
                    .map { it.toSessionRow() }
                rows.firstOrNull { it.id == stored }?.messageCount
            }.onSuccess { count ->
                if (count != null) lastKnownHistoryCount = count
            }
        }
    }

    private suspend fun refreshCurrentSession() {
        val client = rpc ?: return
        // Prefer the DURABLE stored id: a runtime id dies when the dashboard
        // process restarts, and resuming it then fails with "session not found".
        val stored = _ui.value.currentStoredId
        val runtime = _ui.value.currentRuntimeId
        val target = stored ?: runtime ?: return
        val isTelegram = stored != null &&
            _ui.value.sessions.any { it.id == stored && it.source == "telegram" }
        val omit = isTelegram && _ui.value.settings.shareTelegramSession
        AppLog.add("refresh session target=$target (stored=${stored != null})")
        val result = runCatching {
            client.call(
                "session.resume",
                jsonObject(
                    "session_id" to target,
                    "lazy" to false,
                    "omit_messages" to omit,
                    "inline_images" to false,
                ),
            )
        }
        result.onSuccess { fresh ->
            if (omit) {
                applySessionMeta(fresh, fallbackStoredId = stored, fallbackTitle = _ui.value.title)
            } else {
                applySession(
                    result = fresh,
                    fallbackStoredId = stored,
                    fallbackTitle = _ui.value.title,
                )
            }
        }.onFailure { error ->
            if (isSessionNotFound(error)) {
                AppLog.add("stale session, recovering")
                recoverSession()
            } else {
                _ui.update { it.copy(error = humanError(error)) }
            }
        }
    }

    /** Older Hermes builds reject the optional voice flag (strict params model). */
    private fun isUnknownVoiceParam(error: Throwable): Boolean {
        val message = (error.message ?: "").lowercase()
        return message.contains("voice") && message.contains("extra inputs")
    }

    private fun isSessionNotFound(error: Throwable): Boolean {
        val message = (error.message ?: "").lowercase()
        return message.contains("session not found") || message.contains("no such session") ||
            message.contains("unknown session")
    }

    /** Rebuild from the durable stored id (or create a fresh session) after a dashboard restart. */
    private suspend fun recoverSession() {
        val client = rpc ?: return
        val state = _ui.value
        val stored = state.currentStoredId
        if (state.currentRuntimeId != null) {
            runCatching {
                client.call("session.close", jsonObject("session_id" to state.currentRuntimeId))
            }
        }
        _ui.update {
            it.copy(
                currentRuntimeId = null,
                currentStoredId = null,
                messages = emptyList(),
                running = false,
                approval = null,
                clarify = null,
                error = null,
            )
        }
        if (stored != null && resumeSession(stored, keepOnFailure = false)) return
        val telegram = if (_ui.value.settings.shareTelegramSession) findLatestTelegramSession() else null
        if (telegram != null && telegram != stored && resumeSession(telegram, keepOnFailure = false)) return
        createSession()
    }

    private suspend fun resumeSession(storedId: String, keepOnFailure: Boolean = true): Boolean {
        val client = rpc ?: return false
        val isTelegram = _ui.value.sessions.any { it.id == storedId && it.source == "telegram" }
        val omit = isTelegram && _ui.value.settings.shareTelegramSession
        AppLog.add("resume $storedId")
        val result = runCatching {
            client.call(
                "session.resume",
                jsonObject(
                    "session_id" to storedId,
                    "lazy" to false,
                    "omit_messages" to omit,
                    "inline_images" to false,
                ),
            )
        }
        return result.fold(
            onSuccess = {
                val row = _ui.value.sessions.firstOrNull { item -> item.id == storedId }
                applySession(it, fallbackStoredId = storedId, fallbackTitle = row?.title)
                true
            },
            onFailure = { error ->
                AppLog.add("resume failed: ${humanError(error)}")
                if (!keepOnFailure) {
                    settingsStore.update { it.copy(lastSessionId = null) }
                } else {
                    _ui.update { it.copy(error = humanError(error)) }
                }
                false
            },
        )
    }

    private suspend fun createSession() {
        val client = rpc ?: return
        AppLog.add("create session")
        try {
            val result = client.call(
                "session.create",
                jsonObject("source" to "mobile"),
            )
            applySession(result)
            AppLog.add("create session -> ${result.str("session_id")}")
            _ui.update { it.copy(link = LinkState.Ready, error = null) }
        } catch (error: Exception) {
            AppLog.add("create session failed: ${humanError(error)}")
            _ui.update { it.copy(error = humanError(error), running = false) }
        }
    }

    /** Update only the live ids/title/model, keeping the visible transcript untouched. */
    private fun applySessionMeta(
        result: JsonObject,
        fallbackStoredId: String? = null,
        fallbackTitle: String? = null,
    ) {
        val info = result.obj("info") ?: JsonObject(emptyMap())
        val storedId = result.strOrNull("stored_session_id")
            ?: result.strOrNull("session_key")
            ?: fallbackStoredId
        _ui.update {
            it.copy(
                currentRuntimeId = result.str("session_id").ifBlank { it.currentRuntimeId },
                currentStoredId = storedId ?: it.currentStoredId,
                title = info.str("title").ifBlank {
                    fallbackTitle?.takeIf { title -> title.isNotBlank() } ?: it.title
                },
                model = info.str("model").ifBlank { it.model },
                running = info.bool("running"),
            )
        }
        if (!storedId.isNullOrBlank()) {
            settingsStore.update { current -> current.copy(lastSessionId = storedId) }
        }
    }

    private fun applySession(
        result: JsonObject,
        fallbackStoredId: String? = null,
        fallbackTitle: String? = null,
    ) {
        val runtimeId = result.str("session_id")
        val storedId = result.strOrNull("stored_session_id")
            ?: result.strOrNull("session_key")
            ?: fallbackStoredId
        val info = result.obj("info") ?: JsonObject(emptyMap())
        val messages = result.objects("messages")
            .mapNotNull { it.toChatMessage() }
            .filter { message ->
                !(message.role == MessageRole.SYSTEM && message.text.isBlank())
            }
        val title = info.str("title").ifBlank {
            fallbackTitle?.takeIf { it.isNotBlank() } ?: app.getString(R.string.new_chat)
        }
        val model = info.str("model")
        val running = info.bool("running") || result.bool("running")
        lastKnownHistoryCount = result.int("message_count", messages.size)
        val sharedEmpty = _ui.value.settings.shareTelegramSession &&
            messages.isEmpty() && lastKnownHistoryCount > 0
        _ui.update {
            it.copy(
                currentRuntimeId = runtimeId,
                currentStoredId = storedId,
                title = title,
                model = model,
                messages = messages,
                running = running,
                error = null,
                approval = null,
                clarify = null,
                notice = if (sharedEmpty) {
                    app.getString(
                        R.string.telegram_continues,
                        app.resources.getQuantityString(
                            R.plurals.message_count,
                            lastKnownHistoryCount,
                            lastKnownHistoryCount,
                        ),
                    )
                } else {
                    it.notice
                },
            )
        }
        if (!storedId.isNullOrBlank()) {
            settingsStore.update { it.copy(lastSessionId = storedId) }
        }
    }

    fun newChat() {
        awaitingAutoListen = false
        autoListenJob?.cancel()
        stopVoice()
        stopTts()
        scope.launch {
            _ui.update {
                it.copy(
                    currentRuntimeId = null,
                    currentStoredId = null,
                    title = app.getString(R.string.new_chat),
                    messages = emptyList(),
                    running = false,
                    approval = null,
                    clarify = null,
                    error = null,
                )
            }
            createSession()
        }
    }

    fun selectSession(storedId: String) {
        awaitingAutoListen = false
        autoListenJob?.cancel()
        stopVoice()
        stopTts()
        scope.launch {
            _ui.update { it.copy(error = null, messages = emptyList(), running = false) }
            resumeSession(storedId)
        }
    }

    fun deleteSession(storedId: String) {
        val client = rpc ?: return
        scope.launch {
            runCatching { client.call("session.delete", jsonObject("session_id" to storedId)) }
            if (_ui.value.currentStoredId == storedId) {
                newChat()
            }
            loadSessions()
        }
    }

    // ── Voice ─────────────────────────────────────────────────────────────

    fun setUiMode(mode: String) {
        uiMode = mode
    }

    fun startVoice() {
        awaitingAutoListen = false
        autoListenJob?.cancel()
        if (_ui.value.running) {
            _ui.update {
                it.copy(notice = app.getString(R.string.hermes_still_answering))
            }
            return
        }
        if (_ui.value.voiceState == "speaking") {
            // Rotation / launch intent: never start the mic over the TTS.
            return
        }
        if (_ui.value.voiceState == "listening" || _ui.value.voiceState == "transcribing") {
            stopVoice()
            return
        }
        if (rpc?.isReady() != true) {
            _ui.update {
                it.copy(error = app.getString(R.string.not_connected_check_settings))
            }
            return
        }
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            _ui.update {
                it.copy(error = app.getString(R.string.allow_mic_in_settings))
            }
            return
        }
        if (_ui.value.settings.sttEngine == "google" && androidStt.isAvailable()) {
            startVoiceGoogle()
        } else {
            startVoiceServer()
        }
    }

    private fun startVoiceGoogle() {
        AppLog.add("stt google start")
        _ui.update {
            it.copy(voiceState = "listening", partialTranscript = "", error = null, notice = null)
        }
        scope.launch {
            // Let the wake-word service release the microphone first.
            delay(300)
            if (_ui.value.voiceState != "listening") return@launch
            if (!androidStt.start("hr-HR")) {
                _ui.update {
                    it.copy(notice = app.getString(R.string.google_stt_unavailable))
                }
                startVoiceServer()
            }
        }
    }

    private fun startVoiceServer() {
        if (voiceJob?.isActive == true) return
        voiceJob = scope.launch {
            _ui.update { it.copy(voiceState = "listening", error = null, notice = null) }
            try {
                val wav = recorder.record()
                if (wav.size <= 44) {
                    _ui.update { it.copy(voiceState = "idle") }
                    return@launch
                }
                _ui.update { it.copy(voiceState = "transcribing") }
                val client = rest ?: return@launch
                val transcript = client.transcribe(wav).trim()
                if (transcript.isBlank()) {
                    _ui.update {
                        it.copy(
                            voiceState = "idle",
                            notice = app.getString(R.string.did_not_hear),
                        )
                    }
                    return@launch
                }
                sendMessage(transcript, fromVoice = true)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                _ui.update { it.copy(voiceState = "idle") }
                throw cancelled
            } catch (error: Exception) {
                _ui.update {
                    it.copy(voiceState = "idle", error = humanError(error))
                }
            }
        }
    }

    fun stopVoice() {
        androidStt.cancel()
        recorder.cancel()
        voiceJob?.cancel()
        voiceJob = null
        _ui.update { it.copy(voiceState = "idle", partialTranscript = "") }
    }

    /** Manual "read this aloud" / TTS test; independent of the current turn. */
    fun speakText(text: String) {
        val client = rest
        val clean = SpeechText.sanitize(text)
        if (clean.isBlank()) return
        if (!_ui.value.settings.ttsEnabled) {
            _ui.update {
                it.copy(notice = app.getString(R.string.voice_replies_disabled))
            }
            return
        }
        AppLog.add("tts manual: ${clean.length} chars")
        stopTts()
        androidTtsFailed = false
        speakThisTurn = true
        ttsStarted = true
        turnReply.clear()
        turnReply.append(clean)
        if (useAndroidTtsForTurn()) {
            usingAndroidTts = true
            startAndroidTts()
            androidTts.feed(clean)
            androidTts.finish()
        } else {
            val online = client ?: return
            tts.start(online, scope)
            tts.feed(clean)
            tts.finish()
        }
        _ui.update { it.copy(voiceState = "speaking") }
    }

    fun testTts() = speakText(app.getString(R.string.tts_test_text))

    /**
     * Android's own engine can start speaking before the first sentence has
     * even finished arriving on the network. Hermes/Gabrijela remains the
     * higher-quality option and the fallback.
     */
    private fun useAndroidTtsForTurn(): Boolean {
        val settings = _ui.value.settings
        if (settings.ttsEngine != "android" || androidTtsFailed) return false
        if (androidTts.isReady && !androidTts.isAvailable) {
            androidTtsFailed = true
            _ui.update {
                it.copy(notice = app.getString(R.string.android_tts_no_croatian))
            }
            return false
        }
        return true
    }

    private fun startAndroidTts() {
        usingAndroidTts = true
        androidTts.start(
            onStart = {
                scope.launch {
                    ttsSpeaking = true
                    _ui.update { it.copy(voiceState = "speaking") }
                }
            },
            onDone = {
                scope.launch {
                    ttsSpeaking = false
                    _ui.update {
                        it.copy(voiceState = if (it.running) "thinking" else "idle")
                    }
                    maybeAutoListen()
                }
            },
            onError = { message ->
                scope.launch { fallbackAndroidTts(message) }
            },
        )
    }

    private fun fallbackAndroidTts(message: String) {
        if (androidTtsFailed || !speakThisTurn) return
        androidTtsFailed = true
        usingAndroidTts = false
        VoiceDiagnostics.update { it.copy(lastTts = "failed: $message") }
        AppLog.add("android tts failed: $message -> Hermes fallback")
        if (message.contains("hrvatski", ignoreCase = true)) {
            _ui.update {
                it.copy(notice = app.getString(R.string.android_tts_no_croatian))
            }
        }
        androidTts.stop()
        val client = rest ?: return
        val text = SpeechText.sanitize(turnReply.toString()).ifBlank {
            SpeechText.sanitize(
                _ui.value.messages.lastOrNull {
                    it.role == MessageRole.ASSISTANT && it.text.isNotBlank()
                }?.text.orEmpty(),
            )
        }
        if (text.isBlank()) return
        ttsSpeaking = true
        if (!ttsStarted) ttsStarted = true
        tts.start(client, scope)
        tts.feed(text)
        VoiceDiagnostics.update { it.copy(lastTts = "starting") }
        _ui.update { it.copy(voiceState = "speaking") }
        // If the reply already completed while Android was initializing,
        // finishTts() will not run again for this fallback.
        if (!_ui.value.running) {
            tts.finish()
            ttsStarted = false
        }
    }

    private fun feedTts(delta: String) {
        if (delta.isBlank()) return
        if (!speakThisTurn || !_ui.value.settings.ttsEnabled) return
        speechBuffer.append(delta)
        flushSpeechSentences(flush = false)
    }

    /**
     * Both TTS engines get clean sentences, never raw Markdown deltas. This is
     * also why tokens such as ``**`` and ``[](…)`` cannot leak into the audio.
     */
    private fun flushSpeechSentences(flush: Boolean) {
        if (!speakThisTurn) {
            speechBuffer.clear()
            return
        }
        val sentences = SpeechText.extract(speechBuffer, flush)
        sentences.forEach { emitSpeechSentence(it) }
    }

    private fun emitSpeechSentence(sentence: String) {
        if (sentence.isBlank()) return
        val state = _ui.value
        if (!speakThisTurn || !state.settings.ttsEnabled) return
        val android = useAndroidTtsForTurn()
        val client = rest
        if (!android && client == null) return
        if (!ttsStarted) {
            ttsStarted = true
            VoiceDiagnostics.update { it.copy(lastTts = "starting") }
            if (android) {
                startAndroidTts()
            } else {
                tts.start(client ?: return, scope)
            }
            _ui.update { it.copy(voiceState = "speaking") }
        }
        // Keep the player chosen at the start of this turn; a setting change
        // must not cut the answer in half.
        if (usingAndroidTts) androidTts.feed(sentence) else tts.feed(sentence)
    }

    private fun finishTts() {
        if (!speakThisTurn) {
            speechBuffer.clear()
            ttsStarted = false
            _ui.update {
                it.copy(voiceState = if (it.running) "thinking" else "idle")
            }
            // A voice turn with TTS disabled should still continue listening;
            // a typed turn has awaitingAutoListen=false and stops here.
            maybeAutoListen()
            return
        }
        flushSpeechSentences(flush = true)
        if (ttsStarted) {
            if (usingAndroidTts) androidTts.finish() else tts.finish()
            ttsStarted = false
            // Safety net for a provider that produced no audio: don't wait for
            // onSpeaking(false) forever.
            autoListenJob?.cancel()
            autoListenJob = scope.launch {
                delay(15_000)
                maybeAutoListen()
            }
        } else {
            _ui.update {
                it.copy(voiceState = if (it.running) "thinking" else "idle")
            }
            if (_ui.value.settings.continuousVoice) maybeAutoListen()
        }
    }

    /** After a spoken answer, open the mic again for the next turn. */
    private fun maybeAutoListen() {
        if (!awaitingAutoListen) return
        if (!_ui.value.settings.continuousVoice) return
        if (uiMode != "voice") return
        if (_ui.value.running) return
        if (_ui.value.approval != null || _ui.value.clarify != null) return
        if (ttsSpeaking) return
        if (rpc?.isReady() != true) return
        awaitingAutoListen = false
        autoListenJob?.cancel()
        autoListenJob = scope.launch {
            delay(500)
            if (_ui.value.settings.continuousVoice && uiMode == "voice" &&
                !_ui.value.running && _ui.value.approval == null &&
                _ui.value.clarify == null && rpc?.isReady() == true
            ) {
                AppLog.add("continuous voice: re-listen")
                startVoice()
            }
        }
    }

    private fun stopTts() {
        ttsStarted = false
        speakThisTurn = false
        usingAndroidTts = false
        ttsSpeaking = false
        speechBuffer.clear()
        tts.stop()
        androidTts.stop()
    }

    // ── Turns ─────────────────────────────────────────────────────────────

    fun updateInput(value: String) {
        _ui.update { it.copy(input = value) }
    }

    fun addAttachment(attachment: PendingAttachment) {
        if (attachment.bytes.isEmpty()) {
            _ui.update { it.copy(error = app.getString(R.string.attachment_empty_error)) }
            return
        }
        if (attachment.bytes.size > MAX_ATTACHMENT_BYTES) {
            _ui.update {
                it.copy(
                    error = app.getString(
                        R.string.attachment_too_large_error,
                        MAX_ATTACHMENT_BYTES / (1024 * 1024),
                    ),
                )
            }
            return
        }
        _ui.update {
            it.copy(
                pendingAttachments = (it.pendingAttachments + attachment).take(6),
                error = null,
            )
        }
        AppLog.add("attachment added: ${attachment.kind} ${attachment.name} ${attachment.bytes.size}B")
    }

    fun removeAttachment(id: String) {
        _ui.update { it.copy(pendingAttachments = it.pendingAttachments.filterNot { a -> a.id == id }) }
    }

    fun clearAttachments() {
        _ui.update { it.copy(pendingAttachments = emptyList()) }
    }

    private suspend fun uploadAttachments(sid: String, attachments: List<PendingAttachment>): List<String> {
        val client = rpc
            ?: throw RpcError(0, app.getString(R.string.server_connection_not_open))
        val fileRefs = mutableListOf<String>()
        for (attachment in attachments) {
            if (attachment.bytes.size > MAX_ATTACHMENT_BYTES) {
                throw RpcError(
                    0,
                    app.getString(R.string.attachment_named_too_large, attachment.name),
                )
            }
            val encoded = Base64.encodeToString(attachment.bytes, Base64.NO_WRAP)
            when (attachment.kind) {
                AttachmentKind.IMAGE -> {
                    client.call(
                        "image.attach_bytes",
                        jsonObject(
                            "session_id" to sid,
                            "content_base64" to encoded,
                            "filename" to attachment.name,
                            "ext" to attachment.name.substringAfterLast('.', "jpg"),
                        ),
                    )
                }
                AttachmentKind.PDF -> {
                    client.call(
                        "pdf.attach",
                        jsonObject(
                            "session_id" to sid,
                            "content_base64" to encoded,
                            "filename" to attachment.name,
                            "first_page" to 1,
                            "last_page" to 5,
                        ),
                    )
                }
                AttachmentKind.FILE -> {
                    val result = client.call(
                        "file.attach",
                        jsonObject(
                            "session_id" to sid,
                            "data_url" to "data:${attachment.mimeType};base64,$encoded",
                            "name" to attachment.name,
                        ),
                    )
                    val path = result.strOrNull("path")
                    val ref = result.strOrNull("ref_text")
                    if (!path.isNullOrBlank()) {
                        fileRefs += buildString {
                            append(app.getString(R.string.attached_file_prefix)).append(path)
                            if (!ref.isNullOrBlank()) append(" (").append(ref).append(")")
                        }
                    }
                }
            }
            AppLog.add("attached ${attachment.kind} ${attachment.name} (${attachment.bytes.size}B)")
        }
        return fileRefs
    }

    fun sendMessage(text: String = _ui.value.input, fromVoice: Boolean = false) {
        val trimmed = text.trim()
        val attachments = _ui.value.pendingAttachments
        if (trimmed.isEmpty() && attachments.isEmpty()) return
        awaitingAutoListen = fromVoice
        if (_ui.value.running) {
            _ui.update {
                it.copy(notice = app.getString(R.string.hermes_still_answering_stop))
            }
            return
        }
        val client = rpc
        if (client == null || !client.isReady()) {
            _ui.update {
                it.copy(
                    error = app.getString(R.string.not_connected_check_settings),
                )
            }
            return
        }
        stopTts()
        turnReply.clear()
        speechBuffer.clear()
        androidTtsFailed = false
        speakThisTurn = fromVoice && _ui.value.settings.ttsEnabled
        AppLog.add(
            "send message (${trimmed.length} chars, ${attachments.size} files, voice=$fromVoice, tts=$speakThisTurn)",
        )
        if (fromVoice && !_ui.value.settings.ttsEnabled) {
            _ui.update {
                it.copy(notice = app.getString(R.string.voice_replies_disabled_text_only))
            }
        }
        scope.launch {
            try {
                if (_ui.value.currentRuntimeId == null) createSession()
                if (_ui.value.settings.shareTelegramSession) syncSharedSession()
                val sid = _ui.value.currentRuntimeId ?: return@launch
                val fileRefs = if (attachments.isNotEmpty()) {
                    _ui.update { it.copy(notice = app.getString(R.string.sending_attachments)) }
                    val refs = uploadAttachments(sid, attachments)
                    _ui.update { it.copy(pendingAttachments = emptyList(), notice = null) }
                    refs
                } else {
                    emptyList()
                }
                val displayText = buildString {
                    attachments.forEach { append("📎 ${it.name}\n") }
                    append(trimmed.ifBlank { app.getString(R.string.attachment_prompt) })
                }
                val promptText = buildString {
                    if (trimmed.isNotBlank()) append(trimmed)
                    fileRefs.forEach { ref ->
                        if (isNotEmpty()) append("\n\n")
                        append(ref)
                    }
                    if (isEmpty()) append(app.getString(R.string.attachment_prompt))
                }
                val userMessage = ChatMessage(role = MessageRole.USER, text = displayText)
                _ui.update {
                    it.copy(
                        input = "",
                        running = true,
                        error = null,
                        lastTurnFromVoice = fromVoice,
                        voiceState = if (fromVoice) "thinking" else "idle",
                        messages = it.messages + userMessage,
                    )
                }
                startTurnWatchdog()
                AppLog.add("prompt.submit sid=$sid")
                val submitted = try {
                    client.call(
                        "prompt.submit",
                        jsonObject(
                            "session_id" to sid,
                            "text" to promptText,
                            "surface" to "mobile",
                            "voice" to speakThisTurn,
                        ),
                    )
                } catch (error: Exception) {
                    if (isUnknownVoiceParam(error)) {
                        // Keep working after a Hermes update that restores the
                        // old strict prompt.submit contract. The client-side
                        // SpeechText cleaner still keeps the audio readable.
                        AppLog.add("server rejects voice flag; retrying without it")
                        client.call(
                            "prompt.submit",
                            jsonObject(
                                "session_id" to sid,
                                "text" to promptText,
                                "surface" to "mobile",
                            ),
                        )
                    } else {
                        if (!isSessionNotFound(error)) throw error
                        AppLog.add("prompt hit stale session, recovering once")
                        recoverSession()
                        val fresh = _ui.value.currentRuntimeId
                            ?: throw RpcError(
                                0,
                                app.getString(R.string.session_could_not_recover),
                            )
                        client.call(
                            "prompt.submit",
                            jsonObject(
                                "session_id" to fresh,
                                "text" to trimmed,
                                "surface" to "mobile",
                            ),
                        )
                    }
                }
                AppLog.add("prompt.submit -> ${submitted.str("status")}")
            } catch (error: Exception) {
                AppLog.add("send failed: ${humanError(error)}")
                _ui.update {
                    it.copy(
                        running = false,
                        voiceState = "idle",
                        error = humanError(error),
                    )
                }
            }
        }
    }

    fun interrupt() {
        awaitingAutoListen = false
        autoListenJob?.cancel()
        turnWatchdogJob?.cancel()
        stopVoice()
        stopTts()
        val client = rpc ?: return
        val sid = _ui.value.currentRuntimeId ?: return
        scope.launch {
            runCatching {
                client.call("session.interrupt", jsonObject("session_id" to sid))
            }
            _ui.update { it.copy(running = false, voiceState = "idle") }
        }
    }

    // ── Server events ─────────────────────────────────────────────────────

    private fun handleEvent(event: GatewayEvent) {
        val state = _ui.value
        if (event.sessionId != null && state.currentRuntimeId != null &&
            event.sessionId != state.currentRuntimeId
        ) {
            if (event.type == "sessions.changed") scheduleSessionsRefresh()
            return
        }
        when (event.type) {
            "message.start" -> addAssistantPlaceholder()
            "message.delta" -> {
                val delta = event.payload.str("text")
                appendDelta(delta)
                turnReply.append(delta)
                feedTts(delta)
            }
            "message.complete" -> {
                val fullText = event.payload.str("text")
                if (fullText.isNotBlank()) {
                    turnReply.clear()
                    turnReply.append(fullText)
                }
                completeAssistant(fullText)
                // Fallback: some replies arrive only as message.complete (no deltas).
                // A voice turn must still be spoken, so start TTS from the final text.
                val ttsFailed = VoiceDiagnostics.state.value.lastTts?.startsWith("failed") == true
                if (speakThisTurn && _ui.value.settings.ttsEnabled && (!ttsStarted || ttsFailed)) {
                    val reply = _ui.value.messages.lastOrNull {
                        it.role == MessageRole.ASSISTANT && it.text.isNotBlank()
                    }?.text ?: fullText
                    if (reply.isNotBlank()) {
                        if (ttsFailed) {
                            tts.stop()
                            ttsStarted = false
                        }
                        feedTts(reply)
                    }
                }
                finishTts()
            }
            "tool.start" -> addToolStart(event.payload)
            "tool.complete" -> addToolComplete(event.payload)
            "thinking.delta" -> appendThinking(event.payload.str("text"))
            "reasoning.available" -> {
                val text = event.payload.str("text")
                if (text.isNotBlank()) {
                    _ui.update { current ->
                        val messages = current.messages
                        val last = messages.lastOrNull { it.role == MessageRole.ASSISTANT }
                        if (last != null && last.reasoning.isBlank()) {
                            current.copy(messages = messages.replace(last.id) { it.copy(reasoning = text) })
                        } else {
                            current
                        }
                    }
                }
            }
            "session.title" -> {
                val title = event.payload.str("title")
                if (title.isNotBlank()) _ui.update { it.copy(title = title) }
            }
            "session.info" -> {
                val info = event.payload
                _ui.update {
                    it.copy(
                        model = info.str("model").ifBlank { it.model },
                        title = info.str("title").ifBlank { it.title },
                        running = info.bool("running") || it.running,
                    )
                }
            }
            "error" -> {
                val message = event.payload.strOrNull("message")
                    ?: event.payload.strOrNull("error")
                    ?: app.getString(R.string.hermes_reported_error)
                awaitingAutoListen = false
                autoListenJob?.cancel()
                speakThisTurn = false
                stopTts()
                _ui.update { it.copy(error = message, running = false, voiceState = "idle") }
            }
            "approval.cancelled" -> _ui.update { it.copy(approval = null) }
            "request.cancel" -> {
                val id = event.payload.strOrNull("id")
                _ui.update {
                    it.copy(
                        approval = if (it.approval?.frameId == id) null else it.approval,
                        clarify = if (it.clarify?.frameId == id) null else it.clarify,
                    )
                }
            }
            "sessions.changed" -> scheduleSessionsRefresh()
            "status.update" -> {
                val running = event.payload["running"]?.toString()?.toBooleanStrictOrNull()
                if (running != null) _ui.update { it.copy(running = running) }
            }
        }
    }

    private fun addAssistantPlaceholder() {
        turnWatchdogJob?.cancel()
        _ui.update { state ->
            val last = state.messages.lastOrNull()
            if (last?.role == MessageRole.ASSISTANT && last.streaming) return@update state
            state.copy(
                running = true,
                messages = state.messages + ChatMessage(
                    role = MessageRole.ASSISTANT,
                    streaming = true,
                ),
            )
        }
    }

    private fun appendDelta(delta: String) {
        turnWatchdogJob?.cancel()
        if (delta.isEmpty()) return
        _ui.update { state ->
            val last = state.messages.lastOrNull()
            if (last?.role == MessageRole.ASSISTANT && last.streaming) {
                state.copy(
                    running = true,
                    messages = state.messages.replace(last.id) {
                        it.copy(text = it.text + delta)
                    },
                )
            } else {
                state.copy(
                    running = true,
                    messages = state.messages + ChatMessage(
                        role = MessageRole.ASSISTANT,
                        text = delta,
                        streaming = true,
                    ),
                )
            }
        }
    }

    private fun completeAssistant(fullText: String) {
        turnWatchdogJob?.cancel()
        AppLog.add("message.complete (${fullText.length} chars)")
        _ui.update { state ->
            val index = state.messages.indexOfLast { it.role == MessageRole.ASSISTANT }
            val messages = if (index >= 0) {
                val existing = state.messages[index]
                state.messages.replace(existing.id) {
                    it.copy(
                        text = fullText.ifBlank { it.text },
                        streaming = false,
                    )
                }
            } else {
                state.messages + ChatMessage(
                    role = MessageRole.ASSISTANT,
                    text = fullText,
                    streaming = false,
                )
            }
            state.copy(messages = messages, running = false)
        }
        scheduleSessionsRefresh()
        scheduleHistoryBaseline()
    }

    private fun appendThinking(delta: String) {
        if (delta.isBlank()) return
        _ui.update { state ->
            val index = state.messages.indexOfLast { it.role == MessageRole.ASSISTANT }
            if (index < 0) return@update state
            val message = state.messages[index]
            state.copy(
                messages = state.messages.replace(message.id) {
                    it.copy(reasoning = it.reasoning + delta)
                },
            )
        }
    }

    private fun addToolStart(payload: JsonObject) {
        val toolId = payload.str("tool_id").ifBlank { UUID.randomUUID().toString() }
        val tool = ChatMessage(
            id = toolId,
            role = MessageRole.TOOL,
            toolName = payload.str("name").ifBlank { "alat" },
            toolContext = payload.strOrNull("context"),
            toolDone = false,
        )
        _ui.update { it.copy(messages = it.messages + tool) }
    }

    private fun addToolComplete(payload: JsonObject) {
        val toolId = payload.str("tool_id")
        val result = payload.obj("result")
        val output = result?.strOrNull("output")
            ?: result?.strOrNull("error")
            ?: payload["result"]?.toString()
        _ui.update { state ->
            val index = state.messages.indexOfFirst { it.id == toolId }
            if (index < 0) {
                state.copy(
                    messages = state.messages + ChatMessage(
                        role = MessageRole.TOOL,
                        toolName = payload.str("name").ifBlank {
                            app.getString(R.string.tool_default)
                        },
                        toolResult = output,
                        toolDone = true,
                    ),
                )
            } else {
                val message = state.messages[index]
                state.copy(
                    messages = state.messages.replace(message.id) {
                        it.copy(toolResult = output, toolDone = true)
                    },
                )
            }
        }
    }

    private fun startTurnWatchdog() {
        turnWatchdogJob?.cancel()
        turnWatchdogJob = scope.launch {
            delay(35_000)
            if (_ui.value.running) {
                AppLog.add("turn watchdog: no events after 35s")
                _ui.update {
                    it.copy(
                        running = false,
                        voiceState = "idle",
                        error = app.getString(R.string.no_response_35s),
                    )
                }
            }
        }
    }

    private fun scheduleSessionsRefresh() {
        sessionsRefreshJob?.cancel()
        sessionsRefreshJob = scope.launch {
            delay(700)
            loadSessions()
        }
    }

    // ── Server -> client requests ─────────────────────────────────────────

    private fun handleRequest(request: GatewayRequest) {
        AppLog.add("server request: ${request.method}")
        if (request.method == "approval" ||
            request.method == "clarify" ||
            request.method == "device.action"
        ) {
            awaitingAutoListen = false
            autoListenJob?.cancel()
        }
        when (request.method) {
            "approval" -> {
                val params = request.params
                val choices = params.arr("choices").mapNotNull {
                    runCatching { it.toString().trim('"') }.getOrNull()
                }.ifEmpty { listOf("once", "deny") }
                _ui.update {
                    it.copy(
                        approval = ApprovalPrompt(
                            frameId = request.id,
                            requestId = params.str("request_id"),
                            command = params.str("command"),
                            description = params.str("description"),
                            choices = choices,
                            allowSession = params.bool("allow_session"),
                            allowPermanent = params.bool("allow_permanent"),
                        ),
                    )
                }
            }
            "device.action" -> handleDeviceAction(request)
            "file.send" -> receiveFile(request)
            "clarify" -> {
                val questions = request.params.objects("questions").map { it.toClarifyQuestion() }
                if (questions.isNotEmpty()) {
                    _ui.update { it.copy(clarify = ClarifyPrompt(request.id, questions)) }
                } else {
                    rpc?.respond(request.id, jsonObject())
                }
            }
            else -> {
                // Desktop-only bridges and secret/sudo prompts cannot be rendered
                // here yet. Decline explicitly so the agent does not wait for a
                // reply that will never come.
                rpc?.respondError(
                    request.id,
                    4404,
                    app.getString(R.string.mobile_unsupported),
                )
                _ui.update {
                    it.copy(
                        notice = app.getString(R.string.desktop_only_action, request.method),
                    )
                }
            }
        }
    }

    /**
     * `device.action` can dial, text, set alarms, launch apps or open URLs.
     * When confirmations are on (the default), the user must approve it in the
     * app first; otherwise a prompt-injected agent could act silently. The
     * request is denied after 90 s so the server never waits forever.
     */
    private fun handleDeviceAction(request: GatewayRequest) {
        val action = request.params.str("action").lowercase()
        if (settingsStore.current.confirmDeviceActions &&
            action in confirmableDeviceActions
        ) {
            _ui.value.deviceConfirm?.let { pending ->
                deviceConfirmJob?.cancel()
                rpc?.respond(
                    pending.frameId,
                    jsonObject(
                        "ok" to false,
                        "error" to app.getString(R.string.replaced_by_new_action),
                    ),
                )
            }
            _ui.update {
                it.copy(
                    deviceConfirm = DeviceActionPrompt(
                        frameId = request.id,
                        action = action,
                        description = describeDeviceAction(request.params),
                        params = request.params,
                    ),
                )
            }
            deviceConfirmJob?.cancel()
            deviceConfirmJob = scope.launch {
                delay(90_000)
                _ui.update { state ->
                    if (state.deviceConfirm?.frameId != request.id) return@update state
                    rpc?.respond(
                        request.id,
                        jsonObject(
                            "ok" to false,
                            "error" to app.getString(R.string.user_did_not_confirm),
                        ),
                    )
                    state.copy(
                        deviceConfirm = null,
                        notice = app.getString(R.string.action_timed_out),
                    )
                }
            }
            return
        }
        executeDeviceAction(request.id, request.params)
    }

    private fun executeDeviceAction(frameId: String, params: JsonObject) {
        scope.launch {
            val result = DeviceActions.execute(app, params)
            AppLog.add("device.action -> ${result.str("ok")}")
            rpc?.respond(frameId, result)
        }
    }

    fun confirmDeviceAction(allow: Boolean) {
        val prompt = _ui.value.deviceConfirm ?: return
        deviceConfirmJob?.cancel()
        deviceConfirmJob = null
        _ui.update { it.copy(deviceConfirm = null) }
        if (!allow) {
            rpc?.respond(
                prompt.frameId,
                jsonObject("ok" to false, "error" to app.getString(R.string.user_denied_action)),
            )
            return
        }
        executeDeviceAction(prompt.frameId, prompt.params)
    }

    private fun describeDeviceAction(params: JsonObject): String {
        val action = params.str("action").lowercase()
        val number = params.str("number")
        val contact = params.str("contact_name")
        val target = when (action) {
            "call_contact", "dial_number", "send_sms" -> contact.ifBlank { number }
            "open_url" -> params.str("url")
            "set_alarm" -> params.str("time")
            "launch_app" -> params.str("app_name").ifBlank { params.str("query") }
            "play_music" -> params.str("query").ifBlank { params.str("song") }
            "media_control" -> params.str("command").ifBlank { "play_pause" }
            else -> ""
        }.trim()
        val label = when (action) {
            "call_contact", "dial_number" -> app.getString(R.string.device_action_call)
            "send_sms" -> app.getString(R.string.device_action_sms)
            "open_url" -> app.getString(R.string.device_action_open_link)
            "set_alarm" -> app.getString(R.string.device_action_alarm)
            "launch_app" -> app.getString(R.string.device_action_launch_app)
            "play_music" -> app.getString(R.string.device_action_music)
            "media_control" -> app.getString(R.string.device_action_media_control)
            else -> action
        }
        return if (target.isBlank()) {
            label
        } else {
            app.getString(R.string.device_action_target, label, target)
        }
    }

    // ── Server -> client files ─────────────────────────────────────────────

    /**
     * The agent produced a file on the server and asked the phone to take it
     * (`file.send`). The bytes come over the authenticated REST API, never the
     * socket, so multi-megabyte PDFs and photos do not stall the gateway link.
     */
    private fun receiveFile(request: GatewayRequest) {
        val params = request.params
        val serverPath = params.str("path")
        val downloadPath = params.str("download_path")
        val file = FileTransfer(
            name = params.str("name").ifBlank {
                serverPath.substringAfterLast('/').ifBlank {
                    app.getString(R.string.file)
                }
            },
            mimeType = params.str("mime").ifBlank { "application/octet-stream" },
            size = params.long("size"),
            serverPath = serverPath,
            downloadPath = downloadPath,
            caption = params.str("caption"),
        )
        val client = rest
        if (client == null || downloadPath.isBlank()) {
            rpc?.respond(
                request.id,
                jsonObject(
                    "ok" to false,
                    "error" to app.getString(R.string.server_connection_not_open),
                ),
            )
            return
        }
        val messageId = UUID.randomUUID().toString()
        _ui.update {
            it.copy(
                messages = it.messages + ChatMessage(
                    id = messageId,
                    role = MessageRole.FILE,
                    text = file.caption,
                    file = file,
                ),
                notice = app.getString(R.string.file_arriving, file.name),
            )
        }
        AppLog.add("file.send <- ${file.name} (${file.size} B)")
        startFileDownload(messageId, file, client, request.id)
    }

    /** Retry from the failed card; no live server request is waiting for this answer. */
    fun retryFile(messageId: String) {
        val message = _ui.value.messages.firstOrNull { it.id == messageId } ?: return
        val file = message.file ?: return
        val client = rest ?: return
        updateFileMessage(messageId) {
            it.copy(state = FileTransferState.DOWNLOADING, error = null)
        }
        startFileDownload(messageId, file, client, requestId = null)
    }

    private fun startFileDownload(
        messageId: String,
        file: FileTransfer,
        client: HermesRest,
        requestId: String?,
    ) {
        scope.launch {
            try {
                val url = client.fileDownloadUrl(file.downloadPath)
                    ?: throw IllegalStateException(
                        app.getString(R.string.file_outside_origin),
                    )
                val saved = FileInbox.download(
                    app,
                    client.client,
                    url,
                    IncomingFile(
                        serverPath = file.serverPath,
                        downloadPath = file.downloadPath,
                        name = file.name,
                        mimeType = file.mimeType,
                        size = file.size,
                        caption = file.caption,
                    ),
                )
                updateFileMessage(messageId) {
                    it.copy(
                        name = saved.name,
                        size = saved.size,
                        state = FileTransferState.READY,
                        localUri = saved.uri.toString(),
                        location = saved.location,
                        error = null,
                    )
                }
                FileNotifier.notify(app, saved)
                AppLog.add("file.send -> ${saved.location}")
                if (requestId != null) {
                    rpc?.respond(
                        requestId,
                        jsonObject(
                            "ok" to true,
                            "name" to saved.name,
                            "size" to saved.size,
                            "saved_to" to saved.location,
                            "mime" to file.mimeType,
                        ),
                    )
                }
            } catch (error: Exception) {
                val message = humanError(error)
                AppLog.add("file.send failed: $message")
                updateFileMessage(messageId) {
                    it.copy(state = FileTransferState.FAILED, error = message)
                }
                if (requestId != null) {
                    rpc?.respond(requestId, jsonObject("ok" to false, "error" to message))
                } else {
                    _ui.update {
                        it.copy(error = app.getString(R.string.file_not_downloaded, message))
                    }
                }
            }
        }
    }

    private fun updateFileMessage(messageId: String, transform: (FileTransfer) -> FileTransfer) {
        _ui.update { state ->
            val message = state.messages.firstOrNull { it.id == messageId } ?: return@update state
            val file = message.file ?: return@update state
            state.copy(
                messages = state.messages.replace(messageId) { it.copy(file = transform(file)) },
            )
        }
    }

    fun approve(choice: String) {
        val prompt = _ui.value.approval ?: return
        rpc?.respond(prompt.frameId, jsonObject("choice" to choice))
        _ui.update { it.copy(approval = null) }
    }

    fun answerClarify(answers: Map<String, String?>) {
        val prompt = _ui.value.clarify ?: return
        val payload = buildJsonObject {
            val map = buildJsonObject {
                answers.forEach { (key, value) ->
                    put(key, if (value.isNullOrBlank()) JsonNull else JsonPrimitive(value))
                }
            }
            put("answers", map)
        }
        rpc?.respond(prompt.frameId, payload)
        _ui.update { it.copy(clarify = null) }
    }

    fun dismissRequest() {
        val approval = _ui.value.approval
        if (approval != null) approve("deny")
        val clarify = _ui.value.clarify
        if (clarify != null) {
            rpc?.respond(clarify.frameId, jsonObject())
            _ui.update { it.copy(clarify = null) }
        }
    }

    // ── Settings / onboarding ─────────────────────────────────────────────

    fun openSettings() = _ui.update { it.copy(showSettings = true) }

    fun closeSettings() = _ui.update { it.copy(showSettings = false) }

    fun openOnboarding() = _ui.update { it.copy(showOnboarding = true, showSettings = false) }

    fun clearError() = _ui.update { it.copy(error = null, notice = null) }

    fun voiceComingSoon() = _ui.update {
        it.copy(notice = app.getString(R.string.voice_mode_coming_soon))
    }

    fun enableHandsFree() {
        val updated = settingsStore.current.copy(
            handsFree = true,
            wakeWordEnabled = true,
            hasCompletedOnboarding = true,
        )
        applyVoiceSettings(updated)
        _ui.update {
            it.copy(notice = app.getString(R.string.hands_free_enabled))
        }
    }

    fun startVoiceService() {
        HermesService.start(app)
        _ui.update { it.copy(notice = app.getString(R.string.voice_service_started)) }
    }

    /** Save credentials + apply the Hands-free toggle without leaving Settings. */
    fun applyVoiceSettings(settings: AppSettings) {
        val completed = settings.copy(hasCompletedOnboarding = true)
        settingsStore.save(completed)
        androidTts.selectVoice(completed.androidTtsVoice)
        _ui.update { it.copy(settings = completed, configured = completed.isConfigured) }
        HermesService.applySettings(app, completed)
    }

    /** Record a short sample, send it to Hermes STT and show the transcript in diagnostics. */
    fun testMicrophone() {
        val client = rest
        if (client == null || rpc?.isReady() != true) {
            _ui.update { it.copy(error = app.getString(R.string.not_connected_test_settings)) }
            return
        }
        voiceJob?.cancel()
        voiceJob = scope.launch {
            val wasHandsFree = _ui.value.settings.handsFree
            try {
                HermesService.stop(app)
                delay(400)
                VoiceDiagnostics.update {
                    it.copy(lastMicTest = app.getString(R.string.recording_6s), lastError = null)
                }
                _ui.update { it.copy(voiceState = "listening", error = null) }
                val wav = recorder.recordFixed(6_000)
                val peak = wavPeak(wav)
                if (peak < 300) {
                    VoiceDiagnostics.update {
                        it.copy(lastMicTest = app.getString(R.string.mic_no_signal_peak, peak))
                    }
                    _ui.update {
                        it.copy(
                            voiceState = "idle",
                            notice = app.getString(R.string.mic_no_signal),
                        )
                    }
                    return@launch
                }
                _ui.update { it.copy(voiceState = "transcribing") }
                val transcript = client.transcribe(wav).trim()
                VoiceDiagnostics.update {
                    it.copy(
                        lastMicTest = "peak=$peak · " + transcript.ifBlank {
                            app.getString(R.string.nothing_recognized)
                        },
                    )
                }
                _ui.update {
                    it.copy(
                        voiceState = "idle",
                        notice = if (transcript.isBlank()) {
                            app.getString(R.string.mic_works_no_stt)
                        } else {
                            app.getString(R.string.stt_test_result, transcript)
                        },
                    )
                }
            } catch (error: Exception) {
                VoiceDiagnostics.update {
                    it.copy(
                        lastMicTest = app.getString(R.string.error),
                        lastError = humanError(error),
                    )
                }
                _ui.update { it.copy(voiceState = "idle", error = humanError(error)) }
            } finally {
                if (wasHandsFree) HermesService.start(app)
            }
        }
    }

    /** Record one sample and run the exact on-device KWS model over it. */
    fun testWakeWord() {
        voiceJob?.cancel()
        voiceJob = scope.launch {
            val wasHandsFree = _ui.value.settings.handsFree
            try {
                HermesService.stop(app)
                delay(400)
                VoiceDiagnostics.update {
                    it.copy(lastDetection = app.getString(R.string.listening_6s), lastError = null)
                }
                _ui.update { it.copy(voiceState = "listening", error = null) }
                val wav = recorder.recordFixed(6_000)
                val peak = wavPeak(wav)
                _ui.update { it.copy(voiceState = "thinking") }
                if (peak < 300) {
                    val message = app.getString(R.string.mic_no_signal_peak_short, peak)
                    VoiceDiagnostics.update {
                        it.copy(lastDetection = message)
                    }
                    _ui.update {
                        it.copy(voiceState = "idle", notice = message)
                    }
                    return@launch
                }
                val pcm = wavToPcm(wav)
                val detector = WakeWordDetector(app)
                val initialized = detector.initialize()
                val keyword = if (initialized) detector.detectPcm(pcm) else null
                detector.release()
                VoiceDiagnostics.update {
                    it.copy(
                        lastDetection = keyword ?: if (initialized) {
                            app.getString(R.string.wake_not_recognized_try_english)
                        } else {
                            app.getString(R.string.kws_model_not_loaded)
                        },
                    )
                }
                _ui.update {
                    it.copy(
                        voiceState = "idle",
                        notice = if (keyword != null) {
                            app.getString(R.string.wake_word_recognized, keyword)
                        } else {
                            app.getString(R.string.wake_word_not_recognized)
                        },
                    )
                }
            } catch (error: Exception) {
                VoiceDiagnostics.update { it.copy(lastError = humanError(error)) }
                _ui.update { it.copy(voiceState = "idle", error = humanError(error)) }
            } finally {
                if (wasHandsFree) HermesService.start(app)
            }
        }
    }

    private fun wavPeak(wav: ByteArray): Int {
        val offset = if (wav.size > 44) 44 else 0
        var peak = 0
        var i = offset
        while (i + 1 < wav.size) {
            val value = ((wav[i].toInt() and 0xFF) or (wav[i + 1].toInt() shl 8)).toShort().toInt()
            peak = maxOf(peak, kotlin.math.abs(value))
            i += 2
        }
        return peak
    }

    private fun wavToPcm(wav: ByteArray): ShortArray {
        val offset = if (wav.size > 44) 44 else 0
        val count = (wav.size - offset) / 2
        val shorts = ShortArray(count)
        ByteBuffer.wrap(wav, offset, count * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(shorts)
        return shorts
    }

    // ── App update ─────────────────────────────────────────────────────────

    fun checkForUpdates() {
        if (_ui.value.update.checking || _ui.value.update.downloading) return
        _ui.update {
            it.copy(
                update = it.update.copy(
                    checking = true,
                    error = null,
                    upToDate = false,
                    available = null,
                ),
            )
        }
        scope.launch {
            try {
                val info = Updater.check(app, BuildConfig.VERSION_CODE)
                _ui.update {
                    it.copy(
                        update = it.update.copy(
                            checking = false,
                            available = info,
                            upToDate = info == null,
                            error = null,
                        ),
                    )
                }
            } catch (error: Exception) {
                AppLog.add("update check failed: ${error.message}")
                _ui.update {
                    it.copy(
                        update = it.update.copy(
                            checking = false,
                            error = error.message
                                ?: app.getString(R.string.update_check_failed),
                        ),
                    )
                }
            }
        }
    }

    fun installUpdate() {
        val info = _ui.value.update.available ?: return
        if (_ui.value.update.downloading) return
        if (!app.packageManager.canRequestPackageInstalls()) {
            _ui.update {
                it.copy(notice = app.getString(R.string.allow_install_from_hermes))
            }
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${app.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { app.startActivity(intent) }.onFailure {
                runCatching {
                    app.startActivity(
                        Intent(Settings.ACTION_SECURITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            return
        }
        _ui.update {
            it.copy(
                update = it.update.copy(
                    downloading = true,
                    progress = 0f,
                    error = null,
                ),
            )
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    Updater.downloadAndInstall(app, info) { progress ->
                        _ui.update { it.copy(update = it.update.copy(progress = progress)) }
                    }
                }
                _ui.update {
                    it.copy(
                        update = it.update.copy(
                            downloading = false,
                            progress = null,
                            available = null,
                            upToDate = false,
                        ),
                        notice = app.getString(R.string.installer_opened),
                    )
                }
            } catch (error: Exception) {
                AppLog.add("update download failed: ${error.message}")
                _ui.update {
                    it.copy(
                        update = it.update.copy(
                            downloading = false,
                            progress = null,
                            error = error.message
                                ?: app.getString(R.string.update_download_failed),
                        ),
                    )
                }
            }
        }
    }

    fun saveSettings(settings: AppSettings) {
        val completed = settings.copy(hasCompletedOnboarding = true)
        settingsStore.save(completed)
        androidTts.selectVoice(completed.androidTtsVoice)
        HermesService.applySettings(app, completed)
        _ui.update {
            it.copy(
                settings = completed,
                showOnboarding = false,
                showSettings = false,
                onboardingResult = null,
                configured = completed.isConfigured,
            )
        }
        connect()
    }

    fun updateLocalSettings(settings: AppSettings) {
        _ui.update { it.copy(settings = settings) }
    }

    fun testConnection(settings: AppSettings) {
        scope.launch {
            _ui.update { it.copy(onboardingBusy = true, onboardingResult = null) }
            val result = runCatching {
                val probe = HermesRest(app, settings)
                probe.ensureAuthenticated()
                val me = probe.me()
                val ticket = probe.wsTicket()
                handshake(probe, ticket)
                app.getString(
                    R.string.connected_as,
                    me.str("user_id").ifBlank { settings.username },
                )
            }
            _ui.update {
                it.copy(
                    onboardingBusy = false,
                    onboardingResult = result.getOrElse { error -> humanError(error) },
                    onboardingOk = result.isSuccess,
                )
            }
        }
    }

    private suspend fun handshake(rest: HermesRest, ticket: String) = withTimeout(25_000) {
        val ready = CompletableDeferred<Unit>()
        var socket: WebSocket? = null
        socket = rest.client.newWebSocket(
            Request.Builder().url(rest.webSocketUrl(ticket)).build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val type = runCatching {
                        HermesJson.parseToJsonElement(text).jsonObject
                            .obj("params")?.str("type")
                    }.getOrNull()
                    if (type == "gateway.ready") ready.complete(Unit)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    ready.completeExceptionally(t)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!ready.isCompleted) {
                        ready.completeExceptionally(
                            IllegalStateException(
                                reason.ifBlank { app.getString(R.string.ws_closed) },
                            ),
                        )
                    }
                }
            },
        )
        try {
            ready.await()
        } finally {
            socket.close(1000, "test done")
        }
    }

    fun logout() {
        val client = rest
        stopRpc()
        scope.launch {
            runCatching { client?.clearSession() }
            settingsStore.clearCredentials()
            sessionsLoaded = false
            wasReady = false
            _ui.value = HermesUiState(
                settings = settingsStore.current,
                configured = false,
                showOnboarding = true,
                showSettings = false,
                account = settingsStore.current.username,
            )
        }
    }

    private fun humanError(error: Throwable): String {
        val raw = error.message ?: error::class.java.simpleName
        return when {
            raw.contains("active turn lease", ignoreCase = true) ||
                raw.contains("turn lease", ignoreCase = true) ->
                app.getString(R.string.telegram_busy)
            error is ApiError -> error.message ?: app.getString(R.string.server_error)
            else -> raw
        }
    }

    private companion object {
        const val MAX_ATTACHMENT_BYTES = 8 * 1024 * 1024
    }

    fun shutdown() {
        androidStt.destroy()
        recorder.cancel()
        tts.stop()
        androidTts.shutdown()
        stopRpc()
        scope.cancel()
    }
}

private fun List<ChatMessage>.replace(id: String, transform: (ChatMessage) -> ChatMessage): List<ChatMessage> =
    map { if (it.id == id) transform(it) else it }
