package com.vayunmathur.library.util

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Live "are we online?" state for Compose. Starts [ConnectivityMonitor] on first use.
 *
 * Requires `<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>`.
 */
@Composable
fun rememberIsOnline(): State<Boolean> {
    val context = LocalContext.current
    remember(context) { ConnectivityMonitor.start(context); true }
    return ConnectivityMonitor.isOnline.collectAsState()
}

/**
 * Drop-in banner shown while offline. Adopting an offline indicator is then ~2 lines:
 * `val online by rememberIsOnline(); OfflineBanner(online)`.
 *
 * The warning glyph is drawn with Canvas (not `Icon()`) so this file can live
 * in `:library:network` next to [ConnectivityMonitor] instead of dragging a
 * `:library:network` dependency (and its `ACCESS_NETWORK_STATE`) into
 * `:library:ui`, which every app consumes (#884). Icons stay centralized in
 * `:library:ui` `Icons.kt`; this one glyph is the documented exception.
 */
@Composable
fun OfflineBanner(online: Boolean, modifier: Modifier = Modifier) {
    if (online) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OfflineWarningGlyph(tint = MaterialTheme.colorScheme.onErrorContainer)
        Text(
            text = "No internet connection",
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * Wraps a screen so a full-width offline banner slides in above it whenever validated internet is
 * lost, and the wrapped content fills the rest. Adopting the offline indicator in an app is then a
 * one-line wrap around the existing top-level navigation:
 *
 * ```
 * DynamicTheme {
 *     OfflineAware {
 *         Navigation(viewModel)
 *     }
 * }
 * ```
 *
 * The banner carries [statusBarsPadding] so it clears the status bar under edge-to-edge. While the
 * banner is visible it has already absorbed the status-bar inset, so the content below it must not
 * apply that inset a second time: `statusBarsPadding()` (and the Material3 top app bar's default
 * `windowInsets`, which is also the status bars) do not consume the inset, and a wrapped
 * `AppScaffold` would otherwise add a second, empty status-bar-height gap between the banner and
 * its top app bar. Consuming [WindowInsets.statusBars] for the content while offline makes the
 * app bar sit directly beneath the banner; while online nothing is consumed and the content
 * manages its own insets exactly as before.
 */
@Composable
fun OfflineAware(content: @Composable () -> Unit) {
    val online by rememberIsOnline()
    Column(Modifier.fillMaxSize()) {
        OfflineBanner(online, Modifier.statusBarsPadding())
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(if (online) Modifier else Modifier.consumeWindowInsets(WindowInsets.statusBars)),
        ) { content() }
    }
}

/** Warning triangle with an exclamation bar, drawn to match the `IconWarning` look. */
@Composable
private fun OfflineWarningGlyph(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val barColor = MaterialTheme.colorScheme.errorContainer
    Canvas(modifier = modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val triangle = Path().apply {
            moveTo(w / 2f, h * 0.08f)
            lineTo(w * 0.94f, h * 0.88f)
            lineTo(w * 0.06f, h * 0.88f)
            close()
        }
        drawPath(triangle, tint)
        val barTop = h * 0.34f
        val barBottom = h * 0.62f
        val barHalfWidth = w * 0.035f
        drawRect(
            color = barColor,
            topLeft = Offset(w / 2f - barHalfWidth, barTop),
            size = Size(barHalfWidth * 2f, barBottom - barTop),
        )
        drawCircle(
            color = barColor,
            radius = barHalfWidth,
            center = Offset(w / 2f, h * 0.72f),
        )
    }
}
