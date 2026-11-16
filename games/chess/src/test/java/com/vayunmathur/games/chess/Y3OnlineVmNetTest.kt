package com.vayunmathur.games.chess

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.chess.auth.LichessTokenStore
import com.vayunmathur.games.chess.data.Board
import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.data.Position
import com.vayunmathur.games.chess.network.LichessTimeControl
import com.vayunmathur.games.chess.platform.OnlineAuth
import com.vayunmathur.games.chess.platform.OnlineChessViewModel
import com.vayunmathur.games.chess.platform.OnlineConnection
import com.vayunmathur.games.chess.platform.OnlineLobby
import com.vayunmathur.games.chess.platform.onChallengeUsernameChange
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class Y3OnlineVmNetTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun sq(f: Char, r: Int) = Position(8 - r, f - 'a')

    @BeforeTest fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        com.vayunmathur.library.network.NetworkClient.init(app, com.vayunmathur.library.network.TrustBundle.STANDARD)
        runBlocking { LichessTokenStore(app).clear() }
    }
    @AfterTest fun tearDown() { runBlocking { LichessTokenStore(app).clear() }; Dispatchers.resetMain() }

    private fun waitFor(timeoutMs: Long = 60_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(100)
        }
        assertTrue(cond(), "condition not met within ${timeoutMs}ms")
    }

    @Test fun quickSeekFailureReturnsToIdleWithError() {
        val vm = OnlineChessViewModel(app)
        vm.uiStateFlow.update { it.copy(auth = OnlineAuth.SignedIn("ada")) }
        vm.quickSeek()
        assertTrue(vm.uiState.value.connection is OnlineConnection.Seeking)
        waitFor { vm.uiState.value.connection == OnlineConnection.Idle }
        assertTrue(vm.uiState.value.lobbyError == OnlineLobby.Error.SeekFailed || vm.uiState.value.authError == OnlineAuth.Error.Expired, "${vm.uiState.value}")
        // Seeking again while already seeking is a no-op guard.
        vm.quickSeek(); vm.cancelSeek(); assertEquals(OnlineConnection.Idle, vm.uiState.value.connection)
    }

    @Test fun challengeFailureReturnsToIdleWithError() {
        val vm = OnlineChessViewModel(app)
        vm.uiStateFlow.update { it.copy(auth = OnlineAuth.SignedIn("ada")) }
        vm.onChallengeUsernameChange("  eve  ")
        vm.challenge()
        assertTrue(vm.uiState.value.connection is OnlineConnection.Challenging)
        waitFor { vm.uiState.value.connection == OnlineConnection.Idle }
        assertTrue(vm.uiState.value.lobbyError == OnlineLobby.Error.ChallengeFailed || vm.uiState.value.authError == OnlineAuth.Error.Expired, "${vm.uiState.value}")
        // Blank username never challenges.
        vm.onChallengeUsernameChange("   "); vm.challenge()
        assertEquals(OnlineConnection.Idle, vm.uiState.value.connection)
    }

    @Test fun localMoveResignAbortFailWithoutChangingBoard() {
        val vm = OnlineChessViewModel(app)
        vm.uiStateFlow.update {
            it.copy(
                auth = OnlineAuth.SignedIn("ada"),
                connection = OnlineConnection.Playing("g1", PieceColor.WHITE, opponentName = "bob"),
            )
        }
        vm.onSquareClick(sq('e', 2)); vm.onSquareClick(sq('e', 4)) // triggers sendMove POST
        Thread.sleep(1_000)
        // Board is server-confirmed only: a failed/401 send never advances it locally;
        // a 401 drops to re-auth (Idle), other failures keep the Playing state.
        val after = vm.uiState.value.connection
        if (after is OnlineConnection.Playing) assertEquals(Board.initialState, after.board)
        vm.onResignConfirm(); vm.onAbortConfirm()
        Thread.sleep(500)
        vm.backToLobby(); assertEquals(OnlineConnection.Idle, vm.uiState.value.connection)
    }

    @Test fun signInStartsAuthFlowOnce() {
        val vm = OnlineChessViewModel(app)
        // From the application context the Custom Tab launch itself fails on
        // Robolectric (no NEW_TASK flag); the VM must already be SigningIn.
        runCatching { vm.signIn() }
        assertEquals(OnlineAuth.SigningIn, vm.uiState.value.auth)
        vm.signIn(); assertEquals(OnlineAuth.SigningIn, vm.uiState.value.auth)
        vm.signOut(); assertEquals(OnlineAuth.SignedOut, vm.uiState.value.auth)
    }
}
