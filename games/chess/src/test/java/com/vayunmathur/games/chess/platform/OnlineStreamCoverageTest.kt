package com.vayunmathur.games.chess.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.chess.R
import com.vayunmathur.games.chess.auth.LichessTokenStore
import com.vayunmathur.games.chess.data.Board
import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.data.PieceType
import com.vayunmathur.games.chess.data.Position
import com.vayunmathur.games.chess.network.GameFull
import com.vayunmathur.games.chess.network.GameState
import com.vayunmathur.games.chess.network.LichessEvent
import com.vayunmathur.games.chess.network.LichessGameUpdate
import com.vayunmathur.games.chess.network.LichessPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Server-event handling of [OnlineChessViewModel]: `onEvent`/`onGameUpdate` are the
 * private sinks of the NDJSON streams, and [LichessApi] is a concrete class bound to
 * the real [com.vayunmathur.library.network.NetworkClient], so there is no fake
 * transport to drive them with. The tests invoke the sinks reflectively and assert
 * the resulting public [OnlineChessViewModel.uiState] transitions — the same state
 * the screens render.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OnlineStreamCoverageTest {
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

    /** Invokes a private `suspend fun` on the VM and suspends until it completes. */
    private suspend fun invokeSink(vm: OnlineChessViewModel, name: String, arg: Any) {
        val method = OnlineChessViewModel::class.java
            .getDeclaredMethod(name, arg.javaClass.interfaces.firstOrNull() ?: arg.javaClass, Continuation::class.java)
        method.isAccessible = true
        suspendCancellableCoroutine { cont ->
            val result = try {
                method.invoke(vm, arg, cont)
            } catch (e: InvocationTargetException) {
                cont.resumeWith(Result.failure(e.cause ?: e))
                return@suspendCancellableCoroutine
            }
            if (result != COROUTINE_SUSPENDED) cont.resumeWith(Result.success(result))
        }
    }

    private suspend fun onEvent(vm: OnlineChessViewModel, event: LichessEvent) =
        invokeSink(vm, "onEvent", event)

    private suspend fun onGameUpdate(vm: OnlineChessViewModel, update: LichessGameUpdate) =
        invokeSink(vm, "onGameUpdate", update)

    private fun playing(vm: OnlineChessViewModel) =
        vm.uiState.value.connection as OnlineConnection.Playing

    @Test fun `game start enters playing and game finish for another id is ignored`() = runBlocking {
        val vm = OnlineChessViewModel(app)
        onEvent(vm, LichessEvent.GameStart("g1", PieceColor.WHITE, "bob"))
        val started = playing(vm)
        assertEquals("g1", started.gameId)
        assertEquals("bob", started.opponentName)
        assertEquals(Board.initialState, started.board)
        onEvent(vm, LichessEvent.GameFinish("someone-else"))
        assertEquals("g1", playing(vm).gameId, "a finish for another game must not detach ours")
        onEvent(vm, LichessEvent.Ignored)
        assertEquals("g1", playing(vm).gameId)
        vm.signOut()
    }

    @Test fun `challenge declined returns a challenging lobby to idle with a notice`() = runBlocking {
        val vm = OnlineChessViewModel(app)
        onEvent(vm, LichessEvent.ChallengeDeclined("c1")) // not challenging: ignored
        assertEquals(OnlineConnection.Idle, vm.uiState.value.connection)
        assertNull(vm.uiState.value.lobbyNotice)
        vm.uiStateFlow.update { it.copy(connection = OnlineConnection.Challenging("bob")) }
        onEvent(vm, LichessEvent.ChallengeDeclined("c1"))
        assertEquals(OnlineConnection.Idle, vm.uiState.value.connection)
        assertEquals(OnlineLobby.Notice.ChallengeDeclined, vm.uiState.value.lobbyNotice)
        onEvent(vm, LichessEvent.Challenge("c2", "eve")) // incoming: declined server-side, lobby untouched
        assertEquals(OnlineConnection.Idle, vm.uiState.value.connection)
        vm.signOut()
    }

    @Test fun `game full resolves my colour and state deltas replay only the unseen tail`() = runBlocking {
        LichessTokenStore(app).saveUserId("me")
        val vm = OnlineChessViewModel(app)
        vm.uiStateFlow.update {
            it.copy(connection = OnlineConnection.Playing("g1", PieceColor.WHITE, opponentName = null))
        }
        val full = GameFull(
            id = "g1",
            white = LichessPlayer(id = "opp", name = "olga"),
            black = LichessPlayer(id = "me", name = "ada"),
            state = GameState(moves = "", wtime = 61_000, btime = 62_000, status = "started"),
        )
        onGameUpdate(vm, LichessGameUpdate.Full(full))
        var state = playing(vm)
        assertEquals(PieceColor.BLACK, state.localColor)
        assertEquals("olga", state.opponentName)
        assertEquals(61_000L, state.whiteClockMs)
        assertEquals(0L + 62_000L, state.blackClockMs)

        onGameUpdate(vm, LichessGameUpdate.State(GameState(moves = "e2e4 e7e5 g1f3", status = "started")))
        state = playing(vm)
        assertEquals(PieceType.KNIGHT, state.board.pieces[sq('f', 3).row][sq('f', 3).col]?.type)
        assertEquals(PieceColor.BLACK, state.turn, "three half-moves played: black to move")
        // A re-sent full list replays nothing twice; a tail with an illegal token stops there.
        onGameUpdate(vm, LichessGameUpdate.State(GameState(moves = "e2e4 e7e5 g1f3 g8f6 e2e3", status = "started")))
        state = playing(vm)
        assertEquals(PieceType.KNIGHT, state.board.pieces[sq('f', 6).row][sq('f', 6).col]?.type)
        assertEquals(4, state.board.moves.size, "the stale e2e3 token ends the replay")
        onGameUpdate(vm, LichessGameUpdate.Ignored)
        assertEquals(4, playing(vm).board.moves.size)
        vm.signOut()
    }

    @Test fun `promotion and castling tails are replayed with their piece choices`() = runBlocking {
        LichessTokenStore(app).saveUserId("me")
        val vm = OnlineChessViewModel(app)
        val promoBoard = Board.fromFen("8/P7/8/8/8/8/k6K/8 w - - 0 1")
        vm.uiStateFlow.update {
            it.copy(connection = OnlineConnection.Playing("g1", PieceColor.WHITE, board = promoBoard))
        }
        onGameUpdate(vm, LichessGameUpdate.State(GameState(moves = "a7a8q", status = "started")))
        assertEquals(PieceType.QUEEN, playing(vm).board.pieces[sq('a', 8).row][sq('a', 8).col]?.type)

        val castleVm = OnlineChessViewModel(app)
        val castleBoard = Board.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1")
        castleVm.uiStateFlow.update {
            it.copy(connection = OnlineConnection.Playing("g2", PieceColor.WHITE, board = castleBoard))
        }
        onGameUpdate(castleVm, LichessGameUpdate.State(GameState(moves = "e1g1", status = "started")))
        assertEquals(PieceType.ROOK, playing(castleVm).board.pieces[sq('f', 1).row][sq('f', 1).col]?.type)
        assertEquals(PieceColor.BLACK, playing(castleVm).turn)
        vm.signOut(); castleVm.signOut()
    }

    @Test fun `terminal states map to localised results and a full update without identity is dropped`() = runBlocking {
        val vm = OnlineChessViewModel(app)
        vm.uiStateFlow.update {
            it.copy(connection = OnlineConnection.Playing("g1", PieceColor.BLACK, opponentName = "bob"))
        }
        onGameUpdate(vm, LichessGameUpdate.State(
            GameState(moves = "e2e4", status = "resign", winner = "white")))
        assertEquals(app.getString(R.string.online_result_resigned), playing(vm).resultText,
            "white won on resign and I hold black: I resigned")
        onGameUpdate(vm, LichessGameUpdate.State(
            GameState(moves = "e2e4", status = "resign", winner = "black")))
        assertEquals(app.getString(R.string.online_result_opponent_resigned), playing(vm).resultText)
        onGameUpdate(vm, LichessGameUpdate.State(
            GameState(moves = "e2e4", status = "draw", winner = null)))
        assertEquals(app.getString(R.string.online_result_draw), playing(vm).resultText)
        onGameUpdate(vm, LichessGameUpdate.State(
            GameState(moves = "e2e4", status = "mate", winner = "black")))
        assertEquals(app.getString(R.string.online_result_win), playing(vm).resultText)
        onGameUpdate(vm, LichessGameUpdate.State(
            GameState(moves = "e2e4", status = "mate", winner = "white")))
        assertEquals(app.getString(R.string.online_result_loss), playing(vm).resultText)
        onGameUpdate(vm, LichessGameUpdate.State(
            GameState(moves = "e2e4", status = "aborted", winner = null)))
        assertEquals(app.getString(R.string.online_result_draw), playing(vm).resultText)

        LichessTokenStore(app).clear() // no stored identity: gameFull cannot resolve my colour
        val before = vm.uiState.value
        onGameUpdate(vm, LichessGameUpdate.Full(GameFull(id = "g9")))
        assertEquals(before, vm.uiState.value)
        assertNotNull(playing(vm).resultText)
        vm.signOut()
    }
}
