package com.vayunmathur.library.ui

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Which horizontal swipe a [SwipeActionsBox] is currently showing. */
enum class SwipeActionSide { None, StartToEnd, EndToStart }

/**
 * A horizontal swipe-to-action row that only claims the gesture when it is
 * clearly horizontal.
 *
 * Material3's [SwipeToDismissBox] drives its `anchoredDraggable` off a plain
 * horizontal touch-slop, so a fast, near-vertical flick inside a scrolling list
 * can cross that slop and trigger an action instead of scrolling. This detector
 * instead waits until the accumulated drag is dominated by the X axis
 * ([horizontalBias] : requires |dx| > bias·|dy|) before it consumes anything;
 * until then the parent list keeps the gesture and scrolls normally. If the
 * vertical component wins first, it bails entirely.
 *
 * [onEndToStart] fires on a completed leftward swipe (e.g. delete — the caller
 * typically removes the row), [onStartToEnd] on a rightward swipe (e.g. mark
 * read — snaps back). [background] renders behind the content for the active side.
 *
 * [accessibilityActions] are the TalkBack-accessible equivalents of the swipe
 * gestures (same pattern as `TodoListRow`): swipe itself is undiscoverable and
 * unusable without vision, so callers mirror each swipe action here. Empty by
 * default — existing rows keep their current semantics until wired.
 */
@Composable
fun SwipeActionsBox(
    modifier: Modifier = Modifier,
    enableStartToEnd: Boolean = true,
    enableEndToStart: Boolean = true,
    onStartToEnd: () -> Unit = {},
    onEndToStart: () -> Unit = {},
    horizontalBias: Float = 2.5f,
    thresholdFraction: Float = 0.4f,
    accessibilityActions: List<CustomAccessibilityAction> = emptyList(),
    background: @Composable (SwipeActionSide) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var offsetX by remember { mutableFloatStateOf(0f) }
    var widthPx by remember { mutableFloatStateOf(0f) }

    Box(
        modifier
            .fillMaxWidth()
            .then(
                if (accessibilityActions.isNotEmpty()) {
                    Modifier.semantics(mergeDescendants = true) {
                        customActions = accessibilityActions
                    }
                } else {
                    Modifier
                },
            )
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(enableStartToEnd, enableEndToStart) {
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var claimed = false
                    var totalDx = 0f
                    var totalDy = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val dx = change.position.x - change.previousPosition.x
                        val dy = change.position.y - change.previousPosition.y
                        totalDx += dx
                        totalDy += dy
                        if (!claimed) {
                            // Vertical wins once past slop -> let the parent list scroll.
                            if (abs(totalDy) > touchSlop && abs(totalDy) >= abs(totalDx)) break
                            // Claim only when the drag is clearly horizontal.
                            if (abs(totalDx) > touchSlop && abs(totalDx) > abs(totalDy) * horizontalBias) {
                                claimed = true
                            }
                        }
                        if (claimed) {
                            val next = offsetX + dx
                            val allowed = (next > 0f && enableStartToEnd) || (next < 0f && enableEndToStart) || next == 0f
                            if (allowed) {
                                offsetX = next
                                change.consume()
                            }
                        }
                    }
                    if (claimed) {
                        val threshold = widthPx * thresholdFraction
                        when {
                            offsetX <= -threshold && enableEndToStart -> {
                                onEndToStart()
                                scope.launch { animate(offsetX, -widthPx) { v, _ -> offsetX = v } }
                            }
                            offsetX >= threshold && enableStartToEnd -> {
                                onStartToEnd()
                                scope.launch { animate(offsetX, 0f) { v, _ -> offsetX = v } }
                            }
                            else -> scope.launch { animate(offsetX, 0f) { v, _ -> offsetX = v } }
                        }
                    }
                }
            }
    ) {
        val side = when {
            offsetX > 0f -> SwipeActionSide.StartToEnd
            offsetX < 0f -> SwipeActionSide.EndToStart
            else -> SwipeActionSide.None
        }
        background(side)
        Box(Modifier.offset { IntOffset(offsetX.roundToInt(), 0) }) { content() }
    }
}
