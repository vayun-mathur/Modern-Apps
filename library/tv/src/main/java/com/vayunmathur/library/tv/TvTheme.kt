package com.vayunmathur.library.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ColorScheme
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.compose.material3.LocalContentColor as M3LocalContentColor
import androidx.compose.material3.MaterialTheme as M3MaterialTheme
import androidx.compose.material3.darkColorScheme as m3DarkColorScheme

/** One accent per app, so each TV app is recognisable at a glance from across the room. */
object TvAccent {
    val Red = Color(0xFFFF5B52)
    val Teal = Color(0xFF41D6A8)
    val Amber = Color(0xFFFFB84D)
    val Blue = Color(0xFF7DB4FF)
}

/** Ten-foot spacing. Horizontal/vertical safe values keep content inside TV overscan. */
object TvDimens {
    val SafeHorizontal: Dp = 48.dp
    val SafeVertical: Dp = 28.dp
    val Rail: Dp = 120.dp
    val ItemGap: Dp = 16.dp
    val ShelfGap: Dp = 24.dp
    val InsideFrameStart: Dp = 16.dp
}

/** Start inset for screen content: smaller inside [TvAppFrame] because the rail already insets it. */
internal val LocalTvContentStart = compositionLocalOf { TvDimens.SafeHorizontal }

/**
 * Always-dark TV theme keyed off one accent. Focused surfaces flip to near-white with dark
 * content (the Google TV convention), so focus is legible on any backdrop.
 *
 * Also installs a matching dark Material 3 scheme: `MainNavigation` paints each route with the
 * M3 background and `:library:ui` pieces read M3 colours, which otherwise default to light.
 * Paints nothing itself, so it can wrap overlays above a video surface.
 */
@Composable
fun MaTvTheme(accent: Color, content: @Composable () -> Unit) {
    val scheme = remember(accent) { tvColorScheme(accent) }
    val m3Scheme = remember(accent) { m3ColorScheme(scheme) }
    M3MaterialTheme(colorScheme = m3Scheme) {
        MaterialTheme(colorScheme = scheme) {
            CompositionLocalProvider(
                LocalContentColor provides scheme.onSurface,
                M3LocalContentColor provides scheme.onSurface,
                content = content,
            )
        }
    }
}

/**
 * Bridges tv-material's content colour into Material 3's, which `:library:ui` icons read.
 * Without this an `IconXxx()` inside a focused tv surface keeps the unfocused colour.
 */
@Composable
fun TvIcon(content: @Composable () -> Unit) {
    CompositionLocalProvider(M3LocalContentColor provides LocalContentColor.current, content = content)
}

/** Luminance above which an accent gets dark on-accent content instead of white. */
private const val ON_ACCENT_LUMINANCE_THRESHOLD = 0.4f
private const val PRIMARY_CONTAINER_BLEND = 0.7f
private const val ON_PRIMARY_CONTAINER_BLEND = 0.6f
private const val SECONDARY_BLEND = 0.35f
private const val SECONDARY_CONTAINER_BLEND = 0.8f
private const val TERTIARY_BLEND = 0.5f
private const val BORDER_BLEND = 0.25f

private val Background = Color(0xFF0B0C10)
private val Surface = Color(0xFF16181D)
private val SurfaceVariant = Color(0xFF23262D)
private val OnSurface = Color(0xFFECEDF0)
private val OnSurfaceVariant = Color(0xFFA6AAB3)
private val Error = Color(0xFFFF8A80)
private val ErrorContainer = Color(0xFF5C1F1B)
private val OnErrorContainer = Color(0xFFFFDAD6)

private fun tvColorScheme(accent: Color): ColorScheme {
    val onAccent = if (accent.luminance() > ON_ACCENT_LUMINANCE_THRESHOLD) Background else Color.White
    return darkColorScheme(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = lerp(accent, Background, PRIMARY_CONTAINER_BLEND),
        onPrimaryContainer = lerp(accent, Color.White, ON_PRIMARY_CONTAINER_BLEND),
        secondary = lerp(accent, Color.White, SECONDARY_BLEND),
        onSecondary = Background,
        secondaryContainer = lerp(accent, SurfaceVariant, SECONDARY_CONTAINER_BLEND),
        onSecondaryContainer = OnSurface,
        tertiary = lerp(accent, Color.White, TERTIARY_BLEND),
        background = Background,
        onBackground = OnSurface,
        surface = Surface,
        onSurface = OnSurface,
        surfaceVariant = SurfaceVariant,
        onSurfaceVariant = OnSurfaceVariant,
        inverseSurface = OnSurface,
        inverseOnSurface = Background,
        border = lerp(accent, Color.White, BORDER_BLEND),
        borderVariant = SurfaceVariant,
        error = Error,
        onError = Background,
        errorContainer = ErrorContainer,
        onErrorContainer = OnErrorContainer,
        scrim = Color.Black,
    )
}

private fun m3ColorScheme(tv: ColorScheme) = m3DarkColorScheme(
    primary = tv.primary,
    onPrimary = tv.onPrimary,
    primaryContainer = tv.primaryContainer,
    onPrimaryContainer = tv.onPrimaryContainer,
    secondary = tv.secondary,
    onSecondary = tv.onSecondary,
    secondaryContainer = tv.secondaryContainer,
    onSecondaryContainer = tv.onSecondaryContainer,
    tertiary = tv.tertiary,
    background = tv.background,
    onBackground = tv.onBackground,
    surface = tv.surface,
    onSurface = tv.onSurface,
    surfaceVariant = tv.surfaceVariant,
    onSurfaceVariant = tv.onSurfaceVariant,
    surfaceContainer = tv.surface,
    surfaceContainerHigh = tv.surfaceVariant,
    inverseSurface = tv.inverseSurface,
    inverseOnSurface = tv.inverseOnSurface,
    error = tv.error,
    onError = tv.onError,
    errorContainer = tv.errorContainer,
    onErrorContainer = tv.onErrorContainer,
    outline = tv.border,
    outlineVariant = tv.borderVariant,
    scrim = tv.scrim,
)
