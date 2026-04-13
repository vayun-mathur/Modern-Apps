package com.vayunmathur.games.unblockjam.ui
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CustomDarkColorScheme = darkColorScheme(
    primary = DarkBrown,
    secondary = Brown,
    tertiary = Tan,
    background = Brown,
    surface = DarkBrown,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White,
    primaryContainer = Orange,
    secondaryContainer = WarmGray,
    error = Color.Red
)

@Composable
fun UnblockJamTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = CustomDarkColorScheme,
        typography = Typography,
        content = content
    )
}