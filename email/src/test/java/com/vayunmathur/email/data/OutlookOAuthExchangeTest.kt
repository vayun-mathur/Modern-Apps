package com.vayunmathur.email.data

import android.app.Application
import android.content.Context
import androidx.core.net.toUri
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [OutlookOAuth] token exchange against a local fake Microsoft token
 * endpoint (via the tokenEndpointOverride seam): the code exchange must
 * send the PKCE form, parse tokens, take the account email from the
 * id_token (or the typed hint), persist the OAuth account, and surface
 * provider errors; refresh must trade the refresh token and persist the
 * rotated pair. A wrong parse here signs the user into the wrong
 * account or strands them signed out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutlookOAuthExchangeTest {

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: EmailDatabase
    private lateinit var server: HttpServer
    private var responseCode = 200
    private var responseBody = ""
    @Volatile private var lastForm = ""

    private fun idToken(payloadJson: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"none"}""".toByteArray())
        val payload = enc.encodeToString(payloadJson.toByteArray())
        return "$header.$payload.signature"
    }

    private fun seedSession(state: String? = "state-1", hint: String = "") {
        context.getSharedPreferences("outlook_oauth", Context.MODE_PRIVATE).edit().apply {
            putString("verifier", "verifier-123")
            if (state != null) putString("state", state) else remove("state")
            putString("emailHint", hint)
            apply()
        }
    }

    @BeforeTest
    fun open() {
        try {
            WorkManager.initialize(context, Configuration.Builder().build())
        } catch (_: IllegalStateException) {
        }
        db = Room.inMemoryDatabaseBuilder(context, EmailDatabase::class.java)
            .allowMainThreadQueries().build()
        resetRepositorySingleton()
        com.vayunmathur.library.util.databases[EmailDatabase::class] = db
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/token") { exchange ->
            // A network read timeout on a hung test must not wedge close():
            // SO_TIMEOUT-style guard via the request body read is enough here.
            lastForm = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val bytes = responseBody.toByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(responseCode, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        OutlookOAuth.tokenEndpointOverride = "http://127.0.0.1:${server.address.port}/token"
    }

    @AfterTest
    fun close() {
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

    @Test
    fun `complete exchanges the code and persists the outlook account`() = runBlocking {
        seedSession()
        responseBody = """
            {"access_token":"access-1","refresh_token":"refresh-1","expires_in":1200,
             "id_token":"${idToken("""{"email":"user@outlook.com"}""")}"}
        """.trimIndent()
        val result = OutlookOAuth.complete(
            context,
            "com.vayunmathur.email://oauth?code=code-42&state=state-1".toUri(),
        )
        assertEquals(OutlookOAuth.OAuthResult.Success("user@outlook.com"), result)
        // The form carries the PKCE exchange, url-encoded.
        assertTrue(lastForm.contains("grant_type=authorization_code"), lastForm)
        assertTrue(lastForm.contains("code=code-42"), lastForm)
        assertTrue(lastForm.contains("code_verifier=verifier-123"), lastForm)
        // Account persisted as an OAuth Outlook account with the tokens.
        val account = db.accountDao().getAccountByEmail("user@outlook.com")
        assertNotNull(account)
        assertEquals("oauth2", account.authType)
        assertEquals(PROVIDER_OUTLOOK, account.provider)
        assertEquals("access-1", account.accessToken)
        assertEquals("refresh-1", account.refreshToken)
        assertTrue(account.expiresAt > System.currentTimeMillis())
        assertEquals("outlook.office365.com", account.imapHost)
        // The PKCE session is consumed.
        assertNull(context.getSharedPreferences("outlook_oauth", Context.MODE_PRIVATE).getString("verifier", null))
    }

    @Test
    fun `complete falls back to the typed email hint when the id token has none`() = runBlocking {
        seedSession(hint = "hint@outlook.com")
        responseBody = """
            {"access_token":"a","refresh_token":"r","expires_in":600,
             "id_token":"${idToken("""{"sub":"123"}""")}"}
        """.trimIndent()
        val result = OutlookOAuth.complete(
            context,
            "com.vayunmathur.email://oauth?code=c&state=state-1".toUri(),
        )
        assertEquals(OutlookOAuth.OAuthResult.Success("hint@outlook.com"), result)
        assertEquals("hint@outlook.com", db.accountDao().getAccountByEmail("hint@outlook.com")?.email)
    }

    @Test
    fun `complete without any email fails and persists nothing`() = runBlocking {
        seedSession(hint = "")
        responseBody = """
            {"access_token":"a","refresh_token":"r","expires_in":600,
             "id_token":"${idToken("""{"sub":"123"}""")}"}
        """.trimIndent()
        val result = OutlookOAuth.complete(
            context,
            "com.vayunmathur.email://oauth?code=c&state=state-1".toUri(),
        )
        assertTrue(result is OutlookOAuth.OAuthResult.Failure)
        assertEquals("no_email_in_id_token", (result as OutlookOAuth.OAuthResult.Failure).error)
        assertEquals(emptyList(), db.accountDao().getAccounts())
    }

    @Test
    fun `provider error surfaces and clears the session`() = runBlocking {
        seedSession()
        responseCode = 400
        responseBody = """{"error":"invalid_grant","error_description":"code expired"}"""
        val result = OutlookOAuth.complete(
            context,
            "com.vayunmathur.email://oauth?code=bad&state=state-1".toUri(),
        )
        assertTrue(result is OutlookOAuth.OAuthResult.Failure)
        val failure = result as OutlookOAuth.OAuthResult.Failure
        assertEquals("invalid_grant", failure.error)
        assertEquals("code expired", failure.errorDescription)
        assertNull(context.getSharedPreferences("outlook_oauth", Context.MODE_PRIVATE).getString("verifier", null))
        assertEquals(emptyList(), db.accountDao().getAccounts())
    }

    @Test
    fun `malformed token response fails closed`() = runBlocking {
        seedSession()
        responseBody = "this is not json"
        val result = OutlookOAuth.complete(
            context,
            "com.vayunmathur.email://oauth?code=c&state=state-1".toUri(),
        )
        assertTrue(result is OutlookOAuth.OAuthResult.Failure)
        assertEquals(emptyList(), db.accountDao().getAccounts())
    }

    @Test
    fun `token response without an access token reports no_access_token`() = runBlocking {
        seedSession()
        responseBody = """{"expires_in":600}"""
        val result = OutlookOAuth.complete(
            context,
            "com.vayunmathur.email://oauth?code=c&state=state-1".toUri(),
        )
        assertTrue(result is OutlookOAuth.OAuthResult.Failure)
        assertEquals("no_access_token", (result as OutlookOAuth.OAuthResult.Failure).error)
    }

    @Test
    fun `fresh access token refreshes an expired token and persists the rotation`() = runBlocking {
        val expired = EmailAccount(
            email = "user@outlook.com", provider = PROVIDER_OUTLOOK, authType = "oauth2",
            accessToken = "old-access", refreshToken = "old-refresh",
            expiresAt = System.currentTimeMillis() - 1_000,
        )
        db.accountDao().insertAccount(expired)
        responseBody = """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600}"""
        val token = OutlookOAuth.freshAccessToken(context, expired)
        assertEquals("new-access", token)
        assertTrue(lastForm.contains("grant_type=refresh_token"), lastForm)
        assertTrue(lastForm.contains("refresh_token=old-refresh"), lastForm)
        val stored = db.accountDao().getAccountByEmail("user@outlook.com")
        assertEquals("new-access", stored?.accessToken)
        assertEquals("new-refresh", stored?.refreshToken)
        assertTrue((stored?.expiresAt ?: 0) > System.currentTimeMillis())
    }

    @Test
    fun `refresh failure falls back to the stored access token`() = runBlocking {
        val expired = EmailAccount(
            email = "user@outlook.com", provider = PROVIDER_OUTLOOK, authType = "oauth2",
            accessToken = "old-access", refreshToken = "old-refresh",
            expiresAt = System.currentTimeMillis() - 1_000,
        )
        responseCode = 400
        responseBody = """{"error":"invalid_grant"}"""
        val token = OutlookOAuth.freshAccessToken(context, expired)
        assertEquals("old-access", token)
    }
}
