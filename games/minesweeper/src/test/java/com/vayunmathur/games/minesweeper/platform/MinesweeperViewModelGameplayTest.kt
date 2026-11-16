package com.vayunmathur.games.minesweeper.platform

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.minesweeper.data.BoardSize
import com.vayunmathur.games.minesweeper.data.Difficulty
import com.vayunmathur.games.minesweeper.data.GameConfig
import com.vayunmathur.games.minesweeper.data.GameOutcome
import com.vayunmathur.games.minesweeper.data.MinesweeperStatsRepository
import com.vayunmathur.games.minesweeper.data.TapMode
import com.vayunmathur.library.util.AchievementStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Full games through the ViewModel: win, loss, give-up, timer and achievement back-fill. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MinesweeperViewModelGameplayTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private fun vm() = MinesweeperViewModel(app)
    private fun statsRepo() = MinesweeperStatsRepository(app)

    @BeforeTest
    fun clearStats() {
        app.getSharedPreferences("minesweeper_stats", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    /** Starts a SMALL/EASY field and returns the laid game. */
    private fun started(vm: MinesweeperViewModel): Int {
        vm.newGame(GameConfig(BoardSize.SMALL, Difficulty.EASY))
        vm.tapCell(0)
        return 0
    }

    @Test
    fun diggingEverySafeCellWinsFlagsTheMinesAndRecordsTheWin() {
        val vm = vm()
        vm.newGame(GameConfig(BoardSize.SMALL, Difficulty.EASY))
        vm.tapCell(0)
        vm.incrementTimer()
        vm.incrementTimer()
        playRemaining(vm)

        val game = vm.uiState.value.game!!
        assertEquals(GameOutcome.WON, game.outcome)
        assertTrue(game.isOver)
        assertTrue(game.isCleared)
        assertEquals(0, game.minesRemaining)
        assertEquals(
            game.mines.indices.filter { game.mines[it] },
            game.flagged.indices.filter { game.flagged[it] },
            "winning flags every remaining mine",
        )
        assertFalse(vm.hasActiveGame())

        val sizeStats = vm.getSizeStats(BoardSize.SMALL)
        assertEquals(1, sizeStats.gamesPlayed)
        assertEquals(1, sizeStats.gamesWon)
        assertEquals(1, sizeStats.currentWinStreak)
        assertEquals(2, sizeStats.bestTimeSeconds, "the win is recorded with the clock")

        // The clock stops once the field is over, and dismissing a (possibly
        // queued) achievement notification is always safe.
        vm.incrementTimer()
        assertEquals(2, vm.elapsedSeconds.value)
        vm.dismissAchievementNotification()
    }

    private fun playRemaining(vm: MinesweeperViewModel) {
        var guard = 0
        while (vm.uiState.value.game!!.outcome == GameOutcome.PLAYING && guard++ < 500) {
            val game = vm.uiState.value.game!!
            val next = game.revealed.indices.first { !game.revealed[it] && !game.mines[it] }
            vm.tapCell(next)
        }
        assertTrue(guard < 500, "the field must be clearable by digging safe cells")
    }

    @Test
    fun diggingAMineLosesRecordsTheLossAndFreezesTheBoard() {
        // Seed a win streak on the same variant so the loss has something to break.
        statsRepo().recordGameWon(BoardSize.SMALL, Difficulty.EASY, 30)
        val vm = vm()
        started(vm)
        val laid = vm.uiState.value.game!!
        val mine = laid.mines.indices.first { laid.mines[it] && !laid.revealed[it] }

        vm.tapCell(mine)
        val lost = vm.uiState.value.game!!
        assertEquals(GameOutcome.LOST, lost.outcome)
        assertEquals(mine, lost.explodedAt)
        assertTrue(lost.revealed[mine])
        assertFalse(vm.hasActiveGame())

        val stats = statsRepo().getStats(BoardSize.SMALL, Difficulty.EASY)
        assertEquals(1, stats.gamesPlayed, "the first dig counted the game as played")
        assertEquals(1, stats.gamesWon, "the seeded win is untouched")
        assertEquals(0, stats.currentWinStreak, "the loss breaks the streak")

        // A finished board ignores taps, flags and the clock.
        val safe = lost.revealed.indices.first { !lost.revealed[it] && !lost.mines[it] }
        vm.tapCell(safe)
        assertEquals(lost, vm.uiState.value.game)
        assertFalse(vm.flagCell(safe))
        vm.incrementTimer()
        assertEquals(0, vm.elapsedSeconds.value)

        // Restarting deals a fresh unstarted field on the same variant.
        vm.restart()
        val fresh = vm.uiState.value.game!!
        assertEquals(GameOutcome.PLAYING, fresh.outcome)
        assertFalse(fresh.started)
        assertTrue(fresh.mines.none { it })
        assertTrue(vm.hasActiveGame())
    }

    @Test
    fun giveUpMidGameRecordsALossAndClearsTheField() {
        statsRepo().recordGameWon(BoardSize.MEDIUM, Difficulty.MEDIUM, 30)
        val vm = vm()
        vm.newGame(GameConfig(BoardSize.MEDIUM, Difficulty.MEDIUM))
        vm.tapCell(40)
        vm.incrementTimer()
        assertEquals(1, vm.elapsedSeconds.value)

        vm.giveUp()
        assertNull(vm.uiState.value.game)
        assertFalse(vm.hasActiveGame())
        assertEquals(0, vm.elapsedSeconds.value)
        assertEquals(
            0,
            statsRepo().getStats(BoardSize.MEDIUM, Difficulty.MEDIUM).currentWinStreak,
            "abandoning a started field breaks the streak",
        )
    }

    @Test
    fun giveUpBeforeTheFirstDigRecordsNothing() {
        statsRepo().recordGameWon(BoardSize.MEDIUM, Difficulty.MEDIUM, 30)
        val vm = vm()
        vm.newGame(GameConfig(BoardSize.MEDIUM, Difficulty.MEDIUM))
        vm.giveUp()
        assertNull(vm.uiState.value.game)
        val stats = statsRepo().getStats(BoardSize.MEDIUM, Difficulty.MEDIUM)
        assertEquals(1, stats.currentWinStreak, "an untouched field is not a loss")
        assertEquals(0, stats.gamesPlayed)

        // Giving up with no field at all is a no-op.
        vm.giveUp()
        assertNull(vm.uiState.value.game)
    }

    @Test
    fun actionsWithoutAGameAreSafeNoOps() {
        val vm = vm()
        assertNull(vm.uiState.value.game)
        assertFalse(vm.hasActiveGame())
        vm.tapCell(3)
        assertFalse(vm.flagCell(3))
        vm.incrementTimer()
        assertEquals(0, vm.elapsedSeconds.value)
        vm.setTapMode(TapMode.FLAG)
        assertEquals(TapMode.FLAG, vm.uiState.value.tapMode)
        vm.dismissAchievementNotification()
    }

    @Test
    fun flagModeLongPressDigsAndTapOnOpenNumberNeverChords() {
        val vm = vm()
        started(vm)
        val laid = vm.uiState.value.game!!
        vm.setTapMode(TapMode.FLAG)

        // A flag-mode tap on an open number must open nothing (chord lives on long press).
        val openNumber = laid.revealed.indices.firstOrNull {
            laid.revealed[it] && laid.neighbourCounts[it] > 0
        }
        if (openNumber != null) {
            val before = vm.uiState.value.game!!
            vm.tapCell(openNumber)
            assertEquals(before, vm.uiState.value.game)
        }

        // A flag-mode long press on a covered safe cell digs it instead of flagging.
        val coveredSafe = vm.uiState.value.game!!.let { g ->
            g.revealed.indices.first { !g.revealed[it] && !g.mines[it] }
        }
        assertFalse(vm.flagCell(coveredSafe), "digging via long press is not a flag change")
        assertTrue(vm.uiState.value.game!!.revealed[coveredSafe])
    }

    @Test
    fun winOnLargeExpertWithoutFlagsStillCountsThroughTheWinPath() {
        val vm = vm()
        vm.newGame(GameConfig(BoardSize.LARGE, Difficulty.EXPERT))
        vm.tapCell(0)
        playRemaining(vm)
        val game = vm.uiState.value.game!!
        assertEquals(GameOutcome.WON, game.outcome)
        assertEquals(216, game.cellCount)
        val stats = statsRepo().getStats(BoardSize.LARGE, Difficulty.EXPERT)
        assertEquals(1, stats.gamesPlayed)
        assertEquals(1, stats.gamesWon)
    }

    @Test
    fun achievementsBackFillFromRecordedStats() {
        val repo = statsRepo()
        runBlocking { vm().achievementsManager.forgetAll() }
        repeat(10) { repo.recordGameWon(BoardSize.SMALL, Difficulty.EASY, 45) }

        val manager = MinesweeperAchievementsManager(
            app,
            app.assets.open("achievements.json").bufferedReader().readText(),
            repo,
        )
        assertEquals(
            listOf(
                "first_win", "win_large", "win_expert", "no_flags_needed", "speed_win",
                "first_chord", "wins_10", "wins_50", "win_streak_5",
            ),
            manager.achievements.map { it.id },
        )
        manager.checkExistingAchievements()

        val statuses = awaitStatuses(manager) { list ->
            val byId = list.associateBy { it.achievement.id }
            byId.getValue("first_win").isUnlocked &&
                byId.getValue("speed_win").isUnlocked &&
                byId.getValue("wins_10").isUnlocked &&
                byId.getValue("win_streak_5").isUnlocked &&
                !byId.getValue("win_large").isUnlocked &&
                !byId.getValue("win_expert").isUnlocked &&
                byId.getValue("wins_50").progress == 10
        }
        val byId = statuses.associateBy { it.achievement.id }
        assertEquals(10, byId.getValue("wins_10").progress)
        assertTrue(byId.getValue("win_streak_5").isUnlocked)
    }

    @Test
    fun achievementsStayLockedWithNoWinsRecorded() {
        val repo = statsRepo()
        val manager = MinesweeperAchievementsManager(
            app,
            app.assets.open("achievements.json").bufferedReader().readText(),
            repo,
        )
        runBlocking { manager.forgetAll() }
        manager.checkExistingAchievements()
        val statuses = runBlocking { manager.getAchievementStatuses().first() }
        assertTrue(statuses.none { it.isUnlocked })
        assertTrue(statuses.all { it.progress == 0 })
    }

    private fun awaitStatuses(
        manager: MinesweeperAchievementsManager,
        condition: (List<AchievementStatus>) -> Boolean,
    ): List<AchievementStatus> {
        val deadline = System.currentTimeMillis() + 20_000
        var last: List<AchievementStatus> = emptyList()
        while (System.currentTimeMillis() < deadline) {
            last = runBlocking { manager.getAchievementStatuses().first() }
            if (condition(last)) return last
            Thread.sleep(50)
        }
        assertTrue(condition(last), "timed out waiting for achievement statuses: $last")
        return last
    }
}
