package hr.sting.hermes.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import hr.sting.hermes.R
import hr.sting.hermes.data.ApprovalPrompt
import hr.sting.hermes.data.ChatMessage
import hr.sting.hermes.data.ClarifyPrompt
import hr.sting.hermes.data.DeviceActionPrompt
import hr.sting.hermes.data.FileTransfer
import hr.sting.hermes.data.FileTransferState
import hr.sting.hermes.data.MessageRole
import hr.sting.hermes.data.SessionRow
import java.util.Date
import java.util.Locale

@Composable
fun MessageBubble(message: ChatMessage, onFileRetry: (String) -> Unit = {}) {
    when (message.role) {
        MessageRole.USER -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                modifier = Modifier.fillMaxWidth(0.86f),
            ) {
                SelectionContainer {
                    Text(
                        text = message.text,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }

        MessageRole.ASSISTANT -> Row(modifier = Modifier.fillMaxWidth()) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
                modifier = Modifier.fillMaxWidth(0.94f),
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    SelectionContainer {
                        Column {
                            if (message.reasoning.isNotBlank()) {
                                ReasoningBlock(message.reasoning)
                                Spacer(Modifier.height(6.dp))
                            }
                            if (message.text.isBlank() && message.streaming) {
                                TypingDots()
                            } else {
                                MarkdownLite(message.text)
                            }
                        }
                    }
                }
            }
        }

        MessageRole.TOOL -> ToolCard(message)

        MessageRole.FILE -> FileCard(message, onFileRetry)

        MessageRole.SYSTEM -> SelectionContainer {
            Text(
                text = message.text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun FileCard(message: ChatMessage, onRetry: (String) -> Unit) {
    val file = message.file ?: return
    val context = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.InsertDriveFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = fileSubtitle(file),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (file.state == FileTransferState.DOWNLOADING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
            if (file.caption.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(text = file.caption, style = MaterialTheme.typography.bodyMedium)
            }
            if (file.state == FileTransferState.FAILED && !file.error.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = file.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            when (file.state) {
                FileTransferState.READY -> Row(modifier = Modifier.padding(top = 4.dp)) {
                    TextButton(onClick = { openSavedFile(context, file) }) {
                        Icon(
                            Icons.Filled.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.open))
                    }
                    TextButton(onClick = { shareSavedFile(context, file) }) {
                        Icon(
                            Icons.Filled.Share,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.share))
                    }
                }

                FileTransferState.FAILED -> Row(modifier = Modifier.padding(top = 4.dp)) {
                    TextButton(onClick = { onRetry(message.id) }) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.retry))
                    }
                }

                FileTransferState.DOWNLOADING -> Unit
            }
        }
    }
}

@Composable
private fun fileSubtitle(file: FileTransfer): String {
    val size = if (file.size > 0) formatFileSize(file.size) else ""
    return when (file.state) {
        FileTransferState.DOWNLOADING -> listOf(
            stringResource(R.string.downloading_from_hermes),
            size,
        ).filter { it.isNotBlank() }.joinToString(" · ")

        FileTransferState.READY -> listOf(size, file.location.orEmpty())
            .filter { it.isNotBlank() }.joinToString(" · ")

        FileTransferState.FAILED -> stringResource(R.string.download_failed)
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

private fun openSavedFile(context: Context, file: FileTransfer) {
    val uri = file.localUri?.let(Uri::parse) ?: return
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, file.mimeType.ifBlank { "*/*" })
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
        .onFailure {
            Toast.makeText(context, context.getString(R.string.no_app_to_open), Toast.LENGTH_SHORT)
                .show()
        }
}

private fun shareSavedFile(context: Context, file: FileTransfer) {
    val uri = file.localUri?.let(Uri::parse) ?: return
    val send = Intent(Intent.ACTION_SEND).apply {
        type = file.mimeType.ifBlank { "*/*" }
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(
            Intent.createChooser(send, context.getString(R.string.share_file))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure {
        Toast.makeText(context, context.getString(R.string.sharing_unavailable), Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun MarkdownLite(text: String) {
    val chunks = text.split("```")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        chunks.forEachIndexed { index, chunk ->
            if (chunk.isBlank()) return@forEachIndexed
            if (index % 2 == 1) {
                Surface(
                    color = Color(0xFF090D11),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        text = chunk.trim('\n'),
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            } else {
                Text(
                    text = chunk.trim(),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun ReasoningBlock(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Row(
            modifier = Modifier.clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.reasoning),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun TypingDots() {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { index ->
            Box(
                modifier = Modifier
                    .size(if (index == 1) 8.dp else 6.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f + index * 0.2f),
                        shape = CircleShape,
                    ),
            )
        }
    }
}

@Composable
private fun ToolCard(message: ChatMessage) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (message.toolDone) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                } else {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = message.toolName ?: stringResource(R.string.tool_fallback),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            message.toolContext?.takeIf { it.isNotBlank() }?.let { context ->
                Text(
                    text = context,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = if (expanded) 12 else 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (expanded) {
                message.toolResult?.takeIf { it.isNotBlank() }?.let { result ->
                    Text(
                        text = result.take(4000),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun ApprovalCard(prompt: ApprovalPrompt, onChoice: (String) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.approval_required),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (prompt.description.isNotBlank()) {
                Text(
                    prompt.description,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (prompt.command.isNotBlank()) {
                Surface(
                    color = Color(0xFF090D11),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    Text(
                        prompt.command,
                        modifier = Modifier.padding(10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 10.dp),
            ) {
                val allowed = prompt.choices.ifEmpty { listOf("once", "deny") }
                if ("once" in allowed) {
                    Button(onClick = { onChoice("once") }) {
                        Text(stringResource(R.string.allow_once))
                    }
                }
                if ("session" in allowed && prompt.allowSession) {
                    TextButton(onClick = { onChoice("session") }) {
                        Text(stringResource(R.string.allow_session))
                    }
                }
                if ("always" in allowed && prompt.allowPermanent) {
                    TextButton(onClick = { onChoice("always") }) {
                        Text(stringResource(R.string.allow_always))
                    }
                }
                TextButton(onClick = { onChoice("deny") }) {
                    Text(stringResource(R.string.deny), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
fun DeviceActionCard(
    prompt: DeviceActionPrompt,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.device_action_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                prompt.description.ifBlank { prompt.action },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                stringResource(R.string.device_action_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 10.dp),
            ) {
                Button(onClick = onAllow) { Text(stringResource(R.string.allow)) }
                TextButton(onClick = onDeny) {
                    Text(stringResource(R.string.deny), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
fun ClarifyDialog(prompt: ClarifyPrompt, onSubmit: (Map<String, String?>) -> Unit, onDismiss: () -> Unit) {
    var answers by remember(prompt.frameId) { mutableStateOf<Map<String, String?>>(emptyMap()) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.clarify_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(10.dp))
                prompt.questions.forEach { question ->
                    Text(question.question, style = MaterialTheme.typography.bodyMedium)
                    if (question.choices.isNotEmpty()) {
                        Column(modifier = Modifier.padding(top = 6.dp, bottom = 8.dp)) {
                            question.choices.forEach { choice ->
                                val selected = answers[question.qid] == choice
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        answers = answers + (question.qid to if (selected) null else choice)
                                    },
                                    label = { Text(choice) },
                                    modifier = Modifier.padding(end = 6.dp, bottom = 4.dp),
                                )
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = answers[question.qid].orEmpty(),
                            onValueChange = { answers = answers + (question.qid to it) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp, bottom = 8.dp),
                            minLines = 1,
                            maxLines = 4,
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Spacer(Modifier.width(6.dp))
                    Button(onClick = { onSubmit(answers) }) { Text(stringResource(R.string.send)) }
                }
            }
        }
    }
}

@Composable
fun SessionRowItem(
    session: SessionRow,
    current: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    Surface(
        color = if (current) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                session.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (session.preview.isNotBlank()) {
                Text(
                    session.preview,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val messages = pluralStringResource(
                R.plurals.message_count,
                session.messageCount,
                session.messageCount,
            )
            val telegram = if (session.source.equals("telegram", ignoreCase = true)) {
                " · " + stringResource(R.string.telegram_label)
            } else {
                ""
            }
            val job = when {
                !session.isJob -> ""
                session.running -> " · " + stringResource(R.string.job_running)
                else -> " · " + stringResource(R.string.job_done)
            }
            Text(
                relativeTime(context, session.startedAt.toLong()) + " · " + messages + telegram + job,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

private fun relativeTime(context: Context, epochSeconds: Long): String {
    if (epochSeconds <= 0) return ""
    val now = System.currentTimeMillis() / 1000
    val diff = (now - epochSeconds).coerceAtLeast(0)
    return when {
        diff < 60 -> context.getString(R.string.time_now)
        diff < 3600 -> "${diff / 60} min"
        diff < 86_400 -> "${diff / 3600} h"
        diff < 7 * 86_400 -> "${diff / 86_400} d"
        else -> java.text.DateFormat.getDateInstance(
            java.text.DateFormat.MEDIUM,
            Locale.getDefault(),
        ).format(Date(epochSeconds * 1000))
    }
}
