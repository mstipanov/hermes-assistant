package hr.sting.hermes.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hr.sting.hermes.BuildConfig
import hr.sting.hermes.R
import hr.sting.hermes.data.AppSettings
import hr.sting.hermes.data.HermesUiState
import hr.sting.hermes.data.LinkState
import hr.sting.hermes.util.AppLog
import hr.sting.hermes.util.AssistantSettings
import hr.sting.hermes.voice.VoiceDiagnostics

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: HermesUiState,
    onSave: (AppSettings) -> Unit,
    onClose: () -> Unit,
    onLogout: () -> Unit,
    onTest: (AppSettings) -> Unit,
    onReconnect: () -> Unit,
    onApplyVoice: (AppSettings) -> Unit,
    onTestMic: () -> Unit,
    onTestWake: () -> Unit,
    onTestTts: () -> Unit,
    onCheckUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onOpenActionPermissions: () -> Unit,
) {
    val settings = state.settings
    var draft by remember(settings) { mutableStateOf(settings) }
    var showSecret by remember { mutableStateOf(false) }
    var showApiToken by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val assistantConnectedResult = stringResource(R.string.assistant_connected_toast)
    val assistantNotSetResult = stringResource(R.string.assistant_not_set)
    val roleManager = remember { context.getSystemService(RoleManager::class.java) }
    var roleResult by remember { mutableStateOf<String?>(null) }
    var micDenied by remember { mutableStateOf(false) }
    val voiceDiag by VoiceDiagnostics.state.collectAsStateWithLifecycle()
    val appLog by AppLog.lines.collectAsStateWithLifecycle()
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            val updated = draft.copy(handsFree = true, wakeWordEnabled = true)
            draft = updated
            onApplyVoice(updated)
        } else {
            micDenied = true
        }
    }
    var contactsGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var callGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val contactsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> contactsGranted = granted }
    val callLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> callGranted = granted }
    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        roleResult = if (roleManager?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true) {
            assistantConnectedResult
        } else {
            assistantNotSetResult
        }
    }
    val needsAccess = draft.serverUrl.contains("sting.hr", ignoreCase = true)
    val accessReady = !needsAccess ||
        (draft.accessClientId.isNotBlank() && draft.accessClientSecret.isNotBlank())
    val readyToSave = draft.serverUrl.isNotBlank() && accessReady &&
        (draft.apiToken.isNotBlank() || (draft.username.isNotBlank() && draft.password.isNotBlank()))

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.connection_section),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = draft.serverUrl,
                onValueChange = { draft = draft.copy(serverUrl = it) },
                label = { Text(stringResource(R.string.server_url)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                value = draft.apiToken,
                onValueChange = { draft = draft.copy(apiToken = it) },
                label = { Text(stringResource(R.string.api_token)) },
                singleLine = true,
                visualTransformation = if (showApiToken) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showApiToken = !showApiToken }) {
                        Icon(
                            if (showApiToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = null,
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            OutlinedTextField(
                value = draft.username,
                onValueChange = { draft = draft.copy(username = it) },
                label = { Text(stringResource(R.string.username)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.password,
                onValueChange = { draft = draft.copy(password = it) },
                label = { Text(stringResource(R.string.password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            OutlinedTextField(
                value = draft.accessClientId,
                onValueChange = { draft = draft.copy(accessClientId = it) },
                label = { Text("Cloudflare " + stringResource(R.string.access_client_id)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.accessClientSecret,
                onValueChange = { draft = draft.copy(accessClientSecret = it) },
                label = { Text("Cloudflare " + stringResource(R.string.access_client_secret)) },
                singleLine = true,
                visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showSecret = !showSecret }) {
                        Icon(
                            if (showSecret) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = null,
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

            Text(
                stringResource(R.string.system_assistant),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.system_assistant_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && roleManager != null) {
                val held = roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
                OutlinedButton(
                    onClick = {
                        if (!held && roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                            roleLauncher.launch(
                                roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT),
                            )
                        } else {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (held) {
                            stringResource(R.string.assistant_is_default)
                        } else {
                            stringResource(R.string.set_as_default_assistant)
                        },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { AssistantSettings.openDefaultApps(context) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.default_apps))
                }
                OutlinedButton(
                    onClick = { AssistantSettings.openSideKey(context) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.side_key))
                }
            }
            TextButton(onClick = { AssistantSettings.openVoiceInput(context) }) {
                Text(stringResource(R.string.open_assist_voice_input))
            }
            roleResult?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                stringResource(R.string.samsung_instructions),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.hotword_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

            Text(
                stringResource(R.string.action_permissions),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.action_permissions_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ToggleRow(
                title = stringResource(R.string.confirm_device_actions),
                subtitle = stringResource(R.string.confirm_device_actions_desc),
                checked = draft.confirmDeviceActions,
                onChange = {
                    val updated = draft.copy(confirmDeviceActions = it)
                    draft = updated
                    onApplyVoice(updated)
                },
            )
            TextButton(onClick = onOpenActionPermissions) {
                Text(stringResource(R.string.action_permissions_entry))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { contactsLauncher.launch(Manifest.permission.READ_CONTACTS) },
                    modifier = Modifier.weight(1f),
                    enabled = !contactsGranted,
                ) {
                    Text(
                        if (contactsGranted) {
                            stringResource(R.string.contacts_granted)
                        } else {
                            stringResource(R.string.allow_contacts)
                        },
                    )
                }
                OutlinedButton(
                    onClick = { callLauncher.launch(Manifest.permission.CALL_PHONE) },
                    modifier = Modifier.weight(1f),
                    enabled = !callGranted,
                ) {
                    Text(
                        if (callGranted) {
                            stringResource(R.string.calls_granted)
                        } else {
                            stringResource(R.string.allow_calls)
                        },
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

            Text(
                stringResource(R.string.default_view),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = draft.defaultView != "chat",
                    onClick = {
                        val updated = draft.copy(defaultView = "voice")
                        draft = updated
                        onApplyVoice(updated)
                    },
                    label = { Text(stringResource(R.string.audio_only)) },
                )
                FilterChip(
                    selected = draft.defaultView == "chat",
                    onClick = {
                        val updated = draft.copy(defaultView = "chat")
                        draft = updated
                        onApplyVoice(updated)
                    },
                    label = { Text(stringResource(R.string.chat)) },
                )
            }
            Text(
                stringResource(R.string.audio_only_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                stringResource(R.string.stt_section),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = draft.sttEngine == "google",
                    onClick = {
                        val updated = draft.copy(sttEngine = "google")
                        draft = updated
                        onApplyVoice(updated)
                    },
                    label = { Text(stringResource(R.string.google_fast)) },
                )
                FilterChip(
                    selected = draft.sttEngine == "server",
                    onClick = {
                        val updated = draft.copy(sttEngine = "server")
                        draft = updated
                        onApplyVoice(updated)
                    },
                    label = { Text(stringResource(R.string.hermes_server_private)) },
                )
            }
            Text(
                stringResource(R.string.stt_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { AssistantSettings.openVoiceInput(context) }) {
                Text(stringResource(R.string.open_speech_settings))
            }

            Text(
                stringResource(R.string.capture_section),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = draft.micMode != "manual",
                    onClick = {
                        val updated = draft.copy(micMode = "auto")
                        draft = updated
                        onApplyVoice(updated)
                    },
                    label = { Text(stringResource(R.string.capture_auto)) },
                )
                FilterChip(
                    selected = draft.micMode == "manual",
                    onClick = {
                        val updated = draft.copy(micMode = "manual")
                        draft = updated
                        onApplyVoice(updated)
                    },
                    label = { Text(stringResource(R.string.capture_manual)) },
                )
            }
            Text(
                stringResource(R.string.capture_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                stringResource(R.string.voice_section),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ToggleRow(
                title = stringResource(R.string.continue_listening),
                subtitle = stringResource(R.string.continue_listening_desc),
                checked = draft.continuousVoice,
                onChange = {
                    val updated = draft.copy(continuousVoice = it)
                    draft = updated
                    onApplyVoice(updated)
                },
            )
            ToggleRow(
                title = stringResource(R.string.voice_replies),
                subtitle = stringResource(R.string.voice_replies_desc),
                checked = draft.ttsEnabled,
                onChange = { draft = draft.copy(ttsEnabled = it) },
            )
            Text(
                stringResource(R.string.tts_voice),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = draft.ttsEngine == "android",
                    onClick = {
                        val updated = draft.copy(ttsEngine = "android")
                        draft = updated
                        onApplyVoice(updated)
                    },
                    label = { Text(stringResource(R.string.android_fast)) },
                )
                FilterChip(
                    selected = draft.ttsEngine == "hermes",
                    onClick = {
                        val updated = draft.copy(ttsEngine = "hermes")
                        draft = updated
                        onApplyVoice(updated)
                    },
                    label = { Text(stringResource(R.string.hermes_gabrijela)) },
                )
            }
            Text(
                stringResource(R.string.tts_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (draft.ttsEngine == "android") {
                Text(
                    stringResource(R.string.phone_voice),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.androidTtsVoices.isEmpty()) {
                    Text(
                        stringResource(R.string.no_voices),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val updated = draft.copy(androidTtsVoice = null)
                                draft = updated
                                onApplyVoice(updated)
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = draft.androidTtsVoice == null,
                            onClick = null,
                        )
                        Text(stringResource(R.string.default_phone_voice))
                    }
                    state.androidTtsVoices.forEach { voice ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val updated = draft.copy(androidTtsVoice = voice.id)
                                    draft = updated
                                    onApplyVoice(updated)
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = draft.androidTtsVoice == voice.id,
                                onClick = null,
                            )
                            Text(voice.label)
                        }
                    }
                }
                TextButton(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent("com.android.settings.TTS_SETTINGS"))
                        }.onFailure {
                            runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                        }
                    },
                ) {
                    Text(stringResource(R.string.open_phone_voice_settings))
                }
            }
            ToggleRow(
                title = stringResource(R.string.hands_free),
                subtitle = stringResource(R.string.hands_free_desc),
                checked = draft.handsFree,
                onChange = { enabled ->
                    if (!enabled) {
                        val updated = draft.copy(handsFree = false, wakeWordEnabled = false)
                        draft = updated
                        onApplyVoice(updated)
                    } else if (ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        val updated = draft.copy(handsFree = true, wakeWordEnabled = true)
                        draft = updated
                        onApplyVoice(updated)
                    } else {
                        micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
            )
            ToggleRow(
                title = stringResource(R.string.open_listening_mode),
                subtitle = stringResource(R.string.open_listening_mode_desc),
                checked = draft.listenOnOpen,
                onChange = {
                    val updated = draft.copy(listenOnOpen = it)
                    draft = updated
                    onApplyVoice(updated)
                },
            )
            if (micDenied) {
                Text(
                    stringResource(R.string.mic_permission_denied),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

            Text(
                stringResource(R.string.voice_diagnostics),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.service) + ": " + if (voiceDiag.serviceRunning) {
                    stringResource(R.string.running)
                } else {
                    stringResource(R.string.not_running)
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.wake_word_model) + ": " + if (voiceDiag.kwsReady) {
                    stringResource(R.string.loaded)
                } else {
                    stringResource(R.string.not_loaded)
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.microphone) + ": " + if (voiceDiag.micPermission) {
                    stringResource(R.string.allowed)
                } else {
                    stringResource(R.string.not_allowed)
                },
                style = MaterialTheme.typography.bodySmall,
            )
            voiceDiag.lastDetection?.let {
                Text(
                    stringResource(R.string.last_detection) + ": " + it,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            voiceDiag.lastMicTest?.let {
                Text(
                    stringResource(R.string.stt_test) + ": " + it,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            voiceDiag.lastTts?.let {
                Text(
                    stringResource(R.string.tts_label) + ": " + it,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            voiceDiag.lastStt?.let {
                Text(
                    stringResource(R.string.stt_label) + ": " + it,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            voiceDiag.lastError?.let {
                Text(
                    stringResource(R.string.error) + ": " + it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onTestMic, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.test_microphone))
                }
                OutlinedButton(onClick = onTestWake, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.test_wake_word))
                }
            }
            OutlinedButton(onClick = onTestTts, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.test_voice))
            }
            Text(
                stringResource(R.string.voice_replies) + ": " + if (draft.ttsEnabled) {
                    stringResource(R.string.toggle_on)
                } else {
                    stringResource(R.string.toggle_off)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (draft.ttsEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = {
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}"),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }) {
                Text(stringResource(R.string.open_app_permissions))
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

            Button(
                onClick = { onSave(draft.copy(hasCompletedOnboarding = true)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = readyToSave,
            ) {
                Text(stringResource(R.string.save))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onTest(draft) },
                    modifier = Modifier.weight(1f),
                    enabled = !state.onboardingBusy && readyToSave,
                ) {
                    Text(stringResource(R.string.test_connection))
                }
                OutlinedButton(
                    onClick = onReconnect,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.reconnect))
                }
            }
            if (state.onboardingBusy) {
                Text(stringResource(R.string.testing), style = MaterialTheme.typography.bodySmall)
            }
            state.onboardingResult?.let { result ->
                Text(
                    result,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.onboardingOk) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
            Text(
                stringResource(R.string.diagnostics),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.link) + ": " + when (state.link) {
                    is LinkState.Ready -> stringResource(R.string.link_connected)
                    is LinkState.Connecting -> stringResource(R.string.link_connecting)
                    is LinkState.Failed -> stringResource(R.string.link_error)
                    else -> stringResource(R.string.link_not_connected)
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.account) + ": " + state.account.ifBlank { "—" },
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.api_token) + ": " + if (draft.apiToken.isNotBlank()) {
                    stringResource(R.string.set)
                } else {
                    stringResource(R.string.not_set)
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.cloudflare_access_status) + ": " +
                    if (draft.accessClientId.isNotBlank() && draft.accessClientSecret.isNotBlank()) {
                        stringResource(R.string.set)
                    } else {
                        stringResource(R.string.not_set)
                    },
                style = MaterialTheme.typography.bodySmall,
            )
            (state.error ?: (state.link as? LinkState.Failed)?.message)?.let { error ->
                Text(
                    stringResource(R.string.last_error) + ": $error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                stringResource(R.string.version) + ": ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                stringResource(R.string.updates),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onCheckUpdate,
                enabled = !state.update.checking && !state.update.downloading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (state.update.checking) {
                        stringResource(R.string.checking)
                    } else {
                        stringResource(R.string.check_updates)
                    },
                )
            }
            if (state.update.upToDate) {
                Text(
                    stringResource(R.string.up_to_date, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            state.update.error?.let { error ->
                Text(
                    stringResource(R.string.update_error, error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            state.update.available?.let { info ->
                Text(
                    stringResource(R.string.update_available, info.versionName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                info.changelog.take(4).forEach { line ->
                    Text("• $line", style = MaterialTheme.typography.labelSmall)
                }
                Button(
                    onClick = onInstallUpdate,
                    enabled = !state.update.downloading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (state.update.downloading) {
                            stringResource(R.string.downloading)
                        } else {
                            stringResource(R.string.download_and_install)
                        },
                    )
                }
            }
            if (state.update.downloading) {
                val progress = state.update.progress
                Text(
                    if (progress != null) {
                        stringResource(
                            R.string.downloading_progress,
                            (progress * 100).toInt().coerceIn(0, 100),
                        )
                    } else {
                        stringResource(R.string.downloading)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (appLog.isNotEmpty()) {
                Text(
                    stringResource(R.string.last_rpc_log),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    appLog.takeLast(8).joinToString("\n"),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = {
                val diagnostics = buildString {
                    appendLine("server=${draft.serverUrl}")
                    appendLine("api_token=${if (draft.apiToken.isNotBlank()) "set" else "missing"}")
                    appendLine("access=${if (accessReady) "set" else "missing"}")
                    appendLine("link=${state.link}")
                    appendLine("account=${state.account}")
                    appendLine("error=${state.error ?: "-"}")
                    appendLine("version=${BuildConfig.VERSION_NAME}")
                    appendLine("tts_enabled=${draft.ttsEnabled}")
                    appendLine("tts_engine=${draft.ttsEngine}")
                    appendLine("android_tts_voice=${draft.androidTtsVoice ?: "default"}")
                    appendLine("default_view=${draft.defaultView}")
                    appendLine("continuous_voice=${draft.continuousVoice}")
                    appendLine("mic_mode=${draft.micMode}")
                    appendLine("voice=$voiceDiag")
                    appendLine("--- rpc log ---")
                    appendLine(appLog.takeLast(30).joinToString("\n"))
                }
                clipboard.setText(AnnotatedString(diagnostics))
            }) {
                Text(stringResource(R.string.copy_diagnostics))
            }

            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.logout_and_clear))
            }
            Text(
                stringResource(R.string.secrets_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
