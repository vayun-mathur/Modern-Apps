package com.vayunmathur.games.chess.auth

import android.app.Application
import android.content.Intent
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.chess.network.LichessApi
import com.vayunmathur.games.chess.network.LichessProfile
import com.vayunmathur.games.chess.network.LichessTransport
import com.vayunmathur.library.network.NetworkDataStream
import com.vayunmathur.library.network.SimpleResponse
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Pass 3: OAuth PKCE start/finish through the manager's seams (no real network/browser). */
@RunWith(RobolectricTestRunner::class)
class LichessAuthPass3Test {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var store: LichessTokenStore

    @BeforeTest fun setUp() = runBlocking {
        store = LichessTokenStore(app)
        store.clear()
    }

    private fun accountApi(): LichessApi {
        val transport = object : LichessTransport {
            override suspend fun request(url: String, method: String, headers: Map<String, String>, body: String?) =
                SimpleResponse(200, "OK", "", emptyMap(), url)

            override suspend fun account(url: String, headers: Map<String, String>) =
                LichessProfile("uid-1", "alice")

            override suspend fun stream(
                url: String,
                headers: Map<String, String>,
                readTimeoutMs: Long,
                block: suspend (NetworkDataStream?, SimpleResponse) -> Unit,
            ) = block(null, SimpleResponse(200, "OK", "", emptyMap(), url))
        }
        return LichessApi(store, transport)
    }

    /** Starts sign-in against a real activity so the Custom Tab intent is recorded. */
    private fun startCapturing(manager: LichessAuthManager): String? {
        runCatching { manager.startSignIn() }
        val intent = Shadows.shadowOf(activity).nextStartedActivity
        return intent?.data?.getQueryParameter("state")
    }

    private val activity: androidx.activity.ComponentActivity by lazy {
        org.robolectric.Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java)
            .setup().get()
    }

    @Test fun finishWithoutPendingThrows() { runBlocking {
        val manager = LichessAuthManager(app, store)
        assertFailsWith<IllegalStateException> {
            manager.finishSignIn(LichessAuthManager.AuthRedirect("code", "state"))
        }
    } }

    @Test fun stateMismatchThrowsAndClearsPending() { runBlocking {
        val manager = LichessAuthManager(activity, store)
        startCapturing(manager)
        assertFailsWith<IllegalStateException> {
            manager.finishSignIn(LichessAuthManager.AuthRedirect("code", "definitely-wrong-state"))
        }
        // Pending was consumed by the failed attempt: even a retry now reports no pending.
        assertFailsWith<IllegalStateException> {
            manager.finishSignIn(LichessAuthManager.AuthRedirect("code", "definitely-wrong-state"))
        }
    } }

    @Test fun fullExchangeSavesTokenAndProfile() = runBlocking {
        val manager = LichessAuthManager(activity, store)
        val state = startCapturing(manager)
        assertNotNull(state, "Custom Tab intent should carry the OAuth state")
        var exchangedCode: String? = null
        var exchangedVerifier: String? = null
        manager.tokenExchange = { code, verifier ->
            exchangedCode = code
            exchangedVerifier = verifier
            "access-123"
        }
        manager.accountApi = accountApi()
        val username = manager.finishSignIn(LichessAuthManager.AuthRedirect("the-code", state))
        assertEquals("alice", username)
        assertEquals("the-code", exchangedCode)
        assertTrue(!exchangedVerifier.isNullOrBlank() && exchangedVerifier!!.length >= 43)
        assertEquals("access-123", store.tokenNow())
        assertEquals("alice", store.usernameNow())
        assertEquals("uid-1", store.userIdNow())
    }

    @Test fun emptyTokenFromExchangeThrows() = runBlocking {
        val manager = LichessAuthManager(activity, store)
        val state = startCapturing(manager)
        assertNotNull(state)
        manager.tokenExchange = { _, _ -> "" }
        manager.accountApi = accountApi()
        assertFailsWith<IllegalStateException> {
            manager.finishSignIn(LichessAuthManager.AuthRedirect("the-code", state))
        }
        assertEquals(null, store.tokenNow())
    }

    @Test fun authBridgeForwardsOnlyOurRedirects() {
        var received: LichessAuthManager.AuthRedirect? = null
        val job = GlobalScope.launch {
            LichessAuthBridge.redirects.collect { received = it }
        }
        try {
            LichessAuthBridge.onRedirect(Intent())
            LichessAuthBridge.onRedirect(
                Intent().setData("https://example.com/?code=x&state=y".toUri())
            )
            LichessAuthBridge.onRedirect(
                Intent().setData("modernapps://lichess-callback?code=abc&state=xyz".toUri())
            )
            // tryEmit is synchronous into the shared flow buffer; give the collector a beat.
            Thread.sleep(300)
            assertEquals(LichessAuthManager.AuthRedirect("abc", "xyz"), received)
        } finally {
            job.cancel()
        }
    }
}
