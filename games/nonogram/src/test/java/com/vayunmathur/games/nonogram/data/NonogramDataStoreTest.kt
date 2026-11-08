package com.vayunmathur.games.nonogram.data

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class NonogramDataStoreTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private fun store() = NonogramDataStore(ctx)

    @Test fun defaultsAndModeRoundTrips() = runBlocking {
        val s = store()
        s.saveLevel(1)
        assertEquals(1, s.currentLevel.first())
        assertEquals(STARTING_HEARTS, s.hearts.first())
        assertEquals(emptySet(), s.filledCells.first())
        assertEquals(GameMode.CASUAL, s.gameMode.first())
        assertEquals(MarkMode.FILL, s.markMode.first())
        s.setGameMode(GameMode.DAILY); assertEquals(GameMode.DAILY, s.gameMode.first())
        s.setGameMode(GameMode.CASUAL)
        s.setMarkMode(MarkMode.CROSS); assertEquals(MarkMode.CROSS, s.markMode.first())
        s.setMarkMode(MarkMode.FILL)
    }

    @Test fun saveLevelWipesBoardAndRestoresHearts() = runBlocking {
        val s = store()
        s.fillCell(3, daily = false); s.setNote(4, true, daily = false); s.revealBlank(5, daily = false)
        assertEquals(STARTING_HEARTS - 1, s.hearts.first())
        s.saveLevel(7)
        assertEquals(7, s.currentLevel.first())
        assertEquals(emptySet(), s.filledCells.first())
        assertEquals(emptySet(), s.crossedCells.first())
        assertEquals(emptySet(), s.revealedBlanks.first())
        assertEquals(STARTING_HEARTS, s.hearts.first())
    }

    @Test fun fillRemovesNoteAndNotesToggle() = runBlocking {
        val s = store(); s.saveLevel(2)
        s.setNote(10, true, daily = false); assertEquals(setOf(10), s.crossedCells.first())
        s.fillCell(10, daily = false)
        assertEquals(setOf(10), s.filledCells.first()); assertEquals(emptySet(), s.crossedCells.first())
        s.setNote(11, true, daily = false); s.setNote(11, false, daily = false)
        assertEquals(emptySet(), s.crossedCells.first())
    }

    @Test fun revealBlankChargesOnceAndClearsNote() = runBlocking {
        val s = store(); s.saveLevel(3)
        s.setNote(20, true, daily = false)
        s.revealBlank(20, daily = false)
        assertEquals(setOf(20), s.revealedBlanks.first()); assertEquals(emptySet(), s.crossedCells.first())
        assertEquals(STARTING_HEARTS - 1, s.hearts.first())
        s.revealBlank(20, daily = false)
        assertEquals(STARTING_HEARTS - 1, s.hearts.first(), "second reveal must not charge again")
        s.revealBlank(21, daily = false); s.revealBlank(22, daily = false)
        assertEquals(0, s.hearts.first())
        s.revealBlank(23, daily = false); assertEquals(0, s.hearts.first(), "hearts floor at zero")
    }

    @Test fun clearMarksCasualAndDailyAreIndependent() = runBlocking {
        val s = store(); s.saveLevel(4); s.ensureDailyDay(19_000L)
        s.fillCell(1, daily = false); s.fillCell(2, daily = true)
        s.clearMarks(daily = false)
        assertEquals(emptySet(), s.filledCells.first()); assertEquals(setOf(2), s.dailyFilledCells.first())
        s.clearMarks(daily = true)
        assertEquals(emptySet(), s.dailyFilledCells.first()); assertEquals(STARTING_HEARTS, s.dailyHearts.first())
    }

    @Test fun recordCompletedOnceBanksEachWinKeyOnce() = runBlocking {
        val s = store()
        // Synthetic keys far outside the ViewModel's win namespace (casual: -level, daily: epoch
        // day), so this stays meaningful even if production keying ever changes.
        val a = s.recordCompletedOnce(-100_001L); val b = s.recordCompletedOnce(-100_002L)
        assertEquals(a + 1, b); assertEquals(b, s.puzzlesCompleted.first())
        // A replay reports the lifetime total unchanged: it banks nothing new.
        assertEquals(b, s.recordCompletedOnce(-100_001L), "replaying a banked win must not count again")
        assertEquals(b, s.puzzlesCompleted.first())
    }

    @Test fun dailyRolloverClearsOnlyOnDayChange() = runBlocking {
        val s = store()
        s.ensureDailyDay(20_000L); s.fillCell(5, daily = true); s.revealBlank(6, daily = true)
        s.ensureDailyDay(20_000L)
        assertEquals(setOf(5), s.dailyFilledCells.first(), "same day must keep work")
        s.ensureDailyDay(20_001L)
        assertEquals(20_001L, s.dailyDay.first())
        assertEquals(emptySet(), s.dailyFilledCells.first())
        assertEquals(emptySet(), s.dailyRevealedBlanks.first())
        assertEquals(STARTING_HEARTS, s.dailyHearts.first())
        assertTrue(s.dailyCrossedCells.first().isEmpty())
    }

    @Test fun puzzleFilledCountAndClues() {
        val p = NonogramPuzzle.from(3, listOf(true,false,true, false,true,false, true,false,true))
        assertEquals(5, p.filledCount); assertEquals(3, p.size)
        assertEquals(listOf(1,1), p.rowClues[0]); assertEquals(emptyList<Int>(), NonogramPuzzle.cluesFor(listOf(false,false)))
    }
}
