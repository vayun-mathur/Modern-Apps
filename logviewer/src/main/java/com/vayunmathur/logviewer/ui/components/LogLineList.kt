package com.vayunmathur.logviewer.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.logviewer.domain.LogKind

/**
 * The log itself: one row per line, monospaced, pinch to resize.
 *
 * A lazy list rather than one big text block, which is not a style preference - a full logcat is
 * tens of thousands of lines, and a single `Text` would have to lay all of them out before the
 * first one appears. One row per line also means the font-size change on a pinch only re-measures
 * what is on screen.
 *
 * Lines arrive already flattened by the ViewModel, so scrolling never runs the formatting.
 */
@Composable
internal fun LogLineList(
    lines: List<String>,
    kind: LogKind,
    fontSizeSp: Float,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onZoom: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier = modifier.fillMaxSize().pinchToZoom(onZoom),
    ) {
        items(count = lines.size) { index ->
            Text(
                text = kind.displayLine(lines[index]),
                fontFamily = FontFamily.Monospace,
                fontSize = fontSizeSp.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Pinch-to-zoom that leaves scrolling alone.
 *
 * `detectTransformGestures` would consume single-pointer drags too, which is the whole gesture
 * budget of a list - the log would stop scrolling. Only multi-pointer events are consumed here, so
 * one finger scrolls and two resize.
 */
private fun Modifier.pinchToZoom(onZoom: (Float) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var event: PointerEvent
        do {
            event = awaitPointerEvent()
            if (event.changes.size >= 2) {
                val zoom = event.calculateZoom()
                if (zoom != 1f) {
                    onZoom(zoom)
                    event.changes.forEach { it.consume() }
                }
            }
        } while (event.changes.any { it.pressed })
    }
}
