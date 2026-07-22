package com.vayunmathur.games.logicgate.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.darkColorScheme

private val LogicDark = darkColorScheme(
    primary = Color(0xFF7FD8BE),
    secondary = Color(0xFF2A3B4C),
    tertiary = Color(0xFFD7A8FF),
    background = Color(0xFF0E141B),
    surface = Color(0xFF1B242F),
    onPrimary = Color(0xFF00382E),
    onSecondary = Color.White,
    onTertiary = Color.Black,
    onBackground = Color(0xFFD9E2EC),
    onSurface = Color(0xFFD9E2EC),
    primaryContainer = Color(0xFF1F4D44),
    secondaryContainer = Color(0xFF233140),
    error = Color(0xFFFF8A80)
)

@Composable
fun LogicGateTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LogicDark, content = content)
}
