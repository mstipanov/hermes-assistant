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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hr.sting.hermes.BuildConfig
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
) {
    val settings = state.settings
    var draft by remember(settings) { mutableStateOf(settings) }
    var showSecret by remember { mutableStateOf(false) }
    var showApiToken by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
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
            "Hermes je sada zadani digitalni asistent."
        } else {
            "Hermes nije postavljen kao asistent. Otvori Postavke → Aplikacije → Zadane aplikacije."
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
                title = { Text("Postavke") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Natrag")
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
                "Povezivanje",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = draft.serverUrl,
                onValueChange = { draft = draft.copy(serverUrl = it) },
                label = { Text("Adresa servera") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                value = draft.apiToken,
                onValueChange = { draft = draft.copy(apiToken = it) },
                label = { Text("API token") },
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
                label = { Text("Korisničko ime") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.password,
                onValueChange = { draft = draft.copy(password = it) },
                label = { Text("Lozinka") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            OutlinedTextField(
                value = draft.accessClientId,
                onValueChange = { draft = draft.copy(accessClientId = it) },
                label = { Text("Cloudflare Access Client ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.accessClientSecret,
                onValueChange = { draft = draft.copy(accessClientSecret = it) },
                label = { Text("Cloudflare Access Client Secret") },
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
                "Sustavni asistent",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Postavi Hermesa kao zadani digitalni asistent. Dugi pritisak na side/home tipku tada otvara Hermesa i odmah počinje slušati.",
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
                    Text(if (held) "Hermes je zadani asistent" else "Postavi kao zadanog asistenta")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { AssistantSettings.openDefaultApps(context) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Zadane aplikacije")
                }
                OutlinedButton(
                    onClick = { AssistantSettings.openSideKey(context) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Side tipka")
                }
            }
            TextButton(onClick = { AssistantSettings.openVoiceInput(context) }) {
                Text("Otvori Assist & voice input")
            }
            roleResult?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                "Samsung: Postavke → Napredne funkcije → Tipka sa strane → Pritisni i drži → Digitalni asistent. " +
                    "Ili Postavke → Aplikacije → Zadane aplikacije → Digitalni asistent.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Hotword \"Hey Google\" / \"Hi Bixby\" ostaje vezan uz Google/Bixby i ne može se preuzeti. " +
                    "Hermes zato ima vlastiti \"Hey Hermes\" (Hands-free) koji radi iz njegovog servisa.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

            Text(
                "Dozvole za akcije",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Hermes može otvarati aplikacije, puštati glazbu, postavljati alarme i otvarati URL-ove bez posebnih dozvola. Za pronalaženje kontakata treba pristup kontaktima, a za direktan poziv (bez dialera) i CALL_PHONE.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ToggleRow(
                title = "Potvrdi akcije na telefonu",
                subtitle = "Prije poziva, SMS-a, alarma, URL-a ili pokretanja aplikacije Hermes traži tvoj pristanak.",
                checked = draft.confirmDeviceActions,
                onChange = {
                    val updated = draft.copy(confirmDeviceActions = it)
                    draft = updated
                    onApplyVoice(updated)
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { contactsLauncher.launch(Manifest.permission.READ_CONTACTS) },
                    modifier = Modifier.weight(1f),
                    enabled = !contactsGranted,
                ) {
                    Text(if (contactsGranted) "Kontakti ✓" else "Dozvoli kontakte")
                }
                OutlinedButton(
                    onClick = { callLauncher.launch(Manifest.permission.CALL_PHONE) },
                    modifier = Modifier.weight(1f),
                    enabled = !callGranted,
                ) {
                    Text(if (callGranted) "Pozivi ✓" else "Dozvoli pozive")
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

            Text(
                "Zadani prikaz",
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
                    label = { Text("Audio only") },
                )
                FilterChip(
                    selected = draft.defaultView == "chat",
                    onClick = {
                        val updated = draft.copy(defaultView = "chat")
                        draft = updated
                        onApplyVoice(updated)
                    },
                    label = { Text("Razgovor") },
                )
            }
            Text(
                "Audio only je veliki orb, status i zadnji odgovor — bez tipkovnice i transkripta. Idealno za auto i MyCar.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                "Prepoznavanje govora (STT)",
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
                    label = { Text("Google (brzo)") },
                )
                FilterChip(
                    selected = draft.sttEngine == "server",
                    onClick = {
                        val updated = draft.copy(sttEngine = "server")
                        draft = updated
                        onApplyVoice(updated)
                    },
                    label = { Text("Hermes server (privatno)") },
                )
            }
            Text(
                "Google koristi isti recognition servis kao Gboard i mnogo je brži. Audio ide Googleu. Hermes server šalje snimku na tvoj Mac (Whisper medium) — privatnije, ali sporije.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { AssistantSettings.openVoiceInput(context) }) {
                Text("Otvori postavke prepoznavanja govora")
            }

            Text(
                "Glas",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ToggleRow(
                title = "Nastavi slušati nakon odgovora",
                subtitle = "Nakon što Hermes završi odgovor, odmah ponovno otvori mikrofon za sljedeće pitanje.",
                checked = draft.continuousVoice,
                onChange = {
                    val updated = draft.copy(continuousVoice = it)
                    draft = updated
                    onApplyVoice(updated)
                },
            )
            ToggleRow(
                title = "Glasovni odgovori",
                subtitle = "Hermes govori samo kad je pitanje bilo glasovno. Na tipkani tekst odgovara samo tekstom.",
                checked = draft.ttsEnabled,
                onChange = { draft = draft.copy(ttsEnabled = it) },
            )
            Text(
                "TTS glas",
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
                    label = { Text("Android (brzo)") },
                )
                FilterChip(
                    selected = draft.ttsEngine == "hermes",
                    onClick = {
                        val updated = draft.copy(ttsEngine = "hermes")
                        draft = updated
                        onApplyVoice(updated)
                    },
                    label = { Text("Hermes Gabrijela") },
                )
            }
            Text(
                "Android koristi glas koji telefon ima (Samsung je često potpuno lokalan, Google može koristiti mrežni glas) — počinje gotovo odmah. Hermes Gabrijela je kvalitetniji neuralni glas, ali prvi zvuk čeka server.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (draft.ttsEngine == "android") {
                Text(
                    "Glas telefona",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.androidTtsVoices.isEmpty()) {
                    Text(
                        "Još nema popisa glasova. Otvori postavke glasa telefona i instaliraj hrvatski glas, ili odaberi Hermes Gabrijelu.",
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
                        Text("Zadani glas telefona")
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
                    Text("Otvori postavke glasa telefona")
                }
            }
            ToggleRow(
                title = "Dijeli sesiju s Telegramom",
                subtitle = "App nastavlja isti razgovor kao Telegram bot; povijest se osvježi prije svakog pitanja.",
                checked = draft.shareTelegramSession,
                onChange = { draft = draft.copy(shareTelegramSession = it) },
            )
            ToggleRow(
                title = "Hands-free",
                subtitle = "Slušaj \"Hey Hermes\" dok je aplikacija u pozadini (traži mikrofon i stalnu notifikaciju).",
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
                title = "Otvori u načinu slušanja",
                subtitle = "Kad god otvoriš Hermesa (uključujući Samsung dvostruki pritisak → Open app), odmah počne slušati.",
                checked = draft.listenOnOpen,
                onChange = {
                    val updated = draft.copy(listenOnOpen = it)
                    draft = updated
                    onApplyVoice(updated)
                },
            )
            if (micDenied) {
                Text(
                    "Dozvola za mikrofon je odbijena. Otvori dozvole aplikacije i uključi Mikrofon.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

            Text(
                "Glasovna diagnostika",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Servis: " + if (voiceDiag.serviceRunning) "radi" else "ne radi",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Wake word model: " + if (voiceDiag.kwsReady) "učitan" else "nije učitan",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Mikrofon: " + if (voiceDiag.micPermission) "dozvoljen" else "nije dozvoljen",
                style = MaterialTheme.typography.bodySmall,
            )
            voiceDiag.lastDetection?.let {
                Text("Zadnja detekcija: $it", style = MaterialTheme.typography.bodySmall)
            }
            voiceDiag.lastMicTest?.let {
                Text("STT test: $it", style = MaterialTheme.typography.bodySmall)
            }
            voiceDiag.lastTts?.let {
                Text("TTS: $it", style = MaterialTheme.typography.bodySmall)
            }
            voiceDiag.lastStt?.let {
                Text("STT: $it", style = MaterialTheme.typography.bodySmall)
            }
            voiceDiag.lastError?.let {
                Text(
                    "Greška: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onTestMic, modifier = Modifier.weight(1f)) {
                    Text("Testiraj mikrofon")
                }
                OutlinedButton(onClick = onTestWake, modifier = Modifier.weight(1f)) {
                    Text("Testiraj \"Hey Hermes\"")
                }
            }
            OutlinedButton(onClick = onTestTts, modifier = Modifier.fillMaxWidth()) {
                Text("Testiraj glas (TTS)")
            }
            Text(
                "Glasovni odgovori: " + if (draft.ttsEnabled) "uključeni" else "isključeni",
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
                Text("Otvori dozvole aplikacije")
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

            Button(
                onClick = { onSave(draft.copy(hasCompletedOnboarding = true)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = readyToSave,
            ) {
                Text("Spremi")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onTest(draft) },
                    modifier = Modifier.weight(1f),
                    enabled = !state.onboardingBusy && readyToSave,
                ) {
                    Text("Testiraj vezu")
                }
                OutlinedButton(
                    onClick = onReconnect,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Ponovno poveži")
                }
            }
            if (state.onboardingBusy) {
                Text("Testiram…", style = MaterialTheme.typography.bodySmall)
            }
            state.onboardingResult?.let { result ->
                val ok = result.startsWith("Povezan")
                Text(
                    result,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
            Text(
                "Diagnostika",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Veza: " + when (state.link) {
                    is LinkState.Ready -> "povezan"
                    is LinkState.Connecting -> "spajam se…"
                    is LinkState.Failed -> "greška"
                    else -> "nije povezan"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Račun: ${state.account.ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall)
            Text(
                "API token: " + if (draft.apiToken.isNotBlank()) "postavljen" else "nije postavljen",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Cloudflare Access: " +
                    if (draft.accessClientId.isNotBlank() && draft.accessClientSecret.isNotBlank()) {
                        "postavljen"
                    } else {
                        "nije postavljen"
                    },
                style = MaterialTheme.typography.bodySmall,
            )
            (state.error ?: (state.link as? LinkState.Failed)?.message)?.let { error ->
                Text(
                    "Zadnja greška: $error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text("Verzija: ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall)
            Text(
                "Ažuriranje",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onCheckUpdate,
                enabled = !state.update.checking && !state.update.downloading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.update.checking) "Provjeravam…" else "Provjeri ažuriranja")
            }
            if (state.update.upToDate) {
                Text(
                    "Aplikacija je ažurna (${BuildConfig.VERSION_NAME}).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            state.update.error?.let { error ->
                Text(
                    "Ažuriranje: $error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            state.update.available?.let { info ->
                Text(
                    "Dostupna verzija ${info.versionName}.",
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
                    Text(if (state.update.downloading) "Preuzimam…" else "Preuzmi i instaliraj")
                }
            }
            if (state.update.downloading) {
                val progress = state.update.progress
                Text(
                    if (progress != null) {
                        "Preuzimam… ${(progress * 100).toInt().coerceIn(0, 100)}%"
                    } else {
                        "Preuzimam…"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (appLog.isNotEmpty()) {
                Text(
                    "Zadnji RPC log:",
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
                    appendLine("voice=$voiceDiag")
                    appendLine("--- rpc log ---")
                    appendLine(appLog.takeLast(30).joinToString("\n"))
                }
                clipboard.setText(AnnotatedString(diagnostics))
            }) {
                Text("Kopiraj diagnostiku")
            }

            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
                Text("Odjavi se i obriši podatke")
            }
            Text(
                "Lozinka i Access tajna spremaju se AES/GCM ključem iz Android Keystorea. Ne napuštaju telefon.",
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
