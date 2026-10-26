package com.vayunmathur.contacts.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconStar
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.isCompactHeight
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Draggable section scrubber for the contact list: a pill on the right edge whose y tracks the
 * scroll fraction, showing the section on screen (★ for favorites, else the letter), with a
 * drag moving the list to the matching position. Always visible.
 *
 * The pill is a Material 3 `secondaryContainer` surface with a `primary` border, so it
 * stays distinguishable from the `surfaceVariant` contact rows in both themes instead of a
 * fixed black/white.
 *
 * [sectionIndex] maps each section key (★, then letters) to its header's flat
 * item index, in list order; the pill position and label derive from the same
 * [LazyListState] so the two never disagree.
 *
 * This must be hosted in [com.vayunmathur.library.ui.LazyListScaffold]'s `overlay` slot (a box
 * exactly the list's size), so the pill travels only along the actual list - hosted full-screen
 * it would run up behind the app bar and down behind the navigation bar.
 */
@Composable
internal fun ContactSectionScroller(
    state: LazyListState,
    sectionIndex: Map<String, Int>,
    itemCount: Int,
    modifier: Modifier = Modifier,
) {
    if (sectionIndex.size < 2) return
    val scope = rememberCoroutineScope()
    var trackHeightPx by remember { mutableIntStateOf(0) }
    var thumbHeightPx by remember { mutableIntStateOf(0) }
    // While dragging, the pill follows the finger exactly (absolute progress along the track)
    // and the list is driven to match - instead of the pill trailing the list state, which
    // lagged behind the finger and let the gesture feel like it kept getting lost.
    var dragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    var scrollJob by remember { mutableStateOf<Job?>(null) }

    val keys = sectionIndex.keys.toList()
    // The overlay box is the full scaffold content size, including the area behind the app bar and
    // the navigation bar (the list draws under both and insets its *content* instead). Bound the
    // pill's travel to the list's actual viewport - between the top/bottom content padding - so it
    // runs only along the side of the visible list.
    val info = state.layoutInfo
    val topInsetPx = info.beforeContentPadding
    val bottomInsetPx = info.afterContentPadding
    val listViewportPx = (trackHeightPx - topInsetPx - bottomInsetPx).coerceAtLeast(1)
    val travelPx = (listViewportPx - thumbHeightPx).coerceAtLeast(1).toFloat()
    val latestTravelPx = rememberUpdatedState(travelPx)
    val progress = if (dragging) dragProgress else progressOf(state, itemCount)
    val thumbOffsetPx = topInsetPx + progress * travelPx
    val currentKey = keys.lastOrNull { sectionIndex[it]!! <= state.firstVisibleItemIndex } ?: keys.first()

    Box(
        modifier.fillMaxSize(),
    ) {
        // Full-height grab strip on the right edge: starting a vertical drag anywhere in it
        // scrubs the list, so a finger that drifts off the small pill mid-drag no longer falls
        // through to the list itself and stalls the scrub. Horizontal gestures pass through to
        // the list untouched - only vertical drags are claimed.
        //
        // Short (landscape) tracks shrink the pill and clear less space above the FAB,
        // so the pill's travel stays usable when the viewport is only ~300dp tall.
        val shortTrack = isCompactHeight()
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .fillMaxHeight()
                .width(if (shortTrack) 48.dp else 64.dp)
                // End the grab strip above the FAB (56dp button + 16dp margin + room), and
                // measure the track here so the pill's travel ends there too.
                .padding(bottom = if (shortTrack) 72.dp else 88.dp)
                .onSizeChanged { trackHeightPx = it.height }
                // Stable keys: the travel distance is read through updated state, so a
                // recomposition mid-drag never restarts the detector and drops the gesture.
                .pointerInput(itemCount) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragging = true
                            dragProgress = progressOf(state, itemCount)
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            val p = (dragProgress + dragAmount / latestTravelPx.value).coerceIn(0f, 1f)
                            dragProgress = p
                            val (target, targetOffset) = scrollTargetFor(p, state, itemCount)
                            scrollJob?.cancel()
                            scrollJob = scope.launch { runCatching { state.scrollToItem(target, targetOffset) } }
                        },
                    )
                },
        ) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, thumbOffsetPx.roundToInt()) }
                    .padding(end = 8.dp)
                    .onSizeChanged { thumbHeightPx = it.height },
            ) {
                // Fixed size so the star/letter swap never resizes the pill mid-drag (a size
                // change would shift the travel mapping under the finger).
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(if (shortTrack) 40.dp else 48.dp),
                ) {
                    if (currentKey == FAVORITES_KEY) {
                        IconStar()
                    } else {
                        Text(
                            text = currentKey,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}

/** Favorites section key for the [ContactSectionScroller]. */
internal const val FAVORITES_KEY = "favorites"

/**
 * Scroll fraction 0 (top) to 1 (last item bottom at the viewport bottom):
 * scrolled pixels over the true scrollable distance, so the pill hits the
 * bottom exactly at the list end instead of running ahead of it.
 */
internal fun progressOf(state: LazyListState, itemCount: Int): Float {
    val info = state.layoutInfo
    if (itemCount <= 0) return 0f
    val viewportH = (info.viewportEndOffset - info.viewportStartOffset).coerceAtLeast(1)
    val avgH = info.visibleItemsInfo.map { it.size }.average().takeIf { !it.isNaN() }?.toFloat() ?: 0f
    if (avgH <= 0f) return 0f
    val total = (avgH * itemCount - viewportH).coerceAtLeast(0f)
    if (total <= 0f) return 0f
    val scrolled = state.firstVisibleItemIndex * avgH + state.firstVisibleItemScrollOffset
    return (scrolled / total).coerceIn(0f, 1f)
}

/**
 * Inverse of [progressOf]: the `(index, scrollOffset)` whose scroll fraction equals [progress,
 * so driving the list from a finger position lands where the pill is instead of snapping on
 * release.
 */
internal fun scrollTargetFor(progress: Float, state: LazyListState, itemCount: Int): Pair<Int, Int> {
    val info = state.layoutInfo
    if (itemCount <= 0) return 0 to 0
    val viewportH = (info.viewportEndOffset - info.viewportStartOffset).coerceAtLeast(1)
    val avgH = info.visibleItemsInfo.map { it.size }.average().takeIf { !it.isNaN() }?.toFloat() ?: 0f
    if (avgH <= 0f) return 0 to 0
    val total = (avgH * itemCount - viewportH).coerceAtLeast(0f)
    if (total <= 0f) return 0 to 0
    val scrolled = progress.coerceIn(0f, 1f) * total
    val index = (scrolled / avgH).toInt().coerceIn(0, itemCount - 1)
    val offset = (scrolled - index * avgH).toInt().coerceAtLeast(0)
    return index to offset
}
