package com.vayunmathur.games.sudoku.ui

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.sudoku.R
import com.vayunmathur.games.sudoku.data.BoardSize
import com.vayunmathur.games.sudoku.data.Difficulty
import com.vayunmathur.games.sudoku.data.SudokuGameState
import com.vayunmathur.games.sudoku.domain.SudokuGenerator
import com.vayunmathur.games.sudoku.platform.SudokuActions
import com.vayunmathur.games.sudoku.platform.SudokuUiState
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** The 1 Hz clock only ticks while the screen is resumed, running, and not covered by a dialog. */
@RunWith(RobolectricTestRunner::class)
class GameClockTest {

    @get:Rule val rule = createComposeRule()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun ticksOnlyWhileResumedAndRunning() {
        val owner = TestOwner().apply { registry.currentState = Lifecycle.State.RESUMED }
        var running by mutableStateOf(true)
        var ticks = 0
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                GameClock(running = running, onTick = { ticks++ })
            }
        }

        rule.mainClock.advanceTimeBy(3_500)
        rule.waitForIdle()
        assertEquals(3, ticks, "ticks once a second while resumed")

        // Backgrounded (or behind a system dialog): ON_PAUSE drops the state to STARTED.
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        rule.mainClock.advanceTimeBy(10_000)
        rule.waitForIdle()
        assertEquals(3, ticks, "no ticks while paused")

        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        rule.mainClock.advanceTimeBy(1_500)
        rule.waitForIdle()
        assertEquals(4, ticks, "resumes after ON_RESUME")

        // The flag reaches the clock on the next frame; count from there.
        rule.runOnIdle { running = false }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        val stoppedAt = ticks
        rule.mainClock.advanceTimeBy(5_000)
        rule.waitForIdle()
        assertEquals(stoppedAt, ticks, "a finished game does not tick")
    }

    @Test
    fun giveUpDialogReportsItselfSoTheClockCanPause() {
        val puzzle = SudokuGenerator.generate(BoardSize.SIX, Difficulty.EASY, Random(1))
        val game = SudokuGameState.from(puzzle)
        val reports = mutableListOf<Boolean>()
        rule.setContent {
            GameBoardScreen(
                state = SudokuUiState(game = game),
                actions = SudokuActions.Noop,
                onExit = {},
                onDialogShownChange = { reports += it },
            )
        }
        rule.onNodeWithText(ctx.getString(R.string.give_up)).performClick()
        rule.waitForIdle()
        assertEquals(true, reports.last(), "opening the dialog pauses the clock")
        rule.onNodeWithText(ctx.getString(com.vayunmathur.library.ui.R.string.cancel)).performClick()
        rule.waitForIdle()
        assertEquals(false, reports.last(), "dismissing it resumes the clock")
        rule.onAllNodesWithText(ctx.getString(R.string.confirm_give_up_title)).fetchSemanticsNodes()
            .let { assertEquals(0, it.size) }
    }
}
