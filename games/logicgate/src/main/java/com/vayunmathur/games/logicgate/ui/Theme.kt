package com.vayunmathur.games.logicgate.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.darkColorScheme

private val LogicDark = darkColorScheme(
    primary = Color(0xFF3DD8C2),
    secondary = Color(0xFF2D4A66),
    tertiary = Color(0xFFFFB74D),
    background = Color(0xFF0E1724),
    surface = Color(0xFF1B2636),
    onPrimary = Color.Black,
    onSecondary = Color.White,
    onTertiary = Color.Black,
    onBackground = Color(0xFFC8D6E5),
    onSurface = Color(0xFFC8D6E5),
    primaryContainer = Color(0xFF0E3A36),
    secondaryContainer = Color(0xFF23344A),
    error = Color(0xFFFF8A80),
).copy(
    surfaceContainer = Color(0xFF1B2636),
    surfaceContainerHigh = Color(0xFF233247),
    surfaceContainerHighest = Color(0xFF26384F),
    surfaceContainerLow = Color(0xFF162132),
    surfaceVariant = Color(0xFF26384F),
    onSurfaceVariant = Color(0xFF8FA3B8),
    onPrimaryContainer = Color(0xFFB6F5EC),
    onSecondaryContainer = Color(0xFFC8D6E5),
    outline = Color(0xFF3B4E66),
)

@Composable
fun LogicGateTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LogicDark, content = content)
}
