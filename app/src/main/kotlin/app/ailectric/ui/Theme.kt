package app.ailectric.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF00695C), onPrimary = Color.White,
    primaryContainer = Color(0xFFB2DFDB), onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF6A1B9A), tertiary = Color(0xFFC45014),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF80CBC4), onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF00504A), onPrimaryContainer = Color(0xFFB2DFDB),
    secondary = Color(0xFFCE93D8), tertiary = Color(0xFFFFAB91),
)

@Composable
fun AilectricTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
