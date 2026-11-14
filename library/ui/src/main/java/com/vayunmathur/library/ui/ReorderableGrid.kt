package com.vayunmathur.library.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.toOffset
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Stable
/** Drag-reorder state for a lazy grid: the dragged key, its offset and the move callback. */
class ReorderableLazyGridState(
    val gridState: LazyGridState,
    private val onMoveInternal: (from: Int, to: Int) -> Unit,
) {
    var draggingKey by mutableStateOf<Any?>(null)
        internal set
    var isAnyItemDragging by mutableStateOf(false)
        internal set
    var draggingIndex by mutableIntStateOf(-1)
        internal set

    /** Accumulated finger delta (px) since the drag started. */
    internal var draggingItemOffset by mutableStateOf(Offset.Zero)
    /** Layout offset (px) of the dragged item at drag start. */
    internal var draggingItemInitialOffset = Offset.Zero

    /**
     * Translation (px) that keeps the dragged tile under the finger while the grid reflows it into
     * new slots. Both axes, unlike the list's, because a grid moves sideways too. Read it from a
     * `graphicsLayer { }` on the dragged item; [Offset.Zero] when nothing is dragging.
     */
    val draggingItemTranslation: Offset
        get() {
            val key = draggingKey ?: return Offset.Zero
            val info = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
                ?: return Offset.Zero
            return draggingItemInitialOffset + draggingItemOffset - info.offset.toOffset()
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

    /**
     * Re-evaluate which tile the dragged tile's centre is over and swap if needed, reporting
     * whether it swapped.
     *
     * Tested by position against the live layout rather than by counting indices, so the number of
     * columns never enters the arithmetic and a diagonal drag lands where it looks like it should.
     * [itemCount] bounds the reorderable prefix of the grid: a grid whose last slot is a footer
     * must not have items moved into it.
     */
    internal fun updateDragTarget(itemCount: Int): Boolean {
        val key = draggingKey ?: return false
        val items = gridState.layoutInfo.visibleItemsInfo
        val dragging = items.firstOrNull { it.key == key } ?: return false
        val centre = draggingItemInitialOffset + draggingItemOffset +
            Offset(dragging.size.width / 2f, dragging.size.height / 2f)
        val target = items.firstOrNull {
            it.key != key && it.index < itemCount &&
                centre.x >= it.offset.x && centre.x <= it.offset.x + it.size.width &&
                centre.y >= it.offset.y && centre.y <= it.offset.y + it.size.height
        } ?: return false
        if (target.index == dragging.index) return false
        // From the layout rather than the stored index, so a move the caller declined or clamped
        // cannot leave the two drifting apart for the rest of the drag.
        draggingIndex = dragging.index
        move(target.index)
        return true
    }

    /**
     * One tick of edge auto-scroll: if the dragged tile is within a row of the viewport's top or
     * bottom edge, scroll that way so the drag can continue past what is currently visible.
     */
    internal suspend fun autoScrollStep(itemCount: Int) {
        val key = draggingKey ?: return
        val layout = gridState.layoutInfo
        val dragging = layout.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        val top = draggingItemInitialOffset.y + draggingItemOffset.y
        val bottom = top + dragging.size.height
        val edge = dragging.size.height.toFloat().coerceAtLeast(64f)
        val step = 24f
        val delta = when {
            top < layout.viewportStartOffset + edge -> -step
            bottom > layout.viewportEndOffset - edge -> step
            else -> 0f
        }
        if (delta != 0f) {
            gridState.scrollBy(delta)
            updateDragTarget(itemCount)
        }
    }
}

@Composable
fun rememberReorderableLazyGridState(
    gridState: LazyGridState,
    onMove: (from: ReorderableItemInfo, to: ReorderableItemInfo) -> Unit,
): ReorderableLazyGridState {
    return remember(gridState) {
        ReorderableLazyGridState(gridState) { from, to ->
            onMove(ReorderableItemInfo(from), ReorderableItemInfo(to))
        }
    }
}

@Composable
fun ReorderableItem(
    reorderState: ReorderableLazyGridState,
    key: Any,
    modifier: Modifier = Modifier,
    content: @Composable (isDragging: Boolean) -> Unit,
) {
    val isDragging = reorderState.draggingKey == key
    androidx.compose.foundation.layout.Box(modifier = modifier) {
        content(isDragging)
    }
}

/**
 * A grid tile that can be picked up and reordered, and optionally flicked sideways for something
 * else, from one gesture that decides which by **intent at the start** rather than by what the
 * finger happened to be doing at release.
 *
 * | Branch | Entered by | Consumes |
 * | --- | --- | --- |
 * | Reorder | the long-press timeout expiring | yes, from the pick-up |
 * | [horizontalFlick] | slop crossed with the travel clearly horizontal | yes, from the claim |
 * | nothing | slop crossed vertically, or released first | **no** |
 *
 * Consuming nothing until a branch commits is the load-bearing part: it is why a tap still reaches
 * the tile's own `onClick` and why a vertical fling still reaches the grid's scroll. The two are
 * raced against each other rather than resolved by release velocity, because a reorder is very
 * often released mid-motion — deciding at release would fire the sideways action on drags the user
 * meant as a move.
 *
 * The dominant-axis test is [SwipeActionsBox]'s: horizontal has to beat vertical by
 * [horizontalBias] to claim, and vertical winning bails out entirely. A plain slop crossing is not
 * enough, because a fast diagonal crosses it on both axes at once.
 *
 * Apply this to a node **inside** whatever owns the tile's click, not to the tile's own root: on
 * the `Main` pass the inner node is offered the event first, which is what lets it consume the UP
 * before a surrounding `clickable` can read it as a tap.
 *
 * The same ordering is what keeps a button *inside* the tile — a close or delete affordance — out of
 * the drag: it is deeper still, so its `clickable` consumes the DOWN before this ever sees it, and
 * the DOWN is required unconsumed here. Without that a long press on the button would pick the
 * whole tile up as well.
 *
 * [itemCount] is how many leading items are reorderable, so a grid ending in a footer does not
 * accept a tile dropped onto it. Give the dragged item root
 * `Modifier.zIndex(1f).graphicsLayer { }` reading
 * [ReorderableLazyGridState.draggingItemTranslation] while it is dragging, and `itemMotion()`
 * otherwise so the rest glide aside.
 */
fun Modifier.reorderGridDragHandle(
    reorderState: ReorderableLazyGridState,
    key: Any,
    itemCount: Int,
    onDragStarted: () -> Unit = {},
    onSwap: () -> Unit = {},
    onDragStopped: () -> Unit = {},
    horizontalFlick: HorizontalFlick? = null,
    horizontalBias: Float = 2.5f,
): Modifier = composed {
    val scope = rememberCoroutineScope()
    var scrollJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // Read through `rememberUpdatedState` rather than keyed into `pointerInput`: a fresh lambda or
    // a changed count each recomposition would otherwise restart the detector mid-drag.
    val count by rememberUpdatedState(itemCount)
    val flick by rememberUpdatedState(horizontalFlick)
    val bias by rememberUpdatedState(horizontalBias)
    val dragStarted by rememberUpdatedState(onDragStarted)
    val swapped by rememberUpdatedState(onSwap)
    val dragStopped by rememberUpdatedState(onDragStopped)

    pointerInput(reorderState, key) {
        val slop = viewConfiguration.touchSlop
        val longPressMillis = viewConfiguration.longPressTimeoutMillis

        awaitEachGesture {
            // Unconsumed only: a DOWN already claimed by a deeper node belongs to that node for the
            // whole gesture, so neither branch may start on it.
            val down = awaitFirstDown(requireUnconsumed = true)
            val press = awaitGridPress(down, slop, longPressMillis)

            if (!press.longPressed) {
                val branch = flick
                if (press.moved && branch != null && branch.enabled()) {
                    trackHorizontalFlick(down, slop, bias, branch, press)
                }
                return@awaitEachGesture
            }

            val info = reorderState.gridState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.key == key } ?: return@awaitEachGesture
            reorderState.startDrag(key, info.index)
            reorderState.draggingItemInitialOffset = info.offset.toOffset()
            reorderState.draggingItemOffset = Offset.Zero
            dragStarted()

            scrollJob?.cancel()
            scrollJob = scope.launch {
                while (isActive && reorderState.draggingKey != null) {
                    reorderState.autoScrollStep(count)
                    delay(16)
                }
            }

            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                change.consume()
                reorderState.draggingItemOffset += change.position - change.previousPosition
                if (reorderState.updateDragTarget(count)) swapped()
                if (!change.pressed) break
            }

            scrollJob?.cancel()
            scrollJob = null
            reorderState.stopDrag()
            reorderState.draggingItemOffset = Offset.Zero
            dragStopped()
        }
    }
}

// GridPress + awaitGridPress + trackHorizontalFlick live in ReorderableGridGestures.kt
