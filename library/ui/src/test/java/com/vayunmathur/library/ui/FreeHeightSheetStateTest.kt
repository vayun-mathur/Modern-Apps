package com.vayunmathur.library.ui

import androidx.compose.material3.SheetValue
import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Slice 3/3: [FreeHeightSheetState] height state machine — measure/expand/
 * partial/hide/grow, bounds and content-cap learning. Same package, so
 * `internal` members apply.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FreeHeightSheetStateTest {

    private val scope = TestScope(UnconfinedTestDispatcher())

    /**
     * [Animatable.animateTo] needs frames; fire them immediately, each one
     * 16ms after the last. The frame time must advance: a clock that always
     * reports 0 leaves every spring/tween at play-time 0 forever, and the
     * animation loop spins without ever finishing.
     */
    private class ImmediateFrameClock : MonotonicFrameClock {
        private var frameTimeNanos = 0L

        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            val result = onFrame(frameTimeNanos)
            frameTimeNanos += 16_666_667L
            return result
        }
    }

    private suspend fun animated(block: suspend () -> Unit) =
        withContext(ImmediateFrameClock()) { block() }

    @Test fun initialPartiallyExpandedRestsAtPeek() = scope.runTest {
        val state = FreeHeightSheetState(SheetValue.PartiallyExpanded)
        state.onMeasured(peek = 100f, expanded = 600f)
        assertEquals(100f, state.offsetPx)
        assertEquals(100f, state.liftPx)
    }

    @Test fun initialHiddenRestsAtZero() = scope.runTest {
        val state = FreeHeightSheetState(SheetValue.Hidden)
        state.onMeasured(peek = 100f, expanded = 600f)
        assertEquals(0f, state.offsetPx)
        assertEquals(0f, state.liftPx)
    }

    @Test fun initialExpandedRestsAtTop() = scope.runTest {
        val state = FreeHeightSheetState(SheetValue.Expanded)
        state.onMeasured(peek = 100f, expanded = 600f)
        assertEquals(600f, state.offsetPx)
    }

    @Test fun expandAndPartialExpandMoveHeight() = scope.runTest {
        val state = FreeHeightSheetState(SheetValue.PartiallyExpanded)
        state.onMeasured(peek = 100f, expanded = 600f)
        animated { state.expand() }
        assertEquals(600f, state.offsetPx)
        animated { state.partialExpand() }
        assertEquals(100f, state.offsetPx)
    }

    @Test fun hideDropsToZero() = scope.runTest {
        val state = FreeHeightSheetState(SheetValue.PartiallyExpanded)
        state.onMeasured(peek = 100f, expanded = 600f)
        animated { state.hide() }
        assertEquals(0f, state.offsetPx)
    }

    @Test fun growByAbsorbsWithinBounds() = scope.runTest {
        val state = FreeHeightSheetState(SheetValue.PartiallyExpanded)
        state.onMeasured(peek = 100f, expanded = 600f)
        val absorbed = state.growBy(150f, this)
        assertEquals(150f, absorbed)
        testScheduler.advanceUntilIdle()
        assertEquals(250f, state.offsetPx)
    }

    @Test fun growByClampsAtExpanded() = scope.runTest {
        val state = FreeHeightSheetState(SheetValue.PartiallyExpanded)
        state.onMeasured(peek = 100f, expanded = 600f)
        val absorbed = state.growBy(10_000f, this)
        assertEquals(500f, absorbed)
    }

    @Test fun growByIgnoresHiddenSheet() = scope.runTest {
        val state = FreeHeightSheetState(SheetValue.Hidden)
        state.onMeasured(peek = 100f, expanded = 600f)
        assertEquals(0f, state.growBy(50f, this))
    }

    @Test fun contentCapLowersCeiling() = scope.runTest {
        val state = FreeHeightSheetState(SheetValue.Expanded)
        state.onMeasured(peek = 100f, expanded = 600f)
        assertEquals(600f, state.offsetPx)
        state.onContentHeight(contentHeight = 300f, offered = 600f, scope = this)
        testScheduler.advanceUntilIdle()
        assertEquals(300f, state.offsetPx)
        assertEquals(300f, state.expandedHeightPx)
    }

    @Test fun resetContentCapRestoresCeiling() = scope.runTest {
        val state = FreeHeightSheetState(SheetValue.PartiallyExpanded)
        state.onMeasured(peek = 100f, expanded = 600f)
        state.onContentHeight(contentHeight = 300f, offered = 600f, scope = this)
        state.resetContentCap(peek = 100f)
        animated { state.expand() }
        assertEquals(600f, state.offsetPx)
    }

    @Test fun flingHiddenSheetReturnsVelocity() = scope.runTest {
        val state = FreeHeightSheetState(SheetValue.Hidden)
        state.onMeasured(peek = 100f, expanded = 600f)
        assertEquals(500f, state.flingBy(500f))
    }
}
