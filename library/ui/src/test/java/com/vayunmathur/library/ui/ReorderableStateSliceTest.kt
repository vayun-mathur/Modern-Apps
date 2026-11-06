package com.vayunmathur.library.ui

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Slice 3/3: reorderable list/grid state machines — drag lifecycle, move
 * bookkeeping, translation math. Same package, so `internal` members apply.
 */
@RunWith(RobolectricTestRunner::class)
class ReorderableStateSliceTest {

    @get:Rule
    val rule = createComposeRule()

    @Test fun listStateDragLifecycle() {
        val moves = mutableListOf<Pair<Int, Int>>()
        val state = ReorderableLazyListState(
            listState = androidx.compose.foundation.lazy.LazyListState(),
            onMoveInternal = { from, to -> moves += from to to },
        )
        assertFalse(state.isAnyItemDragging)
        assertEquals(-1, state.draggingIndex)

        state.startDrag("k1", 2)
        assertTrue(state.isAnyItemDragging)
        assertEquals("k1", state.draggingKey)
        assertEquals(2, state.draggingIndex)

        state.move(4)
        assertEquals(listOf(2 to 4), moves)
        assertEquals(4, state.draggingIndex)

        // No-op moves: same index, negative index, or not dragging.
        state.move(4)
        state.move(-1)
        assertEquals(1, moves.size)

        state.stopDrag()
        assertFalse(state.isAnyItemDragging)
        assertEquals(-1, state.draggingIndex)
        state.move(0)
        assertEquals(1, moves.size)
    }

    @Test fun listStateTranslationZeroWhenIdle() {
        val state = ReorderableLazyListState(
            listState = androidx.compose.foundation.lazy.LazyListState(),
            onMoveInternal = { _, _ -> },
        )
        assertEquals(0f, state.draggingItemTranslation)
        state.startDrag("missing", 0)
        // Key not in layout: still zero rather than crashing.
        assertEquals(0f, state.draggingItemTranslation)
    }

    @Test fun gridStateDragLifecycle() {
        val moves = mutableListOf<Pair<Int, Int>>()
        val state = ReorderableLazyGridState(
            gridState = androidx.compose.foundation.lazy.grid.LazyGridState(),
            onMoveInternal = { from, to -> moves += from to to },
        )
        assertFalse(state.isAnyItemDragging)
        state.startDrag("g1", 1)
        assertTrue(state.isAnyItemDragging)
        assertEquals(1, state.draggingIndex)
        state.move(3)
        assertEquals(listOf(1 to 3), moves)
        state.stopDrag()
        assertFalse(state.isAnyItemDragging)
    }

    @Test fun gridStateTranslationZeroWhenIdle() {
        val state = ReorderableLazyGridState(
            gridState = androidx.compose.foundation.lazy.grid.LazyGridState(),
            onMoveInternal = { _, _ -> },
        )
        assertEquals(androidx.compose.ui.geometry.Offset.Zero, state.draggingItemTranslation)
    }

    @Test fun rememberReorderableStatesCompose() {
        var listMoves = 0
        rule.setContent {
            val list = androidx.compose.foundation.lazy.rememberLazyListState()
            val reorder = rememberReorderableLazyListState(list) { _, _ -> listMoves++ }
            androidx.compose.foundation.lazy.LazyColumn(state = list) {
                items(3) { Text("row $it") }
            }
            val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
            val gridReorder = rememberReorderableLazyGridState(grid) { _, _ -> }
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(2),
                state = grid,
            ) {
                items(4) { Text("tile $it") }
            }
            assertTrue(reorder.draggingIndex == -1)
            assertTrue(gridReorder.draggingIndex == -1)
        }
        rule.waitForIdle()
        assertEquals(0, listMoves)
    }
}
