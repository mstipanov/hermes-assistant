package hr.sting.hermes.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class VoiceDiagState(
    val serviceRunning: Boolean = false,
    val kwsReady: Boolean = false,
    val micPermission: Boolean = false,
    val lastDetection: String? = null,
    val lastError: String? = null,
    val lastMicTest: String? = null,
    val lastTts: String? = null,
    val lastStt: String? = null,
)

/** Process-wide voice diagnostics surfaced in Settings; never contains audio or credentials. */
object VoiceDiagnostics {
    private val _state = MutableStateFlow(VoiceDiagState())
    val state: StateFlow<VoiceDiagState> = _state.asStateFlow()

    fun update(transform: (VoiceDiagState) -> VoiceDiagState) = _state.update(transform)

    fun resetTransient() = _state.update {
        it.copy(lastDetection = null, lastError = null, lastMicTest = null)
    }
}
