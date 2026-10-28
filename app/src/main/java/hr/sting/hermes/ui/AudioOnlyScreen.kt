package hr.sting.hermes.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hr.sting.hermes.R
import hr.sting.hermes.data.HermesUiState
import hr.sting.hermes.voice.SpeechText
import hr.sting.hermes.data.LinkState
import hr.sting.hermes.data.MessageRole

/**
 * Voice-first surface: one big orb, the live status and the latest reply.
 * No keyboard and no transcript — the chat screen is one tap away.
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
    onApprove: (String) -> Unit,
    onConfirmDeviceAction: (Boolean) -> Unit,
    onClearMessage: () -> Unit,
) {
    val voiceState = state.voiceState
    val active = voiceState != "idle" || state.running
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
                .padding(horizontal = 22.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
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
                        Text("Hermes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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

            Spacer(Modifier.weight(0.7f))

            Box(
                modifier = Modifier
                    .size(238.dp)
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
                        .size(168.dp)
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
                            state.running || voiceState == "thinking" -> Icons.Filled.Stop
                            else -> Icons.Filled.Mic
                        },
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Text(
                status,
                style = MaterialTheme.typography.headlineSmall,
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
            if (lastReply != null && state.lastTurnFromVoice &&
                voiceState != "listening" && voiceState != "transcribing"
            ) {
                TextButton(onClick = { onSpeakLast(lastReply.text) }) {
                    Icon(
                        Icons.Filled.VolumeUp,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.replay_answer))
                }
            }
            if (state.partialTranscript.isNotBlank() &&
                (voiceState == "listening" || voiceState == "transcribing")
            ) {
                Text(
                    state.partialTranscript,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 10.dp, start = 12.dp, end = 12.dp),
                )
            }

            val listening = voiceState == "listening" || voiceState == "transcribing"
            if (listening || state.running || voiceState == "speaking") {
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = { if (listening) onStopListening() else onStop() },
                    modifier = Modifier.fillMaxWidth(0.72f),
                ) {
                    Icon(
                        Icons.Filled.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        if (listening) {
                            stringResource(R.string.stop_listening)
                        } else {
                            stringResource(R.string.interrupt)
                        },
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            lastReply?.let { reply ->
                Surface(
                    color = Color(0xFF111A1E),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            if (state.lastTurnFromVoice) {
                                stringResource(R.string.voice_reply)
                            } else {
                                stringResource(R.string.text_reply)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (state.lastTurnFromVoice) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        SelectionContainer {
                            Text(
                                text = SpeechText.sanitize(reply.text),
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 6,
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
            Spacer(Modifier.height(6.dp))
        }
        }
    }
}
