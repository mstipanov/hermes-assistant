package hr.sting.hermes.ui

import android.content.res.Configuration
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hr.sting.hermes.R
import hr.sting.hermes.data.ChatMessage
import hr.sting.hermes.data.HermesUiState
import hr.sting.hermes.data.LinkState
import hr.sting.hermes.data.MessageRole
import hr.sting.hermes.voice.SpeechText

/**
 * Voice-first surface: one big orb, the live status and the latest reply.
 * No keyboard and no transcript — the chat screen is one tap away.
 *
 * The microphone mode (automatic / start-stop) can be switched right here, so it
 * is available while driving or from MyCar, in portrait and in landscape.
 */
@Composable
fun AudioOnlyScreen(
    state: HermesUiState,
    onOrb: () -> Unit,
    onStopListening: () -> Unit,
    onStop: () -> Unit,
    onSpeakLast: (String) -> Unit,
    onOpenChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onConnect: () -> Unit,
    onFinishVoice: () -> Unit,
    onMicModeChange: (Boolean) -> Unit,
    onApprove: (String) -> Unit,
    onConfirmDeviceAction: (Boolean) -> Unit,
    onClearMessage: () -> Unit,
) {
    val voiceState = state.voiceState
    val active = voiceState != "idle" || state.running
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val manualMode = state.settings.micMode == "manual"
    val infinite = rememberInfiniteTransition(label = "orb")
    val pulse by infinite.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.07f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (voiceState == "speaking") 520 else 1100),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    val lastReply = state.messages.lastOrNull {
        it.role == MessageRole.ASSISTANT && it.text.isNotBlank()
    }
    val (status, hint) = when {
        state.link !is LinkState.Ready -> stringResource(R.string.status_not_connected) to
            stringResource(R.string.hint_check_settings)

        voiceState == "listening" && state.manualCapture ->
            stringResource(R.string.status_recording) to
                stringResource(R.string.hint_tap_to_finish)

        voiceState == "listening" -> stringResource(R.string.status_listening) to
            stringResource(R.string.hint_tap_to_stop)

        voiceState == "transcribing" -> stringResource(R.string.status_transcribing) to ""
        voiceState == "thinking" || state.running -> stringResource(R.string.status_thinking) to ""
        voiceState == "speaking" -> stringResource(R.string.status_speaking) to
            stringResource(R.string.hint_tap_to_interrupt)

        else -> stringResource(R.string.status_ready) to
            stringResource(R.string.hint_tap_and_speak)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF070A0D), Color(0xFF0D1318), Color(0xFF08110F)),
                ),
            ),
    ) {
        CompositionLocalProvider(LocalContentColor provides Color(0xFFE8EDF2)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(
                        horizontal = if (landscape) 18.dp else 22.dp,
                        vertical = if (landscape) 6.dp else 14.dp,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ScreenHeader(state, onOpenChat, onOpenSettings)

                if (landscape) {
                    BoxWithConstraints(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        // Orb in the middle of the screen, as large as the height allows.
                        // Status, mode switch and buttons sit to its left, the last answer
                        // to its right, so nothing is pushed into one corner.
                        val orbSize = (maxHeight * 0.86f).coerceIn(96.dp, 238.dp)
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .verticalScroll(rememberScrollState()),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                StatusBlock(status, hint, compact = true)
                                ModeToggle(manualMode, onMicModeChange)
                                PartialTranscript(state, voiceState, maxLines = 1)
                                ReplayLast(lastReply, state.lastTurnFromVoice, voiceState, onSpeakLast)
                                VoiceActions(
                                    state = state,
                                    landscape = true,
                                    onFinishVoice = onFinishVoice,
                                    onStopListening = onStopListening,
                                    onStop = onStop,
                                )
                                BottomNotices(
                                    state = state,
                                    onApprove = onApprove,
                                    onConfirmDeviceAction = onConfirmDeviceAction,
                                    onOpenChat = onOpenChat,
                                    onConnect = onConnect,
                                    onClearMessage = onClearMessage,
                                )
                            }
                            Column(
                                modifier = Modifier
                                    .weight(1.15f)
                                    .fillMaxHeight(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Orb(
                                    active = active,
                                    pulse = pulse,
                                    voiceState = voiceState,
                                    running = state.running,
                                    manual = state.manualCapture,
                                    size = orbSize,
                                    onOrb = onOrb,
                                )
                            }
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                Spacer(Modifier.height(8.dp))
                                LastReplyCard(
                                    reply = lastReply,
                                    fromVoice = state.lastTurnFromVoice,
                                    maxLines = 4,
                                    onSpeakLast = onSpeakLast,
                                    onOpenChat = onOpenChat,
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                } else {
                    Spacer(Modifier.weight(0.7f))
                    Orb(
                        active = active,
                        pulse = pulse,
                        voiceState = voiceState,
                        running = state.running,
                        manual = state.manualCapture,
                        size = 238.dp,
                        onOrb = onOrb,
                    )
                    Spacer(Modifier.height(18.dp))
                    StatusBlock(status, hint, compact = false)
                    PartialTranscript(state, voiceState, maxLines = 2)
                    ReplayLast(lastReply, state.lastTurnFromVoice, voiceState, onSpeakLast)
                    ModeToggle(manualMode, onMicModeChange)
                    VoiceActions(
                        state = state,
                        landscape = false,
                        onFinishVoice = onFinishVoice,
                        onStopListening = onStopListening,
                        onStop = onStop,
                    )
                    Spacer(Modifier.weight(1f))
                    LastReplyCard(
                        reply = lastReply,
                        fromVoice = state.lastTurnFromVoice,
                        maxLines = 6,
                        onSpeakLast = onSpeakLast,
                        onOpenChat = onOpenChat,
                    )
                }

                if (!landscape) {
                    BottomNotices(
                        state = state,
                        onApprove = onApprove,
                        onConfirmDeviceAction = onConfirmDeviceAction,
                        onOpenChat = onOpenChat,
                        onConnect = onConnect,
                        onClearMessage = onClearMessage,
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun ScreenHeader(
    state: HermesUiState,
    onOpenChat: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "H",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Spacer(Modifier.size(10.dp))
            Column {
                Text(
                    "Hermes",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    when (state.link) {
                        is LinkState.Ready -> stringResource(R.string.status_connected)
                        is LinkState.Connecting -> stringResource(R.string.status_connecting)
                        else -> stringResource(R.string.status_offline)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onOpenChat) {
            Icon(Icons.Filled.Chat, contentDescription = stringResource(R.string.cd_chat))
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.cd_settings))
        }
    }
}

@Composable
private fun Orb(
    active: Boolean,
    pulse: Float,
    voiceState: String,
    running: Boolean,
    manual: Boolean,
    size: Dp,
    onOrb: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .scale(if (active) pulse else 1f)
            .background(
                Brush.radialGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = if (active) 0.55f else 0.28f),
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.06f),
                        Color.Transparent,
                    ),
                ),
                CircleShape,
            )
            .clickable { onOrb() },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size * 0.7f)
                .background(
                    Brush.radialGradient(
                        listOf(Color(0xFF173B33), Color(0xFF0A1714)),
                    ),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                when {
                    voiceState == "speaking" -> Icons.Filled.GraphicEq
                    manual && voiceState == "listening" -> Icons.Filled.Send
                    voiceState == "listening" || voiceState == "transcribing" ||
                        running || voiceState == "thinking" -> Icons.Filled.Stop
                    else -> Icons.Filled.Mic
                },
                contentDescription = null,
                modifier = Modifier.size(size * 0.27f),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun StatusBlock(status: String, hint: String, compact: Boolean) {
    Text(
        status,
        style = if (compact) {
            MaterialTheme.typography.titleLarge
        } else {
            MaterialTheme.typography.headlineSmall
        },
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
    )
    if (hint.isNotBlank()) {
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** The microphone mode, reachable without opening Settings. */
@Composable
private fun ModeToggle(manual: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 10.dp),
    ) {
        FilterChip(
            selected = !manual,
            onClick = { onChange(false) },
            label = { Text(stringResource(R.string.capture_auto)) },
            colors = modeChipColors(),
        )
        FilterChip(
            selected = manual,
            onClick = { onChange(true) },
            label = { Text(stringResource(R.string.capture_manual)) },
            colors = modeChipColors(),
        )
    }
}

@Composable
private fun modeChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
    selectedLabelColor = MaterialTheme.colorScheme.primary,
    selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
)

@Composable
private fun PartialTranscript(state: HermesUiState, voiceState: String, maxLines: Int) {
    if (state.partialTranscript.isBlank()) return
    if (voiceState != "listening" && voiceState != "transcribing") return
    Text(
        state.partialTranscript,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 10.dp, start = 12.dp, end = 12.dp),
    )
}

@Composable
private fun ReplayLast(
    reply: ChatMessage?,
    fromVoice: Boolean,
    voiceState: String,
    onSpeakLast: (String) -> Unit,
) {
    if (reply == null || !fromVoice) return
    if (voiceState == "listening" || voiceState == "transcribing") return
    TextButton(onClick = { onSpeakLast(reply.text) }) {
        Icon(
            Icons.Filled.VolumeUp,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(6.dp))
        Text(stringResource(R.string.replay_answer))
    }
}

@Composable
private fun VoiceActions(
    state: HermesUiState,
    landscape: Boolean,
    onFinishVoice: () -> Unit,
    onStopListening: () -> Unit,
    onStop: () -> Unit,
) {
    val voiceState = state.voiceState
    if (state.manualCapture) {
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(if (landscape) 0.98f else 0.92f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(onClick = onFinishVoice, modifier = Modifier.weight(1f)) {
                if (!landscape) {
                    Icon(
                        Icons.Filled.Send,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                }
                Text(stringResource(R.string.send))
            }
            TextButton(onClick = onStopListening) {
                Text(stringResource(R.string.cancel))
            }
        }
    } else if (state.running || voiceState == "speaking") {
        // Listening is stopped by tapping the orb itself (its icon turns into Stop),
        // so the separate "stop listening" button is gone.
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(
            onClick = onStop,
            modifier = Modifier.fillMaxWidth(if (landscape) 1f else 0.72f),
        ) {
            Icon(
                Icons.Filled.Stop,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.interrupt))
        }
    }
}

@Composable
private fun LastReplyCard(
    reply: ChatMessage?,
    fromVoice: Boolean,
    maxLines: Int,
    onSpeakLast: (String) -> Unit,
    onOpenChat: () -> Unit,
) {
    reply ?: return
    Surface(
        color = Color(0xFF111A1E),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                if (fromVoice) {
                    stringResource(R.string.voice_reply)
                } else {
                    stringResource(R.string.text_reply)
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (fromVoice) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            SelectionContainer {
                Text(
                    text = SpeechText.sanitize(reply.text),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (reply.streaming) {
                Text(
                    "…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { onSpeakLast(reply.text) }) {
                    Icon(
                        Icons.Filled.VolumeUp,
                        contentDescription = stringResource(R.string.replay_answer),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                TextButton(onClick = { onSpeakLast(reply.text) }) {
                    Text(stringResource(R.string.replay))
                }
                TextButton(onClick = onOpenChat) {
                    Text(stringResource(R.string.open_chat))
                }
            }
        }
    }
}

@Composable
private fun BottomNotices(
    state: HermesUiState,
    onApprove: (String) -> Unit,
    onConfirmDeviceAction: (Boolean) -> Unit,
    onOpenChat: () -> Unit,
    onConnect: () -> Unit,
    onClearMessage: () -> Unit,
) {
    state.approval?.let { prompt ->
        Spacer(Modifier.height(10.dp))
        ApprovalCard(prompt = prompt, onChoice = onApprove)
    }

    state.deviceConfirm?.let { prompt ->
        Spacer(Modifier.height(10.dp))
        DeviceActionCard(
            prompt = prompt,
            onAllow = { onConfirmDeviceAction(true) },
            onDeny = { onConfirmDeviceAction(false) },
        )
    }

    if (state.clarify != null) {
        Spacer(Modifier.height(10.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.hermes_needs_answer),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onOpenChat) { Text(stringResource(R.string.open_chat)) }
            }
        }
    }

    state.error?.let { error ->
        Spacer(Modifier.height(10.dp))
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(start = 14.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    error,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                IconButton(onClick = onClearMessage) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.cd_close),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }

    if (state.link !is LinkState.Ready) {
        Spacer(Modifier.height(8.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.link is LinkState.Connecting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(10.dp))
                    Text(
                        stringResource(R.string.connecting_to_hermes),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(
                        stringResource(R.string.hermes_not_connected),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = onConnect) { Text(stringResource(R.string.connect)) }
                }
            }
        }
    }
}
