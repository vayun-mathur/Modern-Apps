package com.vayunmathur.email.data

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.core.content.edit
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** W5: OutlookOAuth complete/refresh against a loopback token endpoint. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutlookOAuthFlowW5Test {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var server: HttpServer
    private lateinit var db: EmailDatabase
    private var tokenResponse: Pair<Int, String> = 200 to "{}"

    @BeforeTest
    fun setUp() {
        try {
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        } catch (_: IllegalStateException) {
        }
        db = Room.inMemoryDatabaseBuilder(context, EmailDatabase::class.java)
            .allowMainThreadQueries().build()
        resetRepositorySingleton()
        com.vayunmathur.library.util.databases[EmailDatabase::class] = db
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/token") { exchange ->
            exchange.requestBody.readAllBytes()
            val (code, body) = tokenResponse
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        OutlookOAuth.tokenEndpointOverride = "http://127.0.0.1:${server.address.port}/token"
    }

    @AfterTest
    fun tearDown() {
        OutlookOAuth.tokenEndpointOverride = null
        server.stop(0)
        com.vayunmathur.library.util.databases.remove(EmailDatabase::class)
        resetRepositorySingleton()
        db.close()
    }

    private fun resetRepositorySingleton() {
        val field = EmailRepository::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
    }

    private fun seedSession(state: String? = "state-1", hint: String = "") {
        context.getSharedPreferences("outlook_oauth", Context.MODE_PRIVATE).edit {
            putString("verifier", "verifier-1")
            putString("state", state)
            putString("emailHint", hint)
        }
    }

    private fun idToken(emailClaim: String): String {
        fun b64(text: String) = Base64.encodeToString(
            text.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        return "${b64("""{"alg":"none"}""")}.${b64("""{"email":"$emailClaim"}""")}.sig"
    }

    private fun successBody(email: String = "user@outlook.com") = """
        {"access_token":"access-1","refresh_token":"refresh-1","expires_in":3600,"id_token":"${idToken(email)}"}
    """.trimIndent()

    @Test
    fun completeSuccessPersistsAccount() = runBlocking {
        seedSession(hint = "ignored@x.com")
        tokenResponse = 200 to successBody()
        val result = OutlookOAuth.complete(
            context, Uri.parse("com.vayunmathur.email://oauth?code=code-1&state=state-1")
        )
        val success = assertIs<OutlookOAuth.OAuthResult.Success>(result)
        assertEquals("user@outlook.com", success.email)
        val account = db.accountDao().getAccountByEmail("user@outlook.com")
        assertTrue(account != null && account.authType == "oauth2")
        assertEquals("access-1", account?.accessToken)
    }

    @Test
    fun completeFailuresCoverStateCodeAndTokenErrors() = runBlocking {
        // No verifier at all.
        context.getSharedPreferences("outlook_oauth", Context.MODE_PRIVATE).edit { clear() }
        assertIs<OutlookOAuth.OAuthResult.Failure>(
            OutlookOAuth.complete(context, Uri.parse("com.vayunmathur.email://oauth?code=c&state=s"))
        )
        // State mismatch.
        seedSession()
        val mismatch = OutlookOAuth.complete(
            context, Uri.parse("com.vayunmathur.email://oauth?code=c&state=other")
        )
        assertEquals("state_mismatch", assertIs<OutlookOAuth.OAuthResult.Failure>(mismatch).error)
        // Missing state entirely also fails closed.
        seedSession()
        assertIs<OutlookOAuth.OAuthResult.Failure>(
            OutlookOAuth.complete(context, Uri.parse("com.vayunmathur.email://oauth?code=c"))
        )
        // Provider error instead of a code.
        seedSession()
        val providerError = OutlookOAuth.complete(
            context,
            Uri.parse("com.vayunmathur.email://oauth?error=access_denied&error_description=nope"),
        )
        assertEquals("access_denied", assertIs<OutlookOAuth.OAuthResult.Failure>(providerError).error)
        // Token endpoint refuses.
        seedSession()
        tokenResponse = 400 to """{"error":"invalid_grant","error_description":"code expired"}"""
        val refused = OutlookOAuth.complete(
            context, Uri.parse("com.vayunmathur.email://oauth?code=c&state=state-1")
        )
        assertEquals("invalid_grant", assertIs<OutlookOAuth.OAuthResult.Failure>(refused).error)
        // Token response without an email anywhere.
        seedSession(hint = "not-an-email")
        tokenResponse = 200 to """{"access_token":"a","expires_in":"3600.9"}"""
        val noEmail = OutlookOAuth.complete(
            context, Uri.parse("com.vayunmathur.email://oauth?code=c&state=state-1")
        )
        assertEquals("no_email_in_id_token", assertIs<OutlookOAuth.OAuthResult.Failure>(noEmail).error)
    }

    @Test
    fun completeFallsBackToHintEmail() = runBlocking {
        seedSession(hint = "hint@outlook.com")
        tokenResponse = 200 to """{"access_token":"a2","refresh_token":"r2","expires_in":60}"""
        val result = OutlookOAuth.complete(
            context, Uri.parse("com.vayunmathur.email://oauth?code=c&state=state-1")
        )
        assertEquals("hint@outlook.com", assertIs<OutlookOAuth.OAuthResult.Success>(result).email)
    }

    @Test
    fun freshAccessTokenCoversFreshRefreshAndMissing() = runBlocking {
        val fresh = EmailAccount(
            email = "u@outlook.com", accessToken = "still-good",
            expiresAt = System.currentTimeMillis() + 3_600_000,
        )
        assertEquals("still-good", OutlookOAuth.freshAccessToken(context, fresh))

        tokenResponse = 200 to successBody("u@outlook.com")
        val stale = EmailAccount(
            email = "u@outlook.com", accessToken = "old", refreshToken = "refresh-1",
            expiresAt = System.currentTimeMillis() - 1_000,
        )
        assertEquals("access-1", OutlookOAuth.freshAccessToken(context, stale))
        // The refreshed tokens are persisted back onto the account row.
        db.accountDao().insertAccount(stale)
        assertEquals("access-1", OutlookOAuth.freshAccessToken(context, stale))
        assertEquals("access-1", db.accountDao().getAccountByEmail("u@outlook.com")?.accessToken)

        val noRefresh = EmailAccount(email = "v@outlook.com", accessToken = "", expiresAt = 0)
        assertNull(OutlookOAuth.freshAccessToken(context, noRefresh))
    }

    @Test
    fun oauthActivityCompletesSuccessRedirect() {
        seedSession()
        tokenResponse = 200 to successBody()
        val intent = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            Uri.parse("com.vayunmathur.email://oauth?code=code-1&state=state-1"),
        )
        val controller = org.robolectric.Robolectric.buildActivity(
            com.vayunmathur.email.ui.OAuthActivity::class.java, intent,
        )
        val activity = controller.create().get()
        repeat(300) {
            if (activity.isFinishing) return@repeat
            Thread.sleep(25)
            org.robolectric.Shadows.shadowOf(activity.mainLooper).idle()
        }
        assertTrue(activity.isFinishing)
        controller.destroy()
    }
}
