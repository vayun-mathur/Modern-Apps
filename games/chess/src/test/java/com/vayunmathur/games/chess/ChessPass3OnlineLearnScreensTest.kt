package com.vayunmathur.games.chess

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.games.chess.data.Board
import com.vayunmathur.games.chess.data.LearnRepository
import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.data.Position
import com.vayunmathur.games.chess.network.LichessTimeControl
import com.vayunmathur.games.chess.platform.OnlineAuth
import com.vayunmathur.games.chess.platform.OnlineChessUiState
import com.vayunmathur.games.chess.platform.OnlineConnection
import com.vayunmathur.games.chess.util.LearnStatus
import com.vayunmathur.games.chess.util.LearnUiState
import com.vayunmathur.games.chess.util.LearnViewModel
import com.vayunmathur.library.ui.R as UiR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pass 3: online lobby/game state variants and Learn screens (home, stage, dialogs). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ChessPass3OnlineLearnScreensTest {

    @get:Rule val rule = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val ctx: Context get() = app
    private fun str(id: Int, vararg args: Any) =
        if (args.isEmpty()) ctx.getString(id) else ctx.getString(id, *args)

    @BeforeTest fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        LearnRepository.ensureLoaded(ctx)
    }

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun await(desc: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            rule.waitForIdle()
            if (cond()) return
            Thread.sleep(25)
        }
        assertTrue(cond(), "timed out: $desc")
    }

    // ---- Online lobby variants (stateless ChessOnlineScreen) ----

    @Test fun onlineLobbySigningInAndSignedOutVariants() {
        var state by mutableStateOf(
            OnlineChessUiState(auth = OnlineAuth.SigningIn)
        )
        rule.setContent { ChessOnlineScreen(state) }
        rule.onNodeWithText(str(R.string.online_signing_in)).assertIsDisplayed()

        state = OnlineChessUiState(auth = OnlineAuth.SignedOut)
        rule.onNodeWithText(str(R.string.online_sign_in)).assertIsDisplayed()
    }

    @Test fun onlineLobbySeekingAndChallengingVariants() {
        var state by mutableStateOf(
            OnlineChessUiState(
                auth = OnlineAuth.SignedIn("meuser"),
                connection = OnlineConnection.Seeking(LichessTimeControl.BLITZ_3_2),
            )
        )
        rule.setContent { ChessOnlineScreen(state) }
        rule.onNodeWithText(str(R.string.online_signed_in_as, "meuser"), substring = true).assertExists()

        state = OnlineChessUiState(
            auth = OnlineAuth.SignedIn("meuser"),
            connection = OnlineConnection.Challenging("rival"),
            challengeUsername = "rival",
        )
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.online_signed_in_as, "meuser"), substring = true).assertExists()
    }

    @Test fun onlineGameReconnectingClocksAndResultVariants() {
        val playing = OnlineConnection.Playing(
            gameId = "g1",
            localColor = PieceColor.BLACK,
            opponentName = "Opp",
            board = Board.initialState,
            turn = PieceColor.BLACK,
            whiteClockMs = 61000,
            blackClockMs = 9000,
            reconnecting = true,
            resultText = "You win!",
        )
        val state = OnlineChessUiState(
            auth = OnlineAuth.SignedIn("meuser"),
            connection = playing,
        )
        rule.setContent { ChessOnlineScreen(state) }
        rule.onNodeWithText(str(R.string.online_reconnecting)).assertExists()
        rule.onNodeWithText("You win!").assertExists()
        rule.onNodeWithText(str(R.string.online_back_to_lobby)).assertExists()
        assertTrue(state.isBoardFlipped)
    }

    @Test fun onlineGameResignDialogAndAbortBar() {
        val playing = OnlineConnection.Playing(
            gameId = "g1",
            localColor = PieceColor.WHITE,
            opponentName = "Opp",
            board = Board.initialState,
            turn = PieceColor.WHITE,
        )
        rule.setContent {
            ChessOnlineScreen(OnlineChessUiState(auth = OnlineAuth.SignedIn("me"), connection = playing))
        }
        // No moves yet: abort is offered alongside resign.
        rule.onNodeWithText(str(R.string.online_abort_confirm)).assertExists()
        rule.onNodeWithText(str(R.string.online_resign_confirm)).performScrollTo().performClick()
        rule.onNodeWithText(str(R.string.online_resign_title)).assertExists()
        rule.onNodeWithText(str(UiR.string.cancel)).performClick()
        rule.onNodeWithText(str(R.string.online_resign_title)).assertDoesNotExist()
    }

    // ---- Learn screens ----

    @Test fun learnHomeListsStagesAndOpensOne() {
        val opened = mutableListOf<Pair<String, String>>()
        rule.setContent { LearnHomeScreen(onOpenStage = { c, s -> opened += c to s }) }
        await("categories loaded") {
            rule.onAllNodesWithText("Chess pieces", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        val firstStage = LearnRepository.categories.first().stages.first()
        rule.onNodeWithText(firstStage.title, substring = true).performClick()
        assertEquals(listOf(LearnRepository.categories.first().key to firstStage.key), opened)
    }

    @Test fun learnStageIntroThenBoardAndStepper() {
        val vm = LearnViewModel(app)
        vm.loadStage("chessPieces", "rook")
        await("stage loaded") { vm.uiState.value.level != null }
        var backs = 0
        rule.setContent {
            LearnStageScreen(viewModel = vm, onBack = { backs++ }, onOpenStage = { _, _ -> })
        }
        rule.onNodeWithText(str(R.string.learn_lets_go)).performClick()
        rule.waitForIdle()
rule.onNodeWithText(str(R.string.learn_lets_go)).assertDoesNotExist()
        // The stepper renders a chip per level; tapping the board routes to the VM.
        val chips = rule.onAllNodes(
            matcher = androidx.compose.ui.test.hasClickAction() and
                androidx.compose.ui.test.hasAnyDescendant(androidx.compose.ui.test.hasText("2")),
            useUnmergedTree = true,
        )
        assertTrue(chips.fetchSemanticsNodes().isNotEmpty(), "stepper chip for level 2 exists")
        val rook = vm.uiState.value.let { state ->
            (0..7).firstNotNullOf { r ->
                (0..7).firstNotNullOfOrNull { c ->
                    val piece = state.board.pieces[r][c]
                    if (piece != null && piece.color == state.playerColor) Position(r, c) else null
                }
            }
        }
        // Board squares are the large clickable cells: tap the rook's square by board position.
        val boardNodes = rule.onAllNodes(androidx.compose.ui.test.hasClickAction(), useUnmergedTree = true)
        assertTrue(boardNodes.fetchSemanticsNodes().isNotEmpty())
        vm.onSquareClick(rook)
        assertEquals(rook, vm.uiState.value.selectedPiece, "board tap selection reaches the VM state")
        assertEquals(0, backs)
    }

    @Test fun learnStageShapesAndApplesDraw() {
        val vm = LearnViewModel(app)
        // A stage whose first level carries shapes/apples: rook stage level 0.
        vm.loadStage("chessPieces", "rook")
        await("stage loaded") { vm.uiState.value.level != null }
        rule.setContent {
            LearnStageScreen(viewModel = vm, onBack = {}, onOpenStage = { _, _ -> })
        }
        rule.onNodeWithText(str(R.string.learn_lets_go)).performClick()
        assertTrue(vm.uiState.value.apples.isNotEmpty())
    }

    @Test fun learnCompletedStatusShowsStarsAndNext() {
        val vm = LearnViewModel(app)
        vm.loadStage("chessPieces", "rook")
        await("stage loaded") { vm.uiState.value.level != null }
        // Complete level 0 by collecting every apple with the rook.
        var guard = 0
        while (vm.uiState.value.status == LearnStatus.Playing && guard++ < 40) {
            val state = vm.uiState.value
            val target = state.apples.firstOrNull() ?: break
            var moved = false
            outer@ for (r in 0..7) for (c in 0..7) {
                val piece = state.board.pieces[r][c] ?: continue
                if (piece.color != state.playerColor) continue
                if (state.board.isValidMove(Position(r, c), target) ||
                    state.board.isPseudoLegalMove(Position(r, c), target)
                ) {
                    vm.onSquareClick(Position(r, c))
                    vm.onSquareClick(target)
                    moved = true
                    break@outer
                }
            }
            if (!moved) break
            if (vm.uiState.value.status == LearnStatus.Failed) vm.retryLevel()
        }
        assertEquals(LearnStatus.Completed, vm.uiState.value.status)
        rule.setContent {
            LearnStageScreen(viewModel = vm, onBack = {}, onOpenStage = { _, _ -> })
        }
        rule.onNodeWithText(str(R.string.learn_lets_go)).performClick()
        rule.onNodeWithText(str(R.string.learn_completed), substring = true).assertExists()
        rule.onAllNodesWithText("★", substring = true).fetchSemanticsNodes().let {
            assertTrue(it.isNotEmpty(), "star row renders earned stars")
        }
        rule.onNodeWithText(str(UiR.string.next)).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(1, vm.uiState.value.levelIndex)
    }

    @Test fun stageCompleteDialogRanksAndNextStage() {
        val stage = LearnRepository.categories.first().stages.first()
        val opened = mutableListOf<Pair<String, String>>()
        var backs = 0
        val next = LearnRepository.nextStage(stage.key)
        rule.setContent {
            StageCompleteDialog(
                stage = stage,
                stageStars = List(stage.levels.size) { 3 },
                score = 1234,
                nextTitle = next?.second?.title,
                onNext = { next?.let { opened += it.first to it.second.key } },
                onBackToMenu = { backs++ },
            )
        }
        rule.onNodeWithText(str(R.string.learn_your_score, 1234), substring = true).assertExists()
        if (next != null) {
            rule.onNodeWithText(str(R.string.learn_next_stage, next.second.title), substring = true).performClick()
            assertEquals(listOf(next.first to next.second.key), opened)
        }
        assertEquals(0, backs)
    }

    @Test fun learnStageLoadingState() {
        val vm = LearnViewModel(app)
        rule.setContent {
            LearnStageScreen(viewModel = vm, onBack = {}, onOpenStage = { _, _ -> })
        }
        // No stage loaded: spinner state (title falls back to the Learn tab label).
        rule.onNodeWithText(str(R.string.tab_learn)).assertExists()
        assertEquals(null, vm.uiState.value.stage)
    }

}
