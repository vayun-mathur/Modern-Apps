package com.vayunmathur.library.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foundation-based replacement for sh.calvin.reorderable 3.1.0.
 * Implements minimal API surface used in this repo:
 * - rememberReorderableLazyListState / rememberReorderableLazyGridState
 * - ReorderableItem (list + grid)
 * - Modifier.draggableHandle / longPressDraggableHandle
 *
 * [draggableHandle] is iterative: each drag gesture chunk that exceeds a small
 * threshold swaps the dragged item with its neighbour in the drag direction.
 * This preserves the previous position-interpolation logic in NotesListPage etc
 * without requiring exact pixel-to-index mapping. It is a list-only API: counting
 * indices cannot express a grid, where a vertical move is a whole row rather than
 * one step. Grids use [reorderGridDragHandle], which hit-tests against the live
 * layout instead.
 */

data class ReorderableItemInfo(val index: Int, val key: Any = Unit)

/** Drag-reorder state for a lazy list: the dragged key, its offset and the move callback. */
@Stable
class ReorderableLazyListState(
    val listState: LazyListState,
    private val onMoveInternal: (from: Int, to: Int) -> Unit,
) {
    var draggingKey by mutableStateOf<Any?>(null)
        internal set
    var isAnyItemDragging by mutableStateOf(false)
        internal set
    var draggingIndex by mutableIntStateOf(-1)
        internal set

    /** Accumulated finger delta (px) since the drag started. */
    internal var draggingItemOffset by mutableFloatStateOf(0f)
    /** Layout offset (px) of the dragged item at drag start. */
    internal var draggingItemInitialOffset = 0f

    /**
     * Y translation (px) that keeps the dragged item under the finger while the list
     * reflows it to new slots. Read this from a `graphicsLayer { translationY = ... }`
     * on the dragged item (see [reorderDragHandle]). 0 when nothing is dragging.
     */
    val draggingItemTranslation: Float
        get() {
            val key = draggingKey ?: return 0f
            val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return 0f
            return draggingItemInitialOffset + draggingItemOffset - info.offset
        }

    fun startDrag(key: Any, index: Int) {
        draggingKey = key
        draggingIndex = index
        isAnyItemDragging = true
    }

    fun stopDrag() {
        draggingKey = null
        draggingIndex = -1
        isAnyItemDragging = false
    }

    fun move(toIndex: Int) {
        val from = draggingIndex
        if (from == -1 || toIndex == from) return
        if (toIndex < 0) return
        onMoveInternal(from, toIndex)
        draggingIndex = toIndex
    }

    /** Re-evaluate which item the dragged item's centre is over and swap if needed. */
    internal fun updateDragTarget() {
        val key = draggingKey ?: return
        val items = listState.layoutInfo.visibleItemsInfo
        val dragging = items.firstOrNull { it.key == key } ?: return
        val center = draggingItemInitialOffset + draggingItemOffset + dragging.size / 2f
        val target = items.firstOrNull {
            it.key != key && center >= it.offset && center <= it.offset + it.size
        }
        if (target != null && target.index != dragging.index) move(target.index)
    }

    /**
     * One tick of edge auto-scroll: if the dragged item is within a row of the
     * viewport's top/bottom edge, scroll the list in that direction so the user can
     * keep dragging past what's currently visible, then re-check for a swap.
     */
    internal suspend fun autoScrollStep() {
        val key = draggingKey ?: return
        val layout = listState.layoutInfo
        val dragging = layout.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        val top = draggingItemInitialOffset + draggingItemOffset
        val bottom = top + dragging.size
        val edge = dragging.size.toFloat().coerceAtLeast(64f)
        val step = 24f
        val delta = when {
            top < layout.viewportStartOffset + edge -> -step
            bottom > layout.viewportEndOffset - edge -> step
            else -> 0f
        }
        if (delta != 0f) {
            listState.scrollBy(delta)
            updateDragTarget()
        }
    }
}

@Composable
fun rememberReorderableLazyListState(
    lazyListState: LazyListState,
    onMove: (from: ReorderableItemInfo, to: ReorderableItemInfo) -> Unit,
): ReorderableLazyListState {
    return remember(lazyListState) {
        ReorderableLazyListState(lazyListState) { from, to ->
            onMove(ReorderableItemInfo(from), ReorderableItemInfo(to))
        }
    }
}

@Composable
fun ReorderableItem(
    reorderState: ReorderableLazyListState,
    key: Any,
    modifier: Modifier = Modifier,
    content: @Composable (isDragging: Boolean) -> Unit,
) {
    val isDragging = reorderState.draggingKey == key
    androidx.compose.foundation.layout.Box(modifier = modifier) {
        content(isDragging)
    }
}

private const val DRAG_THRESHOLD = 18f

fun Modifier.draggableHandle(
    reorderState: ReorderableLazyListState,
    key: Any,
    index: Int,
    onDragStarted: suspend CoroutineScope.(medium: Any) -> Unit = {},
    onDragStopped: suspend CoroutineScope.() -> Unit = {},
): Modifier = composed {
    val scope = rememberCoroutineScope()
    pointerInput(key, index) {
        detectDragGesturesAfterLongPress(
            onDragStart = {
                reorderState.startDrag(key, index)
                scope.launch { onDragStarted(Unit) }
            },
            onDragEnd = {
                reorderState.stopDrag()
                scope.launch { onDragStopped() }
            },
            onDragCancel = {
                reorderState.stopDrag()
                scope.launch { onDragStopped() }
            },
            onDrag = { change, dragAmount ->
                change.consume()
                if (reorderState.draggingIndex == -1) return@detectDragGesturesAfterLongPress
                val dy = dragAmount.y
                if (dy > DRAG_THRESHOLD) {
                    reorderState.move(reorderState.draggingIndex + 1)
                } else if (dy < -DRAG_THRESHOLD) {
                    reorderState.move(reorderState.draggingIndex - 1)
                }
            }
        )
    }
}

fun Modifier.longPressDraggableHandle(
    reorderState: ReorderableLazyListState,
    key: Any,
    index: Int,
): Modifier = draggableHandle(reorderState, key, index)

/**
 * Immediate (no long-press) drag handle with **true finger-following**: while you
 * drag, the item translates 1:1 with your finger via
 * [ReorderableLazyListState.draggingItemTranslation], and it swaps with whichever
 * item its centre is currently over (using the live [LazyListState] layout). The
 * gesture is consumed so it never fights the list's own vertical scroll.
 *
 * Apply to a small handle element, and give the dragged item root
 * `Modifier.zIndex(1f).graphicsLayer { translationY = state.draggingItemTranslation }`
 * while it's dragging, and `Modifier.animateItem()` otherwise (so the others glide).
 */
fun Modifier.reorderDragHandle(
    reorderState: ReorderableLazyListState,
    key: Any,
    onDragStarted: () -> Unit = {},
    onDragStopped: () -> Unit = {},
): Modifier = composed {
    val scope = rememberCoroutineScope()
    var scrollJob by remember { mutableStateOf<Job?>(null) }
    pointerInput(key) {
        detectDragGestures(
            onDragStart = {
                reorderState.listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.key == key }
                    ?.let { info ->
                        reorderState.startDrag(key, info.index)
                        reorderState.draggingItemInitialOffset = info.offset.toFloat()
                        reorderState.draggingItemOffset = 0f
                        onDragStarted()
                        // Keep scrolling while the dragged item is held near an edge.
                        scrollJob?.cancel()
                        scrollJob = scope.launch {
                            while (isActive && reorderState.draggingKey != null) {
                                reorderState.autoScrollStep()
                                delay(16)
                            }
                        }
                    }
            },
            onDragEnd = {
                scrollJob?.cancel(); scrollJob = null
                reorderState.stopDrag()
                reorderState.draggingItemOffset = 0f
                onDragStopped()
            },
            onDragCancel = {
                scrollJob?.cancel(); scrollJob = null
                reorderState.stopDrag()
                reorderState.draggingItemOffset = 0f
                onDragStopped()
            },
            onDrag = { change, dragAmount ->
                change.consume()
                if (reorderState.draggingKey != null) {
                    reorderState.draggingItemOffset += dragAmount.y
                    reorderState.updateDragTarget()
                }
            },
        )
    }
}
