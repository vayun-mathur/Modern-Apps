package com.vayunmathur.games.arrows.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ArrowsDataStoreTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private fun store() = ArrowsDataStore(ctx)

    @Test
    fun defaultsAndRoundTrips() = runBlocking {
        val s = store()
        // Set everything explicitly first so the test is independent of any prior state.
        s.saveLevel(1)
        s.setGameMode(GameMode.CASUAL)
        s.setShowRoutes(true)
        assertEquals(1, s.currentLevel.first())
        assertEquals(emptySet(), s.removedArrows.first())
        assertEquals(STARTING_HEARTS, s.hearts.first())
        assertEquals(GameMode.CASUAL, s.gameMode.first())
        assertEquals(true, s.showRoutes.first())

        s.setGameMode(GameMode.DAILY)
        assertEquals(GameMode.DAILY, s.gameMode.first())
        s.setGameMode(GameMode.CASUAL)
        assertEquals(GameMode.CASUAL, s.gameMode.first())

        s.setShowRoutes(false)
        assertEquals(false, s.showRoutes.first())
        s.setShowRoutes(true)
        assertEquals(true, s.showRoutes.first())
    }

    @Test
    fun saveLevelResetsBoardProgress() = runBlocking {
        val s = store()
        s.saveProgress(setOf(1, 3), 1, daily = false)
        assertEquals(setOf(1, 3), s.removedArrows.first())
        assertEquals(1, s.hearts.first())
        s.saveLevel(7)
        assertEquals(7, s.currentLevel.first())
        assertEquals(emptySet(), s.removedArrows.first())
        assertEquals(STARTING_HEARTS, s.hearts.first())
        s.saveLevel(1)
    }

    @Test
    fun saveProgressCasualAndDailyAreSeparate() = runBlocking {
        val s = store()
        s.saveProgress(setOf(2), 2, daily = false)
        s.saveProgress(setOf(5, 6), 1, daily = true)
        assertEquals(setOf(2), s.removedArrows.first())
        assertEquals(2, s.hearts.first())
        assertEquals(setOf(5, 6), s.dailyRemoved.first())
        assertEquals(1, s.dailyHearts.first())
    }

    @Test
    fun resetBoardCasualAndDaily() = runBlocking {
        val s = store()
        s.saveProgress(setOf(1), 0, daily = false)
        s.saveProgress(setOf(2), 0, daily = true)
        s.resetBoard(daily = false)
        assertEquals(emptySet(), s.removedArrows.first())
        assertEquals(STARTING_HEARTS, s.hearts.first())
        // Daily untouched by casual reset
        assertEquals(setOf(2), s.dailyRemoved.first())
        assertEquals(0, s.dailyHearts.first())
        s.resetBoard(daily = true)
        assertEquals(emptySet(), s.dailyRemoved.first())
        assertEquals(STARTING_HEARTS, s.dailyHearts.first())
    }

    @Test
    fun recordClearedIncrements() = runBlocking {
        val s = store()
        val before = s.boardsCleared.first()
        val after1 = s.recordCleared()
        assertEquals(before + 1, after1)
        assertEquals(after1, s.boardsCleared.first())
        val after2 = s.recordCleared()
        assertEquals(after1 + 1, after2)
    }

    @Test
    fun ensureDailyDayRollsOverOnlyOnNewDay() = runBlocking {
        val s = store()
        s.ensureDailyDay(20_000L)
        s.saveProgress(setOf(4), 1, daily = true)
        // Same day: progress preserved
        s.ensureDailyDay(20_000L)
        assertEquals(20_000L, s.dailyDay.first())
        assertEquals(setOf(4), s.dailyRemoved.first())
        assertEquals(1, s.dailyHearts.first())
        // New day: progress reset
        s.ensureDailyDay(20_001L)
        assertEquals(20_001L, s.dailyDay.first())
        assertEquals(emptySet(), s.dailyRemoved.first())
        assertEquals(STARTING_HEARTS, s.dailyHearts.first())
        assertTrue(s.dailyDay.first() != Long.MIN_VALUE)
    }
}
