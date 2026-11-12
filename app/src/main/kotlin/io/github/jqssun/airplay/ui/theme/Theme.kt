package io.github.jqssun.airplay.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// A fixed palette keeps the TV experience consistent instead of inheriting phone wallpaper colors.
private val AirPlayColors = darkColorScheme(
    primary = Color(0xFF8CBFFF),
    onPrimary = Color(0xFF002D61),
    primaryContainer = Color(0xFF174478),
    secondary = Color(0xFFB5B8E8),
    background = Color(0xFF080C18),
    surface = Color(0xFF101727),
    surfaceVariant = Color(0xFF202C43),
    onBackground = Color(0xFFF5F7FF),
    onSurface = Color(0xFFF5F7FF),
    onSurfaceVariant = Color(0xFFBAC6DC),
    outline = Color(0xFF52617A)
)

@Composable
fun AirPlayTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AirPlayColors, content = content)
}
