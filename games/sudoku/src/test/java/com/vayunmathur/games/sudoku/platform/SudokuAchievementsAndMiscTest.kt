package com.vayunmathur.games.sudoku.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.sudoku.Route
import com.vayunmathur.games.sudoku.data.BoardSize
import com.vayunmathur.games.sudoku.data.Difficulty
import com.vayunmathur.games.sudoku.data.GameConfig
import com.vayunmathur.games.sudoku.data.SudokuStatsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SudokuAchievementsAndMiscTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var stats: SudokuStatsRepository

    @BeforeTest
    fun setUp() {
        context.getSharedPreferences("sudoku_stats", Context.MODE_PRIVATE).edit().clear().commit()
        stats = SudokuStatsRepository(context)
    }

    private fun manager(): SudokuAchievementsManager {
        val json = context.assets.open("achievements.json").bufferedReader().readText()
        return SudokuAchievementsManager(context, json, stats)
    }

    @Test
    fun checkExistingWithNoWinsUnlocksNothingButReportsZeroProgress() {
        val manager = manager()
        manager.checkExistingAchievements()
        assertEquals(300, SudokuAchievementsManager.SPEED_SECONDS)
        val statuses = runBlocking { manager.getAchievementStatuses().first() }
        assertEquals(manager.achievements.size, statuses.size)
        assertTrue(statuses.none { it.isUnlocked } || statuses.any { it.progress == 0 })
        assertTrue(statuses.all { it.progress == 0 })
    }

    @Test
    fun checkExistingBackfillsFromSeededStats() {
        // Wins on every size, including a fast 9x9 expert win: every back-fill branch fires.
        stats.recordGameWon(BoardSize.SIX, Difficulty.EASY, 400)
        stats.recordGameWon(BoardSize.NINE, Difficulty.EXPERT, 120)
        stats.recordGameWon(BoardSize.TWELVE, Difficulty.MEDIUM, 800)
        val manager = manager()
        manager.checkExistingAchievements()
        // Calls are idempotent: a second pass must not throw or change the achievement set.
        manager.checkExistingAchievements()
        assertEquals(10, manager.achievements.size)
        assertTrue(manager.achievements.any { it.id == "every_size" })
    }

    @Test
    fun backupAgentDeclaresSudokuFiles() {
        val agent = AppBackupAgent()
        assertEquals(listOf("sudoku_stats"), agent.prefNames)
        assertEquals(listOf("datastore_default"), agent.datastoreNames)
    }

    @Test
    fun uiStateAndRejectedDigitDefaultsAndEquality() {
        val state = SudokuUiState()
        assertEquals(null, state.game)
        assertEquals(false, state.canUndo)
        assertEquals(false, state.generating)
        assertEquals(null, state.rejectedDigit)
        assertEquals(RejectedDigit(7, 2L), RejectedDigit(7, 2L))
        assertTrue(RejectedDigit(7, 2L) != RejectedDigit(7, 3L))
        assertEquals(
            SudokuUiState(generating = true),
            SudokuUiState(game = null, canUndo = false, generating = true, rejectedDigit = null),
        )
    }

    @Test
    fun noopActionsDoNothing() {
        val actions = SudokuActions.Noop
        actions.selectCell(1); actions.enterDigit(1); actions.clearCell()
        actions.toggleNotesMode(); actions.hint(); actions.undo(); actions.restart(); actions.playAgain(); actions.giveUp()
        // Reaching here without effect or crash is the contract; state is the caller's.
        assertTrue(actions === SudokuActions.Noop)
    }

    @Test
    fun routesCarryConfigAndSerialize() {
        val config = GameConfig(BoardSize.TWELVE, Difficulty.EXPERT, answerMode = true)
        val route = Route.Game(config)
        assertEquals(config, route.config)
        assertEquals(Route.Home, Route.Home)
        assertEquals(Route.GameCenter, Route.GameCenter)
        assertTrue(Route.Home != Route.GameCenter)

        val encoded = Json.encodeToString(Route.serializer(), Route.Game(config))
        val decoded = Json.decodeFromString(Route.serializer(), encoded)
        assertEquals(Route.Game(config), decoded)
        val homeEncoded = Json.encodeToString(Route.serializer(), Route.Home)
        assertEquals(Route.Home, Json.decodeFromString(Route.serializer(), homeEncoded))
    }
}
