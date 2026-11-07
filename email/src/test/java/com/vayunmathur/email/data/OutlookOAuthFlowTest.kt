package com.vayunmathur.email.data

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * [OutlookOAuth] flow state machine offline: start() must persist the PKCE
 * session and launch the authorize URL; complete() must reject a state
 * mismatch and surface an error redirect without touching the network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutlookOAuthFlowTest {

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private fun prefs() = context.getSharedPreferences("outlook_oauth", Context.MODE_PRIVATE)

    @Test
    fun `isConfigured reflects the built-in client id`() {
        assertTrue(OutlookOAuth.isConfigured())
    }

    @Test
    fun `start stores pkce session and launches authorize url`() {
        prefs().edit { clear() }
        OutlookOAuth.start(context, emailHint = "me@x.com")
        assertNotNull(prefs().getString("verifier", null))
        assertNotNull(prefs().getString("state", null))
        assertEquals("me@x.com", prefs().getString("emailHint", null))
        val launched = Shadows.shadowOf(context as Application).nextStartedActivity
        assertNotNull(launched)
        val url = launched.data.toString()
        assertTrue(url.contains("login.microsoftonline.com"), url)
        assertTrue(url.contains("code_challenge_method=S256"), url)
        assertTrue(url.contains("login_hint=me%40x.com") || url.contains("login_hint=me@x.com"), url)
    }

    @Test
    fun `complete with mismatched state fails closed and clears session`() = runBlocking {
        prefs().edit {
            putString("verifier", "v123")
            putString("state", "expected-state")
            putString("emailHint", "me@x.com")
        }
        val result = OutlookOAuth.complete(
            context,
            "com.vayunmathur.email://oauth?code=abc&state=other-state".toUri(),
        )
        assertTrue(result is OutlookOAuth.OAuthResult.Failure, result.toString())
        assertEquals("state_mismatch", (result as OutlookOAuth.OAuthResult.Failure).error)
        assertEquals(null, prefs().getString("verifier", null))
    }

    @Test
    fun `complete with error redirect surfaces the provider error`() = runBlocking {
        prefs().edit {
            putString("verifier", "v123")
            putString("state", "s")
            putString("emailHint", "")
        }
        val result = OutlookOAuth.complete(
            context,
            "com.vayunmathur.email://oauth?error=access_denied&error_description=User+cancelled".toUri(),
        )
        assertTrue(result is OutlookOAuth.OAuthResult.Failure, result.toString())
        val failure = result as OutlookOAuth.OAuthResult.Failure
        assertEquals("access_denied", failure.error)
    }
}
