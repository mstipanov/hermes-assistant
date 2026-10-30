package hr.sting.hermes.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import hr.sting.hermes.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hr.sting.hermes.data.ChatMessage
import hr.sting.hermes.data.HermesUiState
import hr.sting.hermes.data.LinkState
import hr.sting.hermes.data.PendingAttachment
import hr.sting.hermes.util.AssistantSettings
import hr.sting.hermes.voice.VoiceDiagnostics
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: HermesUiState,
    onConnect: () -> Unit,
    onNewChat: () -> Unit,
    onSelectSession: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onMic: () -> Unit,
    onFinishVoice: () -> Unit,
    onApprove: (String) -> Unit,
    onConfirmDeviceAction: (Boolean) -> Unit,
    onClarify: (Map<String, String?>) -> Unit,
    onDismissRequest: () -> Unit,
    onClearMessage: () -> Unit,
    onEnableHandsFree: () -> Unit,
    onStartVoiceService: () -> Unit,
    onBackToVoice: (() -> Unit)? = null,
    onAddAttachment: (PendingAttachment) -> Unit = {},
    onRemoveAttachment: (String) -> Unit = {},
    onFileRetry: (String) -> Unit = {},
) {
    BackHandler(enabled = onBackToVoice != null) { onBackToVoice?.invoke() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val galleryFilename = stringResource(R.string.gallery_filename)
    val fileFallbackName = stringResource(R.string.file)
    val cameraFilename = stringResource(R.string.camera_filename)
    val attachmentNotLoaded = stringResource(R.string.attachment_not_loaded)
    var assistantRoleHeld by remember { mutableStateOf(AssistantSettings.isRoleHeld(context)) }
    var assistantBannerDismissed by rememberSaveable { mutableStateOf(false) }
    val voiceDiag by VoiceDiagnostics.state.collectAsStateWithLifecycle()
    val inputFocus = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var lastAttachmentCount by remember { mutableStateOf(0) }
    LaunchedEffect(state.pendingAttachments.size) {
        if (state.pendingAttachments.size > lastAttachmentCount) {
            inputFocus.requestFocus()
            keyboardController?.show()
        }
        lastAttachmentCount = state.pendingAttachments.size
    }
    val handsFreeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) onEnableHandsFree() }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val ingest: (Uri, String) -> Unit = { uri, fallback ->
        scope.launch {
            runCatching { readAttachment(context, uri, fallback) }
                .onSuccess { attachment -> attachment?.let(onAddAttachment) }
                .onFailure {
                    snackbar.showSnackbar(
                        it.message ?: attachmentNotLoaded,
                    )
                }
        }
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val uri = cameraUri
        cameraUri = null
        if (success && uri != null) ingest(uri, cameraFilename)
    }
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> uri?.let { ingest(it, galleryFilename) } }
    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { ingest(it, fileFallbackName) } }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                assistantRoleHeld = AssistantSettings.isRoleHeld(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) onMic() }
    val micAction = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            onMic()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(state.notice) {
        val message = state.notice
        if (!message.isNullOrBlank()) {
            snackbar.showSnackbar(message)
            onClearMessage()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface,
            ) {
                DrawerContent(
                    state = state,
                    onNewChat = {
                        scope.launch { drawerState.close() }
                        onNewChat()
                    },
                    onSelectSession = { id ->
                        scope.launch { drawerState.close() }
                        onSelectSession(id)
                    },
                    onSettings = {
                        scope.launch { drawerState.close() }
                        onOpenSettings()
                    },
                )
            }
        },
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = state.title.ifBlank {
                                    stringResource(R.string.new_chat)
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (state.model.isNotBlank()) {
                                Text(
                                    text = state.model,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(
                                Icons.Filled.Menu,
                                contentDescription = stringResource(R.string.sessions),
                            )
                        }
                    },
                    actions = {
                        onBackToVoice?.let { back ->
                            IconButton(onClick = back) {
                                Icon(
                                    Icons.Filled.GraphicEq,
                                    contentDescription = stringResource(R.string.voice_mode),
                                )
                            }
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = stringResource(R.string.cd_settings),
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                if (!state.connected) {
                    ConnectionBanner(state.link, onConnect)
                }

                if (!assistantRoleHeld && !assistantBannerDismissed) {
                    AssistantBanner(
                        onOpenSettings = { AssistantSettings.openAssistantSettings(context) },
                        onDismiss = { assistantBannerDismissed = true },
                    )
                }

                if (!state.settings.handsFree) {
                    HandsFreeBanner(
                        title = stringResource(R.string.wake_word_off_title),
                        subtitle = stringResource(R.string.wake_word_off_subtitle),
                        action = stringResource(R.string.enable),
                        onAction = {
                            if (ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO,
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                onEnableHandsFree()
                            } else {
                                handsFreeLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                    )
                } else if (!voiceDiag.serviceRunning) {
                    HandsFreeBanner(
                        title = stringResource(R.string.voice_service_inactive_title),
                        subtitle = voiceDiag.lastError
                            ?: stringResource(R.string.voice_service_inactive_subtitle),
                        action = stringResource(R.string.start),
                        onAction = onStartVoiceService,
                    )
                }

                Box(modifier = Modifier.weight(1f)) {
                    if (state.messages.isEmpty()) {
                        EmptyState(onSuggestion = { text ->
                            onInput(text)
                            onSend()
                        })
                    } else {
                        MessageList(state.messages, onFileRetry)
                    }
                }

                if (state.pendingAttachments.isNotEmpty()) {
                    AttachmentStrip(
                        attachments = state.pendingAttachments,
                        onRemove = onRemoveAttachment,
                    )
                }

                VoicePanel(
                    voiceState = state.voiceState,
                    manual = state.manualCapture,
                    onCancel = onStop,
                    onFinish = onFinishVoice,
                )

                state.error?.let { error ->
                    ErrorBanner(error = error, onDismiss = onClearMessage)
                }

                state.approval?.let { prompt ->
                    Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        ApprovalCard(prompt = prompt, onChoice = onApprove)
                    }
                }

                state.deviceConfirm?.let { prompt ->
                    Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        DeviceActionCard(
                            prompt = prompt,
                            onAllow = { onConfirmDeviceAction(true) },
                            onDeny = { onConfirmDeviceAction(false) },
                        )
                    }
                }

                InputBar(
                    value = state.input,
                    running = state.running,
                    hasAttachments = state.pendingAttachments.isNotEmpty(),
                    onInput = onInput,
                    onSend = onSend,
                    onStop = onStop,
                    onMic = {
                        when {
                            state.voiceState == "idle" -> micAction()
                            state.manualCapture -> onFinishVoice()
                            else -> onStop()
                        }
                    },
                    focusRequester = inputFocus,
                    onCamera = {
                        val uri = cameraOutputUri(context)
                        cameraUri = uri
                        cameraLauncher.launch(uri)
                    },
                    onGallery = { galleryLauncher.launch("image/*") },
                    onFile = { fileLauncher.launch(arrayOf("*/*")) },
                )
            }

            state.clarify?.let { prompt ->
                ClarifyDialog(
                    prompt = prompt,
                    onSubmit = onClarify,
                    onDismiss = onDismissRequest,
                )
            }
        }
    }
}

@Composable
private fun MessageList(messages: List<ChatMessage>, onFileRetry: (String) -> Unit) {
    val listState = rememberLazyListState()
    // Pinned means the newest message is on screen: only then does growing content follow the
    // bottom. Scrolling up to read history un-pins the list and suspends auto-scrolling.
    val pinned by remember(listState) {
        derivedStateOf { !listState.canScrollForward }
    }
    val lastLength = messages.lastOrNull()?.text?.length ?: 0
    // The first message id changes only when the shown session changes, so this is the first
    // load of a session: jump to the newest message once instead of animating the whole history.
    val sessionKey = messages.firstOrNull()?.id.orEmpty()
    LaunchedEffect(sessionKey) {
        if (messages.isNotEmpty()) {
            listState.scrollToItem(messages.lastIndex)
        }
    }
    // Streaming updates follow the bottom instantly, but never yank a user who scrolled up.
    LaunchedEffect(messages.size, lastLength) {
        if (pinned && messages.isNotEmpty()) {
            listState.scrollToItem(messages.lastIndex)
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(messages, key = { it.id }) { message ->
            MessageBubble(message, onFileRetry)
        }
    }
}

@Composable
private fun EmptyState(onSuggestion: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(
                    brush = Brush.radialGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                            Color.Transparent,
                        ),
                    ),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.GraphicEq,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            stringResource(R.string.empty_chat_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.empty_chat_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.height(22.dp))
        listOf(
            stringResource(R.string.suggestion_time),
            stringResource(R.string.suggestion_schedule),
            stringResource(R.string.suggestion_light),
        ).forEach { suggestion ->
            AssistChip(
                onClick = { onSuggestion(suggestion) },
                label = { Text(suggestion) },
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun HandsFreeBanner(
    title: String,
    subtitle: String,
    action: String,
    onAction: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Mic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun AssistantBanner(onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.GraphicEq,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.default_assistant_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.default_assistant_subtitle),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.set_up)) }
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.cd_close),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ConnectionBanner(link: LinkState, onRetry: () -> Unit) {
    Surface(
        color = when (link) {
            is LinkState.Failed -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (link) {
                is LinkState.Failed -> {
                    Text(
                        text = link.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                }
                is LinkState.Connecting -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.connecting_to_hermes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    Text(
                        stringResource(R.string.not_connected_to_hermes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.connect)) }
                }
            }
        }
    }
}

@Composable
private fun ErrorBanner(error: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.cd_close),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun VoicePanel(
    voiceState: String,
    manual: Boolean,
    onCancel: () -> Unit,
    onFinish: () -> Unit,
) {
    if (voiceState == "idle") return
    val (label, active) = when (voiceState) {
        "listening" -> stringResource(
            if (manual) R.string.status_recording else R.string.status_listening,
        ) to true
        "transcribing" -> stringResource(R.string.status_transcribing) to true
        "thinking" -> stringResource(R.string.status_thinking) to true
        "speaking" -> stringResource(R.string.status_speaking) to false
        else -> return
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (active) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    Icons.Filled.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (manual && voiceState == "listening") {
                TextButton(onClick = onFinish) { Text(stringResource(R.string.send)) }
                TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            } else {
                TextButton(onClick = onCancel) {
                    Text(
                        if (voiceState == "speaking") {
                            stringResource(R.string.interrupt)
                        } else {
                            stringResource(R.string.cancel)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    running: Boolean,
    hasAttachments: Boolean,
    focusRequester: FocusRequester,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onMic: () -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onFile: () -> Unit,
) {
    var attachMenu by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(26.dp),
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                IconButton(onClick = { attachMenu = true }) {
                    Icon(
                        Icons.Filled.AttachFile,
                        contentDescription = stringResource(R.string.attachment),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.take_photo)) },
                        leadingIcon = { Icon(Icons.Filled.PhotoCamera, contentDescription = null) },
                        onClick = { attachMenu = false; onCamera() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.from_gallery)) },
                        leadingIcon = { Icon(Icons.Filled.Image, contentDescription = null) },
                        onClick = { attachMenu = false; onGallery() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.file)) },
                        leadingIcon = { Icon(Icons.Filled.InsertDriveFile, contentDescription = null) },
                        onClick = { attachMenu = false; onFile() },
                    )
                }
            }
            IconButton(onClick = onMic) {
                Icon(
                    Icons.Filled.Mic,
                    contentDescription = stringResource(R.string.cd_voice),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onInput,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                decorationBox = { inner ->
                    Box {
                        if (value.isBlank()) {
                            Text(
                                if (hasAttachments) {
                                    stringResource(R.string.add_description)
                                } else {
                                    stringResource(R.string.message_hint)
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                },
            )
            if (running) {
                IconButton(onClick = onStop) {
                    Icon(
                        Icons.Filled.Stop,
                        contentDescription = stringResource(R.string.stop),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            } else {
                IconButton(onClick = onSend, enabled = value.isNotBlank() || hasAttachments) {
                    Icon(
                        Icons.Filled.Send,
                        contentDescription = stringResource(R.string.send),
                        tint = if (value.isNotBlank() || hasAttachments) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerContent(
    state: HermesUiState,
    onNewChat: () -> Unit,
    onSelectSession: (String) -> Unit,
    onSettings: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "H",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        "Hermes",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    StatusText(state.link, state.account)
                }
            }
            Spacer(Modifier.height(14.dp))
            Button(onClick = onNewChat, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.new_chat))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        ) {
            if (state.jobs.isNotEmpty()) {
                item(key = "jobs-label") {
                    Text(
                        stringResource(R.string.jobs_upper),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 10.dp, top = 8.dp, bottom = 4.dp),
                    )
                }
                items(state.jobs, key = { "job-" + it.id }) { job ->
                    SessionRowItem(
                        session = job,
                        current = job.id == state.currentStoredId,
                        onClick = { onSelectSession(job.id) },
                    )
                }
                item(key = "jobs-divider") {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
            item(key = "conversations-label") {
                Text(
                    stringResource(R.string.conversations_upper),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 10.dp, top = 8.dp, bottom = 4.dp),
                )
            }
            items(state.sessions, key = { it.id }) { session ->
                SessionRowItem(
                    session = session,
                    current = session.id == state.currentStoredId,
                    onClick = { onSelectSession(session.id) },
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        TextButton(
            onClick = onSettings,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.settings), modifier = Modifier.weight(1f))
        }
        Text(
            stringResource(R.string.signed_in_as, state.account.ifBlank { "—" }),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 18.dp, bottom = 14.dp),
        )
    }
}

@Composable
private fun StatusText(link: LinkState, account: String) {
    val (text, color) = when (link) {
        is LinkState.Ready -> stringResource(R.string.status_connected) to MaterialTheme.colorScheme.primary
        is LinkState.Connecting -> stringResource(R.string.status_connecting) to
            MaterialTheme.colorScheme.onSurfaceVariant
        is LinkState.Failed -> stringResource(R.string.status_not_connected) to
            MaterialTheme.colorScheme.error
        else -> stringResource(R.string.status_offline) to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(color, CircleShape),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (account.isBlank()) {
                text
            } else {
                stringResource(R.string.account_separator, text, account)
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
