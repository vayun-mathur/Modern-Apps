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

    private fun board(filled: Set<Int> = emptySet(), crossed: Set<Int> = emptySet(), revealed: Set<Int> = emptySet(), hearts: Int = STARTING_HEARTS) =
        StoredBoard(filled, crossed, revealed, hearts)

    @Test fun saveLevelWipesBoardAndRestoresHearts() = runBlocking {
        val s = store(); s.saveLevel(6)
        s.saveBoard(board(setOf(3), setOf(4), setOf(5), STARTING_HEARTS - 1), daily = false, level = 6, day = 0L)
        assertEquals(STARTING_HEARTS - 1, s.hearts.first())
        s.saveLevel(7)
        assertEquals(7, s.currentLevel.first())
        assertEquals(emptySet(), s.filledCells.first())
        assertEquals(emptySet(), s.crossedCells.first())
        assertEquals(emptySet(), s.revealedBlanks.first())
        assertEquals(STARTING_HEARTS, s.hearts.first())
    }

    @Test fun saveBoardRoundTripsAsOneSnapshot() = runBlocking {
        val s = store(); s.saveLevel(2)
        val b = board(setOf(1, 2), setOf(7), setOf(9), 1)
        s.saveBoard(b, daily = false, level = 2, day = 0L)
        assertEquals(b, s.board(daily = false).first())
        assertEquals(setOf(1, 2), s.filledCells.first()); assertEquals(1, s.hearts.first())
    }

    @Test fun saveBoardIsDroppedOnceTheLevelHasMovedOn() = runBlocking {
        val s = store(); s.saveLevel(3)
        s.saveLevel(4)
        // A snapshot of level 3 queued behind "Next level" must not land on level 4.
        s.saveBoard(board(filled = setOf(1)), daily = false, level = 3, day = 0L)
        assertEquals(StoredBoard(), s.board(daily = false).first())
    }

    @Test fun saveBoardCasualAndDailyAreIndependent() = runBlocking {
        val s = store(); s.saveLevel(4); s.ensureDailyDay(19_000L)
        s.saveBoard(board(filled = setOf(1)), daily = false, level = 4, day = 19_000L)
        s.saveBoard(board(filled = setOf(2)), daily = true, level = 4, day = 19_000L)
        assertEquals(setOf(1), s.filledCells.first()); assertEquals(setOf(2), s.dailyFilledCells.first())
        s.saveBoard(StoredBoard(), daily = false, level = 4, day = 19_000L)
        assertEquals(emptySet(), s.filledCells.first()); assertEquals(setOf(2), s.dailyFilledCells.first())
        // Yesterday's daily snapshot is dropped after the rollover.
        s.ensureDailyDay(19_001L)
        s.saveBoard(board(filled = setOf(3)), daily = true, level = 4, day = 19_000L)
        assertEquals(emptySet(), s.dailyFilledCells.first())
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
        s.ensureDailyDay(20_000L)
        s.saveBoard(board(setOf(5), revealed = setOf(6), hearts = 2), daily = true, level = 1, day = 20_000L)
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
