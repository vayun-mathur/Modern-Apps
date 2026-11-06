package com.vayunmathur.communicate.signal

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.communicate.data.signal.registration.RegistrationHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Wave 9 (w6 slice 2/6): registration UI constants — the captcha URL/scheme contract between
 * [RegistrationHttpClient] and the captcha WebView — plus a Compose smoke test of the captcha
 * redirect-token stripping rule (the pure string half of the WebView client).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SignalSlice2RegistrationUiTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun captchaConstants_matchServerContract() {
        assertEquals(
            "https://signalcaptchas.org/registration/generate.html",
            RegistrationHttpClient.CAPTCHA_URL,
        )
        assertEquals("signalcaptcha://", RegistrationHttpClient.SIGNAL_CAPTCHA_SCHEME)
    }

    @Test
    fun captchaRedirect_stripsSchemeToToken() {
        val url = "signalcaptcha://signal-hcaptcha.sitekey.registration.abc123"
        assertTrue(url.startsWith(RegistrationHttpClient.SIGNAL_CAPTCHA_SCHEME))
        val token = url.substring(RegistrationHttpClient.SIGNAL_CAPTCHA_SCHEME.length)
        assertEquals("signal-hcaptcha.sitekey.registration.abc123", token)
        // Non-captcha URLs are not tokens.
        assertTrue(!"https://example.com/".startsWith(RegistrationHttpClient.SIGNAL_CAPTCHA_SCHEME))
    }

    @Test
    fun captchaToken_composeRoundTrip() {
        var received: String? = null
        rule.setContent {
            androidx.compose.material3.Button(
                onClick = {
                    val url = "signalcaptcha://tok123"
                    if (url.startsWith(RegistrationHttpClient.SIGNAL_CAPTCHA_SCHEME)) {
                        received = url.substring(RegistrationHttpClient.SIGNAL_CAPTCHA_SCHEME.length)
                    }
                },
            ) {
                androidx.compose.material3.Text("Solve captcha")
            }
        }
        rule.onNodeWithText("Solve captcha").assertIsDisplayed()
        rule.onNodeWithText("Solve captcha").performClick()
        rule.runOnIdle {
            assertEquals("tok123", received)
        }
    }
}
