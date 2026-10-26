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
import androidx.core.content.ContextCompat
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
                    snackbar.showSnackbar(it.message ?: "Prilog nije učitan.")
                }
        }
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val uri = cameraUri
        cameraUri = null
        if (success && uri != null) ingest(uri, "Kamera.jpg")
    }
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> uri?.let { ingest(it, "Slika.jpg") } }
    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { ingest(it, "Datoteka") } }
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
                                text = state.title,
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
                            Icon(Icons.Filled.Menu, contentDescription = "Razgovori")
                        }
                    },
                    actions = {
                        onBackToVoice?.let { back ->
                            IconButton(onClick = back) {
                                Icon(Icons.Filled.GraphicEq, contentDescription = "Glasovni način")
                            }
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = "Postavke")
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
                        title = "Hey Hermes nije uključen",
                        subtitle = "Uključi stalno slušanje da te Hermes čuje i kad je ekran ugašen.",
                        action = "Uključi",
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
                        title = "Glasovni servis nije aktivan",
                        subtitle = voiceDiag.lastError
                            ?: "Servis je zaustavljen. Pokreni ga za \"Hey Hermes\".",
                        action = "Pokreni",
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
                    onCancel = onStop,
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
                        if (state.voiceState == "idle") micAction() else onStop()
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
    val lastLength = messages.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(messages.size, lastLength) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
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
            "Kako mogu pomoći?",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Text(
            "Piši ili pričaj — Hermes ima pristup tvom Macu, Home Assistantu i alatima.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.height(22.dp))
        listOf(
            "Koliko je sati?",
            "Što mi je danas na rasporedu?",
            "Uključi svjetlo u dnevnoj sobi",
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
                    "Hermes nije zadani asistent",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Postavi ga da te dugi pritisak na side tipku odmah spoji s Hermesom.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onOpenSettings) { Text("Postavi") }
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Zatvori",
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
                    TextButton(onClick = onRetry) { Text("Pokušaj ponovno") }
                }
                is LinkState.Connecting -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Spajam se na Hermesa…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    Text(
                        "Nije povezan s Hermesom.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRetry) { Text("Poveži") }
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
                    contentDescription = "Zatvori",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun VoicePanel(voiceState: String, onCancel: () -> Unit) {
    if (voiceState == "idle") return
    val (label, active) = when (voiceState) {
        "listening" -> "Slušam…" to true
        "transcribing" -> "Prepoznajem govor…" to true
        "thinking" -> "Hermes razmišlja…" to true
        "speaking" -> "Hermes govori…" to false
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
            TextButton(onClick = onCancel) {
                Text(if (voiceState == "speaking") "Prekini" else "Otkaži")
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
                        contentDescription = "Prilog",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Slikaj kamerom") },
                        leadingIcon = { Icon(Icons.Filled.PhotoCamera, contentDescription = null) },
                        onClick = { attachMenu = false; onCamera() },
                    )
                    DropdownMenuItem(
                        text = { Text("Iz galerije") },
                        leadingIcon = { Icon(Icons.Filled.Image, contentDescription = null) },
                        onClick = { attachMenu = false; onGallery() },
                    )
                    DropdownMenuItem(
                        text = { Text("Datoteka") },
                        leadingIcon = { Icon(Icons.Filled.InsertDriveFile, contentDescription = null) },
                        onClick = { attachMenu = false; onFile() },
                    )
                }
            }
            IconButton(onClick = onMic) {
                Icon(
                    Icons.Filled.Mic,
                    contentDescription = "Glas",
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
                                if (hasAttachments) "Dodaj objašnjenje…" else "Napiši poruku…",
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
                        contentDescription = "Zaustavi",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            } else {
                IconButton(onClick = onSend, enabled = value.isNotBlank() || hasAttachments) {
                    Icon(
                        Icons.Filled.Send,
                        contentDescription = "Pošalji",
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
                Text("Novi razgovor")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        Text(
            "RAZGOVORI",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 18.dp, top = 12.dp, bottom = 4.dp),
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        ) {
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
            Text("Postavke", modifier = Modifier.weight(1f))
        }
        Text(
            "Prijavljen kao ${state.account.ifBlank { "—" }}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 18.dp, bottom = 14.dp),
        )
    }
}

@Composable
private fun StatusText(link: LinkState, account: String) {
    val (text, color) = when (link) {
        is LinkState.Ready -> "Povezan" to MaterialTheme.colorScheme.primary
        is LinkState.Connecting -> "Spajam se…" to MaterialTheme.colorScheme.onSurfaceVariant
        is LinkState.Failed -> "Nije povezan" to MaterialTheme.colorScheme.error
        else -> "Offline" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(color, CircleShape),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (account.isBlank()) text else "$text · $account",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
