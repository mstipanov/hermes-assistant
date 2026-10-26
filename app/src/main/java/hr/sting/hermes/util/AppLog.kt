package hr.sting.hermes.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small in-memory ring buffer of client-side events (never audio/tokens).
 * Shown in Settings and included in "Copy diagnostics" so a failure can be
 * diagnosed without a USB connection.
 */
object AppLog {
    private const val MAX_LINES = 120
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale("hr", "HR"))

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun add(message: String) {
        val line = "${timeFormat.format(Date())}  $message"
        _lines.update { (it + line).takeLast(MAX_LINES) }
    }

    fun snapshot(): String = _lines.value.joinToString("\n")

    fun clear() {
        _lines.value = emptyList()
    }
}
