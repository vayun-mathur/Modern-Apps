package com.vayunmathur.games.chess

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.chess.auth.LichessAuthBridge
import com.vayunmathur.games.chess.auth.LichessAuthManager
import com.vayunmathur.games.chess.auth.LichessTokenStore
import com.vayunmathur.games.chess.network.LichessApi
import com.vayunmathur.games.chess.network.LichessTimeControl
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class Y3LichessNetTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @BeforeTest fun setUp() {
        NetworkClient.init(app, TrustBundle.STANDARD)
        runBlocking { LichessTokenStore(app).clear() }
    }

    @Test fun authRedirectParsing() {
        fun intent(uri: String?) = Intent(Intent.ACTION_VIEW).apply { if (uri != null) data = Uri.parse(uri) }
        assertNull(LichessAuthManager.AuthRedirect.from(intent(null)))
        assertNull(LichessAuthManager.AuthRedirect.from(intent("https://lichess-callback?code=a&state=b")))
        assertNull(LichessAuthManager.AuthRedirect.from(intent("modernapps://other?code=a&state=b")))
        assertNull(LichessAuthManager.AuthRedirect.from(intent("modernapps://lichess-callback?state=b")))
        assertNull(LichessAuthManager.AuthRedirect.from(intent("modernapps://lichess-callback?code=a")))
        val r = LichessAuthManager.AuthRedirect.from(intent("modernapps://lichess-callback?code=a%20b&state=s1"))
        assertEquals("a b", r!!.code); assertEquals("s1", r.state)
    }

    @Test fun bridgeEmitsOnlyValidRedirects() = runBlocking {
        LichessAuthBridge.onRedirect(Intent(Intent.ACTION_VIEW).apply { data = Uri.parse("https://x/?code=a&state=b") })
        val deferred = kotlinx.coroutines.CompletableDeferred<LichessAuthManager.AuthRedirect>()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        scope.launch { deferred.complete(LichessAuthBridge.redirects.first()) }
        LichessAuthBridge.onRedirect(Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("modernapps://lichess-callback?code=cc&state=ss")
        })
        val got = withTimeout(5_000) { deferred.await() }
        assertEquals("cc", got.code)
        Unit
    }

    @Test fun finishSignInWithoutPendingFails() = runBlocking {
        val mgr = LichessAuthManager(app, LichessTokenStore(app))
        assertFailsWith<IllegalStateException> {
            mgr.finishSignIn(LichessAuthManager.AuthRedirect("c", "s"))
        }
        Unit
    }

    @Test fun startSignInThenStateMismatchFails() = runBlocking {
        val mgr = LichessAuthManager(app, LichessTokenStore(app))
        runCatching { mgr.startSignIn() } // Custom Tab launch is a no-op/shadow under Robolectric
        assertFailsWith<IllegalStateException> {
            mgr.finishSignIn(LichessAuthManager.AuthRedirect("c", "definitely-wrong-state"))
        }
        // Pending was consumed by the failed attempt.
        assertFailsWith<IllegalStateException> {
            mgr.finishSignIn(LichessAuthManager.AuthRedirect("c", "definitely-wrong-state"))
        }
        Unit
    }

    @Test fun lichessApiUnreachableFailsAsIoNotSuccess(): Unit = runBlocking {
        val api = LichessApi(LichessTokenStore(app))
        // No live Lichess in the test sandbox: every call must surface an IOException
        // (transport or HTTP status), never a fabricated success.
        val accountErr = runCatching { withTimeout(30_000) { api.account() } }.exceptionOrNull()
        assertNotNull(accountErr); assertTrue(accountErr is java.io.IOException, "$accountErr")
        val seekErr = runCatching { withTimeout(30_000) { api.seek(LichessTimeControl.BLITZ_3_2) } }.exceptionOrNull()
        assertNotNull(seekErr)
        val challengeErr = runCatching { withTimeout(30_000) { api.challenge("some user", LichessTimeControl.RAPID_10_0) } }.exceptionOrNull()
        assertNotNull(challengeErr)
        val declineErr = runCatching { withTimeout(30_000) { api.declineChallenge("c1") } }.exceptionOrNull()
        assertNotNull(declineErr)
        val moveErr = runCatching { withTimeout(30_000) { api.sendMove("g1", "e2e4") } }.exceptionOrNull()
        assertNotNull(moveErr)
        assertNotNull(runCatching { withTimeout(30_000) { api.resign("g1") } }.exceptionOrNull())
        assertNotNull(runCatching { withTimeout(30_000) { api.abort("g1") } }.exceptionOrNull())
        assertNotNull(runCatching { withTimeout(30_000) { api.streamEvents {} } }.exceptionOrNull())
        assertNotNull(runCatching { withTimeout(30_000) { api.streamGame("g1") {} } }.exceptionOrNull())

    }
}
