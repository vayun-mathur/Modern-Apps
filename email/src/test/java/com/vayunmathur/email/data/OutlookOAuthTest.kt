package com.vayunmathur.email.data

import android.app.Application
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the offline-safe edges of [OutlookOAuth]: completing without a PKCE
 * session must fail closed (never exchange), and a fresh access token must
 * be returned without touching the network. A regression here either strands
 * signed-in users or fires token refreshes on every send.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutlookOAuthTest {

    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    private fun account(
        accessToken: String = "tok",
        refreshToken: String? = "refresh",
        expiresAt: Long = System.currentTimeMillis() + 3_600_000L,
    ) = EmailAccount(
        email = "me@outlook.com",
        provider = PROVIDER_OUTLOOK,
        authType = "oauth2",
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAt = expiresAt,
    )

    @Test
    fun `complete without a pkce session fails closed`() = runBlocking {
        // No start() call, so the outlook_oauth prefs hold no verifier.
        context.getSharedPreferences("outlook_oauth", android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()
        val result = OutlookOAuth.complete(context, "com.vayunmathur.email://oauth?code=abc&state=xyz".toUri())
        assertTrue(result is OutlookOAuth.OAuthResult.Failure, result.toString())
        assertTrue(
            (result as OutlookOAuth.OAuthResult.Failure).reason.contains("verifier", ignoreCase = true),
            result.reason,
        )
    }

    @Test
    fun `fresh access token is returned without a refresh`() = runBlocking {
        val token = OutlookOAuth.freshAccessToken(context, account(accessToken = "live-token"))
        assertEquals("live-token", token)
    }

    @Test
    fun `expired token without refresh falls back to the stored token`() = runBlocking {
        val token = OutlookOAuth.freshAccessToken(
            context,
            account(accessToken = "stale", refreshToken = null, expiresAt = 0L),
        )
        assertEquals("stale", token)
    }

    @Test
    fun `blank stored token with no refresh yields null`() = runBlocking {
        val token = OutlookOAuth.freshAccessToken(
            context,
            account(accessToken = "", refreshToken = "", expiresAt = 0L),
        )
        assertNull(token)
    }
}
