package hr.sting.hermes.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.UUID

enum class MessageRole { USER, ASSISTANT, TOOL, SYSTEM, FILE }

enum class FileTransferState { DOWNLOADING, READY, FAILED }

/**
 * A file Hermes pushed to the phone (`file.send` server request). The bytes are
 * fetched over the authenticated HTTP API and stored in Downloads; [localUri]
 * is the content:// or FileProvider:// URI the card opens and shares.
 */
data class FileTransfer(
    val name: String,
    val mimeType: String,
    val size: Long,
    val serverPath: String,
    val downloadPath: String,
    val caption: String = "",
    val state: FileTransferState = FileTransferState.DOWNLOADING,
    val localUri: String? = null,
    val location: String? = null,
    val error: String? = null,
)

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val text: String = "",
    val reasoning: String = "",
    val toolName: String? = null,
    val toolContext: String? = null,
    val toolResult: String? = null,
    val toolDone: Boolean = false,
    val streaming: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val rowId: Long? = null,
    val file: FileTransfer? = null,
)

enum class AttachmentKind { IMAGE, PDF, FILE }

data class PendingAttachment(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val mimeType: String,
    val bytes: ByteArray,
    val kind: AttachmentKind,
)

data class SessionRow(
    val id: String,
    val title: String,
    val preview: String,
    val startedAt: Double,
    val messageCount: Int,
    val source: String,
)

data class ClarifyQuestion(
    val qid: String,
    val question: String,
    val choices: List<String>,
    val multiSelect: Boolean,
)

data class ApprovalPrompt(
    val frameId: String,
    val requestId: String,
    val command: String,
    val description: String,
    val choices: List<String>,
    val allowSession: Boolean,
    val allowPermanent: Boolean,
)

data class ClarifyPrompt(
    val frameId: String,
    val questions: List<ClarifyQuestion>,
)

/**
 * A device action the agent asked for (call, SMS, alarm, URL, app launch…).
 * It is only executed after the user taps "Dopusti" unless confirmations are
 * disabled in Settings.
 */
data class DeviceActionPrompt(
    val frameId: String,
    val action: String,
    val description: String,
    val params: JsonObject,
)

data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val apkUrl: String,
    val changelog: List<String> = emptyList(),
    val sha256: String? = null,
)

data class UpdateUiState(
    val checking: Boolean = false,
    val available: UpdateInfo? = null,
    val downloading: Boolean = false,
    val progress: Float? = null,
    val upToDate: Boolean = false,
    val error: String? = null,
)

data class TtsVoiceOption(
    val id: String,
    val label: String,
    val network: Boolean,
)

data class HermesUiState(
    val settings: AppSettings = AppSettings(),
    val configured: Boolean = false,
    val showOnboarding: Boolean = true,
    val showSettings: Boolean = false,
    val link: LinkState = LinkState.Idle,
    val account: String = "",
    val sessions: List<SessionRow> = emptyList(),
    val currentRuntimeId: String? = null,
    val currentStoredId: String? = null,
    val title: String = "",
    val model: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val running: Boolean = false,
    val approval: ApprovalPrompt? = null,
    val clarify: ClarifyPrompt? = null,
    val deviceConfirm: DeviceActionPrompt? = null,
    val error: String? = null,
    val notice: String? = null,
    val onboardingBusy: Boolean = false,
    val onboardingResult: String? = null,
    val onboardingOk: Boolean = false,
    val voiceState: String = "idle",
    /** True while a start-stop capture is recording; the next tap finishes it. */
    val manualCapture: Boolean = false,
    val lastTurnFromVoice: Boolean = false,
    val partialTranscript: String = "",
    val pendingAttachments: List<PendingAttachment> = emptyList(),
    val androidTtsVoices: List<TtsVoiceOption> = emptyList(),
    val update: UpdateUiState = UpdateUiState(),
) {
    val connected: Boolean get() = link is LinkState.Ready
}

fun JsonObject.toSessionRow(): SessionRow = SessionRow(
    id = str("id"),
    title = str("title").ifBlank { "Bez naslova" },
    preview = str("preview"),
    startedAt = double("started_at"),
    messageCount = int("message_count"),
    source = str("source"),
)

fun JsonObject.toClarifyQuestion(): ClarifyQuestion = ClarifyQuestion(
    qid = str("qid"),
    question = str("question"),
    choices = (this["choices"] as? JsonArray)?.mapNotNull {
        runCatching { it.toString().trim('"') }.getOrNull()
    } ?: emptyList(),
    multiSelect = bool("multi_select"),
)

fun JsonObject.toChatMessage(): ChatMessage? {
    if (strOrNull("display_kind") == "hidden") return null
    val role = when (str("role")) {
        "user" -> MessageRole.USER
        "assistant" -> MessageRole.ASSISTANT
        "tool" -> MessageRole.TOOL
        else -> MessageRole.SYSTEM
    }
    val text = str("text")
    val reasoning = strOrNull("reasoning") ?: strOrNull("reasoning_content") ?: ""
    val rawTimestamp = double("timestamp")
    val timestamp = if (rawTimestamp > 0) (rawTimestamp * 1000.0).toLong() else System.currentTimeMillis()
    val rowId = this["row_id"]?.let {
        runCatching { it.jsonPrimitive.longOrNull }.getOrNull()
    }
    if (role == MessageRole.TOOL) {
        val content = this["content"]?.toString()?.takeIf { it != "null" && it.isNotBlank() }
        return ChatMessage(
            role = MessageRole.TOOL,
            toolName = strOrNull("name") ?: "alat",
            toolContext = strOrNull("context"),
            toolResult = content,
            toolDone = true,
            timestamp = timestamp,
            rowId = rowId,
        )
    }
    if (role == MessageRole.SYSTEM && text.isBlank()) return null
    if (text.isBlank() && reasoning.isBlank()) return null
    return ChatMessage(
        role = role,
        text = text,
        reasoning = reasoning,
        timestamp = timestamp,
        rowId = rowId,
    )
}

