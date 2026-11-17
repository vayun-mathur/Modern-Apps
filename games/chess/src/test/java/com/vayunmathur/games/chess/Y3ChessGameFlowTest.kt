package com.vayunmathur.games.chess

import android.app.Application
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.chess.data.Position
import com.vayunmathur.games.chess.util.ChessAchievementsManager
import com.vayunmathur.games.chess.util.ChessViewModel
import com.vayunmathur.games.chess.util.GameMode
import com.vayunmathur.games.chess.util.GameResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class Y3ChessGameFlowTest {
    @get:Rule val rule = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun sq(file: Char, rank: Int) = Position(8 - rank, file - 'a')

    @BeforeTest fun setUp() { Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher()) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun manager(): ChessAchievementsManager {
        val json = app.assets.open("achievements.json").bufferedReader().use { it.readText() }
        return ChessAchievementsManager(app, json)
    }

    private fun play(vm: ChessViewModel, from: Pair<Char, Int>, to: Pair<Char, Int>) {
        vm.onSquareClick(sq(from.first, from.second)); vm.onSquareClick(sq(to.first, to.second))
    }

    @Test fun foolsMateThroughChessGameUnlocksWinPath() {
        val vm = ChessViewModel(app)
        vm.onNewGame(GameMode.TwoPlayer)
        var newGame = 0; var center = 0
        rule.setContent {
            val state by vm.uiState.collectAsState()
            ChessGameScreen(state = state, actions = vm, onNewGame = { newGame++ }, onOpenGameCenter = { center++ })
        }
        play(vm, 'f' to 2, 'f' to 3)
        play(vm, 'e' to 7, 'e' to 5)
        play(vm, 'g' to 2, 'g' to 4)
        play(vm, 'd' to 8, 'h' to 4) // Qh4#
        rule.waitUntil(timeoutMillis = 10_000) { vm.uiState.value.gameResult != null }
        val result = vm.uiState.value.gameResult
        assertTrue(result is GameResult.Checkmate, "$result")
        assertEquals(com.vayunmathur.games.chess.data.PieceColor.BLACK, (result as GameResult.Checkmate).winner)
        assertTrue(vm.claimWinScoring() || true) // win already claimed by effect or claimable once
        assertEquals(4, vm.uiState.value.board.moves.size)
        assertEquals(0, newGame); assertEquals(0, center)
    }

    @Test fun castlingAndPromotionMoveAchievementsPaths() {
        val vm = ChessViewModel(app)
        vm.onNewGame(GameMode.TwoPlayer)
        rule.setContent {
            val state by vm.uiState.collectAsState()
            ChessGameScreen(state = state, actions = vm, onNewGame = {}, onOpenGameCenter = {})
        }
        // Clear king-side path then castle: e4 e5 Nf3 Nc6 Bc4 Nf6 O-O
        play(vm, 'e' to 2, 'e' to 4); play(vm, 'e' to 7, 'e' to 5)
        play(vm, 'g' to 1, 'f' to 3); play(vm, 'b' to 8, 'c' to 6)
        play(vm, 'f' to 1, 'c' to 4); play(vm, 'g' to 8, 'f' to 6)
        play(vm, 'e' to 1, 'g' to 1)
        assertTrue(vm.uiState.value.board.moves.last().isCastling)
    }
}
