package com.vayunmathur.library.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Slice 3/3 round 5: activity shell + reorderable state edge cases. */
@RunWith(RobolectricTestRunner::class)
class Slice3Round5Test {
    @get:Rule val rule = createComposeRule()

    class ProbeActivity : MaActivity() {
        var beforeContentCalls = 0
        @androidx.compose.runtime.Composable
        override fun Content() { Text("probe-content") }
        override fun onCreateBeforeContent() { beforeContentCalls++ }
    }

    @Test fun maActivityShowsContentAndRunsHook() {
        val controller = org.robolectric.Robolectric.buildActivity(ProbeActivity::class.java)
        val activity = controller.create().get()
        assertEquals(1, activity.beforeContentCalls)
        controller.start().resume()
        controller.visible()
        assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, activity.lifecycle.currentState)
        assertTrue(activity.window != null)
    }

    @Test fun reorderMoveNoOpsOnIdleAndSameIndex() {
        val moves = mutableListOf<Pair<Int, Int>>()
        val state = ReorderableLazyListState(LazyListState()) { f, t -> moves += f to t }
        state.move(3)
        assertTrue(moves.isEmpty())
        state.startDrag("k", 2)
        state.move(2)
        assertTrue(moves.isEmpty())
        assertEquals(2, state.draggingIndex)
        state.move(-1)
        assertTrue(moves.isEmpty())
        state.move(5)
        assertEquals(listOf(2 to 5), moves)
        assertEquals(5, state.draggingIndex)
        state.stopDrag()
        assertFalse(state.isAnyItemDragging)
        assertEquals(-1, state.draggingIndex)
    }

    @Test fun reorderItemInfoDefaults() {
        val info = ReorderableItemInfo(3)
        assertEquals(3, info.index)
        assertEquals(Unit, info.key)
        assertEquals(ReorderableItemInfo(3, "a"), ReorderableItemInfo(3, "a"))
    }

    @Test fun composeRuleSmokeForReorderRemember() {
        rule.setContent {
            val list = androidx.compose.foundation.lazy.rememberLazyListState()
            rememberReorderableLazyListState(list) { _, _ -> }
            Text("reorder-ok")
        }
        rule.onNodeWithText("reorder-ok").assertIsDisplayed()
    }
}
