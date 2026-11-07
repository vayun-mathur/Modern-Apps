package com.vayunmathur.communicate.ui.googlevoice

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.R
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Slice-4 pass-2: GV WebView sign-in screen renders its scaffold under Robolectric. */
@RunWith(RobolectricTestRunner::class)
class GoogleVoiceSignInSlice4Pass2Test {

    @get:Rule val rule = createComposeRule()

    @Test
    fun signInScreenShowsTitleAndWebView() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        var back = 0
        var signedIn = 0
        rule.setContent {
            GoogleVoiceSignInScreen(onBack = { back++ }, onSignedIn = { signedIn++ })
        }
        rule.onNodeWithText(ctx.getString(R.string.gv_sign_in_title)).assertIsDisplayed()
        assertEquals(0, back)
        assertEquals(0, signedIn)
    }
}
