package com.vayunmathur.games.chess.platform

import android.app.Application
import android.content.Intent
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.chess.auth.LichessAuthBridge
import com.vayunmathur.games.chess.auth.LichessTokenStore
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

/** Pass 3: OnlineChessViewModel sign-in state and OAuth redirect handling. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OnlineChessSignInPass3Test {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @BeforeTest fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        runBlocking { LichessTokenStore(app).clear() }
    }

    @AfterTest fun tearDown() {
        runBlocking { LichessTokenStore(app).clear() }
        Dispatchers.resetMain()
    }

    private fun await(desc: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(25)
        }
        assertTrue(cond(), "timed out: $desc")
    }

    @Test fun signInEntersSigningInAndIgnoresRepeat() {
        val vm = OnlineChessViewModel(app)
        assertEquals(OnlineAuth.SignedOut, vm.uiState.value.auth)
        // The Custom Tab launch may not resolve under Robolectric; the state
        // transition happens before it either way.
        runCatching { vm.signIn() }
        assertEquals(OnlineAuth.SigningIn, vm.uiState.value.auth)
        runCatching { vm.signIn() }
        assertEquals(OnlineAuth.SigningIn, vm.uiState.value.auth, "repeat sign-in while pending is ignored")
        assertNull(vm.uiState.value.authError)
    }

    @Test fun redirectWithMismatchedStateFailsSignIn() {
        val vm = OnlineChessViewModel(app)
        runCatching { vm.signIn() }
        assertEquals(OnlineAuth.SigningIn, vm.uiState.value.auth)
        LichessAuthBridge.onRedirect(
            Intent().setData("modernapps://lichess-callback?code=abc&state=not-the-state".toUri())
        )
        await("sign-in failed") { vm.uiState.value.auth is OnlineAuth.SignedOut }
        assertEquals(OnlineAuth.Error.SignInFailed, vm.uiState.value.authError)
        assertEquals(
            app.getString(com.vayunmathur.games.chess.R.string.online_sign_in_failed),
            vm.authErrorText(OnlineAuth.Error.SignInFailed),
        )
        vm.dismissLobbyMessage()
        assertNull(vm.uiState.value.authError)
    }

    @Test fun redirectWhileSignedOutIsDropped() {
        val vm = OnlineChessViewModel(app)
        LichessAuthBridge.onRedirect(
            Intent().setData("modernapps://lichess-callback?code=abc&state=xyz".toUri())
        )
        Thread.sleep(300)
        assertEquals(OnlineAuth.SignedOut, vm.uiState.value.auth)
        assertNull(vm.uiState.value.authError)
    }
}
