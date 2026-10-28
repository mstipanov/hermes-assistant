package hr.sting.hermes

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hr.sting.hermes.ui.AudioOnlyScreen
import hr.sting.hermes.ui.ChatScreen
import hr.sting.hermes.ui.HermesTheme
import hr.sting.hermes.ui.OnboardingScreen
import hr.sting.hermes.data.LinkState
import hr.sting.hermes.ui.SettingsScreen
import hr.sting.hermes.util.TrustedLaunch

class MainActivity : ComponentActivity() {
    private var pendingListen by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val core = (application as HermesApp).core
        val launchedFromLauncher = intent?.action == Intent.ACTION_MAIN &&
            intent.hasCategory(Intent.CATEGORY_LAUNCHER)
        // Untrusted intents (any app can start an exported activity) must not be
        // able to open the microphone; only in-process components set this flag.
        val trustedListen = TrustedLaunch.consumeListen()
        pendingListen = trustedListen || (launchedFromLauncher && core.ui.value.settings.listenOnOpen)
        if (pendingListen) showOverLockScreen()
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        setContent {
            HermesTheme {
                val state by core.ui.collectAsStateWithLifecycle()
                var mode by rememberSaveable {
                    mutableStateOf(core.ui.value.settings.defaultView)
                }

                LaunchedEffect(state.settings.defaultView) {
                    mode = state.settings.defaultView
                }
                LaunchedEffect(mode) { core.setUiMode(mode) }

                LaunchedEffect(state.link, pendingListen, state.configured) {
                    if (pendingListen && state.configured && state.link is LinkState.Ready) {
                        // A rotation / relaunch must not steal audio focus from an
                        // answer that is still being spoken or a turn that is running.
                        if (state.running || state.voiceState != "idle") {
                            pendingListen = false
                        } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            pendingListen = false
                            core.startVoice()
                        } else {
                            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
                        }
                    }
                }

                when {
                    state.showOnboarding -> OnboardingScreen(
                        state = state,
                        onTest = core::testConnection,
                        onSave = core::saveSettings,
                    )

                    state.showSettings -> SettingsScreen(
                        state = state,
                        onSave = core::saveSettings,
                        onClose = core::closeSettings,
                        onLogout = core::logout,
                        onTest = core::testConnection,
                        onReconnect = core::connect,
                        onApplyVoice = core::applyVoiceSettings,
                        onTestMic = core::testMicrophone,
                        onTestWake = core::testWakeWord,
                        onTestTts = core::testTts,
                        onCheckUpdate = core::checkForUpdates,
                        onInstallUpdate = core::installUpdate,
                    )

                    mode == "chat" -> ChatScreen(
                        state = state,
                        onConnect = core::connect,
                        onNewChat = core::newChat,
                        onSelectSession = core::selectSession,
                        onOpenSettings = core::openSettings,
                        onInput = core::updateInput,
                        onSend = { core.sendMessage() },
                        onStop = core::interrupt,
                        onMic = core::startVoice,
                        onFinishVoice = core::finishVoice,
                        onApprove = core::approve,
                        onConfirmDeviceAction = core::confirmDeviceAction,
                        onClarify = core::answerClarify,
                        onDismissRequest = core::dismissRequest,
                        onClearMessage = core::clearError,
                        onEnableHandsFree = core::enableHandsFree,
                        onStartVoiceService = core::startVoiceService,
                        onAddAttachment = core::addAttachment,
                        onRemoveAttachment = core::removeAttachment,
                        onFileRetry = core::retryFile,
                        onBackToVoice = {
                            core.stopVoice()
                            mode = "voice"
                        },
                    )

                    else -> AudioOnlyScreen(
                        state = state,
                        onOrb = {
                            when {
                                state.voiceState == "listening" && state.manualCapture ->
                                    core.finishVoice()

                                state.voiceState == "listening" ||
                                    state.voiceState == "transcribing" -> core.stopVoice()

                                state.voiceState == "idle" -> core.startVoice()
                                else -> core.interrupt()
                            }
                        },
                        onStopListening = core::stopVoice,
                        onStop = core::interrupt,
                        onSpeakLast = core::speakText,
                        onOpenChat = { mode = "chat" },
                        onOpenSettings = core::openSettings,
                        onConnect = core::connect,
                        onFinishVoice = core::finishVoice,
                        onMicModeChange = core::setMicMode,
                        onApprove = core::approve,
                        onConfirmDeviceAction = core::confirmDeviceAction,
                        onClearMessage = core::clearError,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val fromLauncher = intent.action == Intent.ACTION_MAIN &&
            intent.hasCategory(Intent.CATEGORY_LAUNCHER)
        if (TrustedLaunch.consumeListen() ||
            (fromLauncher && (application as HermesApp).core.ui.value.settings.listenOnOpen)
        ) {
            pendingListen = true
            showOverLockScreen()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            val core = (application as HermesApp).core
            if (core.ui.value.link is LinkState.Ready) {
                pendingListen = false
                core.startVoice()
            }
        }
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
    }

}
