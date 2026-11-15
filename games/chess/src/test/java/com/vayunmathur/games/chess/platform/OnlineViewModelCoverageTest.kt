package com.vayunmathur.games.chess.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.chess.R
import com.vayunmathur.games.chess.auth.LichessTokenStore
import com.vayunmathur.games.chess.data.Board
import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.data.PieceType
import com.vayunmathur.games.chess.data.Position
import com.vayunmathur.games.chess.network.LichessTimeControl
import com.vayunmathur.games.chess.network.UciMove
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Online ViewModel behaviour driven through its `uiStateFlow` test seam: the board
 * only changes on server echoes, so local interaction is selection, deselection and
 * promotion staging — all assertable without any network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OnlineViewModelCoverageTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun sq(file: Char, rank: Int) = Position(8 - rank, file - 'a')

    @BeforeTest fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        runBlocking { LichessTokenStore(app).clear() }
    }
    @AfterTest fun tearDown() {
        runBlocking { LichessTokenStore(app).clear() }
        Dispatchers.resetMain()
    }

    private fun playingVm(
        board: Board = Board.initialState,
        turn: PieceColor = PieceColor.WHITE,
        localColor: PieceColor = PieceColor.WHITE,
        resultText: String? = null,
        pending: UciMove? = null,
    ): OnlineChessViewModel {
        val vm = OnlineChessViewModel(app)
        vm.uiStateFlow.update {
            it.copy(
                auth = OnlineAuth.SignedIn("ada"),
                connection = OnlineConnection.Playing(
                    gameId = "game-1", localColor = localColor, opponentName = "bob",
                    board = board, turn = turn, resultText = resultText,
                    pendingPromotion = pending,
                ),
            )
        }
        return vm
    }

    private fun playing(vm: OnlineChessViewModel) =
        vm.uiState.value.connection as OnlineConnection.Playing

    @Test fun `selection selects own piece, deselects on illegal target, ignores enemy and empty`() {
        val vm = playingVm()
        vm.onSquareClick(sq('e', 7)) // black pawn on white's move
        assertNull(playing(vm).selectedPiece)
        vm.onSquareClick(sq('e', 4)) // empty square with nothing selected
        assertNull(playing(vm).selectedPiece)
        vm.onSquareClick(sq('e', 2))
        assertEquals(sq('e', 2), playing(vm).selectedPiece)
        vm.onSquareClick(sq('e', 5)) // illegal pawn jump: deselect only, board untouched
        assertNull(playing(vm).selectedPiece)
        assertEquals(Board.initialState, playing(vm).board)
    }

    @Test fun `clicks are ignored when it is not our turn or the game is over`() {
        val opponentTurn = playingVm(turn = PieceColor.BLACK)
        opponentTurn.onSquareClick(sq('e', 7))
        assertNull(playing(opponentTurn).selectedPiece)
        val over = playingVm(resultText = "You won")
        over.onSquareClick(sq('e', 2))
        assertNull(playing(over).selectedPiece)
        assertEquals(Board.initialState, playing(over).board)
    }

    @Test fun `promotion is staged without touching the board and cancels cleanly`() {
        val board = Board.fromFen("8/P7/8/8/8/8/k6K/8 w - - 0 1")
        val vm = playingVm(board = board)
        vm.onSquareClick(sq('a', 7))
        assertEquals(sq('a', 7), playing(vm).selectedPiece)
        vm.onSquareClick(sq('a', 8))
        val staged = playing(vm)
        assertEquals(UciMove(sq('a', 7), sq('a', 8)), staged.pendingPromotion)
        assertEquals(board, staged.board, "the confirmed position does not move before the server echo")
        assertNull(staged.selectedPiece)
        vm.onSquareClick(sq('a', 7)) // the modal promotion choice owns input
        assertNull(playing(vm).selectedPiece)
        vm.onCancelPromotion()
        assertNull(playing(vm).pendingPromotion)
        assertEquals(board, playing(vm).board)
        vm.onCancelPromotion() // nothing staged: no-op
        vm.onPromote(PieceType.QUEEN) // nothing staged: no-op, and no network call is made
        assertEquals(board, playing(vm).board)
    }

    @Test fun `drop mirrors taps including the same-square and reselect paths`() {
        val vm = playingVm()
        vm.onSquareDrop(sq('e', 2), sq('e', 2))
        assertEquals(sq('e', 2), playing(vm).selectedPiece)
        vm.onSquareDrop(sq('e', 2), sq('e', 5)) // illegal drop target deselects
        assertNull(playing(vm).selectedPiece)
        vm.onSquareDrop(sq('d', 2), sq('d', 5)) // press selects d2, illegal release target deselects
        assertNull(playing(vm).selectedPiece)
        assertEquals(Board.initialState, playing(vm).board)
    }

    @Test fun `lobby guards and session resets`() {
        val vm = OnlineChessViewModel(app)
        vm.uiStateFlow.update {
            it.copy(
                auth = OnlineAuth.SigningIn,
                connection = OnlineConnection.Seeking(LichessTimeControl.BLITZ_3_2),
                lobbyError = OnlineLobby.Error.SeekFailed,
                lobbyNotice = OnlineLobby.Notice.ChallengeDeclined,
                authError = OnlineAuth.Error.Expired,
            )
        }
        vm.signIn() // already signing in: ignored
        assertEquals(OnlineAuth.SigningIn, vm.uiState.value.auth)
        vm.quickSeek() // already seeking: ignored
        assertTrue(vm.uiState.value.connection is OnlineConnection.Seeking)
        vm.onChallengeUsernameChange("bob")
        vm.challenge() // seeking blocks challenging
        assertTrue(vm.uiState.value.connection is OnlineConnection.Seeking)
        vm.cancelSeek()
        assertEquals(OnlineConnection.Idle, vm.uiState.value.connection)
        vm.dismissLobbyMessage()
        assertNull(vm.uiState.value.lobbyError)
        assertNull(vm.uiState.value.lobbyNotice)
        assertNull(vm.uiState.value.authError)
        assertEquals(
            app.getString(R.string.online_auth_expired),
            vm.authErrorText(OnlineAuth.Error.Expired),
        )
        vm.selectTimeControl(LichessTimeControl.BULLET)
        assertEquals("1+0", vm.uiState.value.selectedTimeControl.label)
        assertEquals(LichessTimeControl.PRESETS, listOf(
            LichessTimeControl.BULLET, LichessTimeControl.BLITZ_3_2,
            LichessTimeControl.RAPID_5_3, LichessTimeControl.RAPID_10_0,
        ))
    }

    @Test fun `back to lobby and sign out reset the session`() {
        val vm = playingVm()
        vm.onSquareClick(sq('e', 2))
        vm.backToLobby()
        assertEquals(OnlineConnection.Idle, vm.uiState.value.connection)
        val inGame = playingVm()
        assertTrue(playing(inGame).canAbort, "no moves yet: abort is offered")
        val moved = Board.initialState.movePiece(sq('e', 2), sq('e', 4))
        val afterMove = playingVm(board = moved, turn = PieceColor.BLACK)
        assertFalse(playing(afterMove).canAbort)
        inGame.signOut()
        assertEquals(OnlineChessUiState(), inGame.uiState.value)
        assertFalse(inGame.uiState.value.isBoardFlipped)
        val black = playingVm(localColor = PieceColor.BLACK, turn = PieceColor.BLACK)
        assertTrue(black.uiState.value.isBoardFlipped)
        assertEquals(PieceColor.BLACK, black.uiState.value.turn)
    }
}
