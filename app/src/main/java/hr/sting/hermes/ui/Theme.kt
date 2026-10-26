package hr.sting.hermes.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val HermesTeal = Color(0xFF7CE7C4)
val HermesTealDark = Color(0xFF1F6F5B)
val HermesInk = Color(0xFF0B0D10)
val HermesSurface = Color(0xFF14181D)
val HermesSurfaceHigh = Color(0xFF1D232A)
val HermesText = Color(0xFFE8EDF2)
val HermesMuted = Color(0xFF8B98A5)
val HermesDanger = Color(0xFFFF8A80)

private val DarkColors = darkColorScheme(
    primary = HermesTeal,
    onPrimary = Color(0xFF00382C),
    primaryContainer = HermesTealDark,
    onPrimaryContainer = Color(0xFFB8FFE7),
    secondary = Color(0xFF9EC9FF),
    background = HermesInk,
    onBackground = HermesText,
    surface = HermesSurface,
    onSurface = HermesText,
    surfaceVariant = HermesSurfaceHigh,
    onSurfaceVariant = HermesMuted,
    error = HermesDanger,
    outline = Color(0xFF33404D),
)

private val LightColors = lightColorScheme(
    primary = HermesTealDark,
    onPrimary = Color.White,
    background = Color(0xFFF7FAF9),
    surface = Color.White,
)

@Composable
fun HermesTheme(content: @Composable () -> Unit) {
    // Hermes is a dark, always-on assistant UI; the light scheme made text
    // unreadable on the audio-only surface when the phone was in light mode.
    MaterialTheme(
        colorScheme = DarkColors,
        content = content,
    )
}
