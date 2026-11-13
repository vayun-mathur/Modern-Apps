package com.vayunmathur.games.chess.platform

import android.app.Application
import android.content.Intent
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.chess.auth.LichessAuthManager
import com.vayunmathur.games.chess.auth.LichessTokenStore
import com.vayunmathur.games.chess.data.PieceType
import com.vayunmathur.games.chess.data.Position
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OnlineChessViewModelTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @BeforeTest fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        runBlocking { LichessTokenStore(app).clear() }
    }
    @AfterTest fun tearDown() {
        runBlocking { LichessTokenStore(app).clear() }
        Dispatchers.resetMain()
    }

    @Test fun `guards with no live game are no-ops`() {
        val vm = OnlineChessViewModel(app)
        vm.onSquareClick(Position(6, 4))
        vm.onPromote(PieceType.QUEEN)
        vm.onCancelPromotion()
        vm.onResignConfirm(); vm.onAbortConfirm()
        vm.backToLobby()
        assertEquals(OnlineConnection.Idle, vm.uiState.value.connection)
        vm.dismissLobbyMessage()
        assertNull(vm.uiState.value.lobbyError)
    }

    @Test fun `challenge with blank username is ignored and cancel seek idles`() {
        val vm = OnlineChessViewModel(app)
        vm.onChallengeUsernameChange("   ")
        vm.challenge()
        assertEquals(OnlineConnection.Idle, vm.uiState.value.connection)
        vm.cancelSeek()
        assertEquals(OnlineConnection.Idle, vm.uiState.value.connection)
    }

    @Test fun `auth redirect parsing only accepts our callback`() {
        assertNull(LichessAuthManager.AuthRedirect.from(Intent()))
        assertNull(LichessAuthManager.AuthRedirect.from(Intent().setData("https://example.com/?code=x&state=y".toUri())))
        assertNull(LichessAuthManager.AuthRedirect.from(Intent().setData("modernapps://lichess-callback?code=x".toUri())))
        val ok = LichessAuthManager.AuthRedirect.from(
            Intent().setData("modernapps://lichess-callback?code=abc&state=xyz".toUri())
        )
        assertEquals(LichessAuthManager.AuthRedirect("abc", "xyz"), ok)
        assertTrue(ok!!.code.isNotEmpty())
    }

    @Test fun `finish sign in without pending attempt throws`() = runBlocking {
        val manager = LichessAuthManager(app, LichessTokenStore(app))
        var threw = false
        try {
            manager.finishSignIn(LichessAuthManager.AuthRedirect("code", "state"))
        } catch (_: IllegalStateException) {
            threw = true
        } catch (_: Exception) {
            // Network/token exchange failures also prove the pending-state path was exercised;
            // the state guard is the contract, so only IllegalState counts as a pass here.
        }
        assertTrue(threw, "expected IllegalStateException for sign-in with no pending attempt")
    }
}
