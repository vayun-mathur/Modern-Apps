package com.vayunmathur.games.chess.auth

import android.app.Application
import android.content.Intent
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.chess.platform.OnlineAuth
import com.vayunmathur.games.chess.platform.OnlineChessViewModel
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** PKCE sign-in state machine: pending attempt, state validation, redirect bridge. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AuthCoverageTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    // Custom Tabs need an Activity context; the manager is constructed with one here.
    private val activity: android.app.Activity by lazy {
        org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).create().get()
    }

    @BeforeTest fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        runBlocking { LichessTokenStore(app).clear() }
    }
    @AfterTest fun tearDown() {
        runBlocking { LichessTokenStore(app).clear() }
        Dispatchers.resetMain()
    }

    @Test fun `a mismatched state is rejected and consumes the pending attempt`() { runBlocking {
        val manager = LichessAuthManager(activity, LichessTokenStore(app))
        manager.startSignIn()
        val mismatch = runCatching {
            manager.finishSignIn(LichessAuthManager.AuthRedirect("code", "forged-state"))
        }.exceptionOrNull()
        assertIs<IllegalStateException>(mismatch)
        assertTrue(mismatch.message!!.contains("mismatch"), mismatch.message)
        val second = runCatching {
            manager.finishSignIn(LichessAuthManager.AuthRedirect("code", "forged-state"))
        }.exceptionOrNull()
        assertIs<IllegalStateException>(second)
        assertTrue(second.message!!.contains("No pending"), second.message)
    } }

    @Test fun `restarting sign in abandons the earlier attempt`() { runBlocking {
        val manager = LichessAuthManager(activity, LichessTokenStore(app))
        manager.startSignIn()
        manager.startSignIn() // only the newest state may complete
        val outcome = runCatching {
            manager.finishSignIn(LichessAuthManager.AuthRedirect("code", "stale-state"))
        }.exceptionOrNull()
        assertIs<IllegalStateException>(outcome)
    } }

    @Test fun `the bridge drops foreign intents and forwards our callback`() {
        LichessAuthBridge.onRedirect(Intent()) // no data: ignored, must not throw
        LichessAuthBridge.onRedirect(
            Intent().setData("https://example.com/?code=x&state=y".toUri())
        )
        LichessAuthBridge.onRedirect(
            Intent().setData("modernapps://lichess-callback?code=abc&state=xyz".toUri())
        )
    }

    @Test fun `a redirect with no pending attempt fails the view model sign in`() {
        val vm = OnlineChessViewModel(app)
        vm.uiStateFlow.update { it.copy(auth = OnlineAuth.SigningIn) }
        LichessAuthBridge.onRedirect(
            Intent().setData("modernapps://lichess-callback?code=abc&state=xyz".toUri())
        )
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline &&
            vm.uiState.value.authError != OnlineAuth.Error.SignInFailed
        ) {
            Thread.sleep(40)
        }
        assertEquals(OnlineAuth.Error.SignInFailed, vm.uiState.value.authError)
        assertEquals(OnlineAuth.SignedOut, vm.uiState.value.auth)
        vm.signOut()
    }
}
