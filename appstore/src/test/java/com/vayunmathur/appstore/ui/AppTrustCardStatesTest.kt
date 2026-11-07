package com.vayunmathur.appstore.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.security.VerificationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The trust card's remaining verification states: a verified install
 * names what was checked, and no verification yet invites the tap to
 * the trust page (which the card forwards).
 */
@RunWith(RobolectricTestRunner::class)
class AppTrustCardStatesTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `a verified install names the checks that passed`() {
        rule.setContent {
            AppTrustCard(
                source = AppSource.FDROID,
                verification = VerificationResult.Verified("hash matches; signed by the key"),
                onOpenTrust = {},
            )
        }

        rule.onNodeWithText("Checked on install: hash matches; signed by the key")
            .assertIsDisplayed()
    }

    @Test
    fun `no verification yet invites opening the trust page`() {
        var opened = 0
        rule.setContent {
            AppTrustCard(
                source = AppSource.FDROID,
                verification = null,
                onOpenTrust = { opened++ },
            )
        }

        rule.onNodeWithText("Tap to see how this source is checked").assertIsDisplayed()
        rule.onNodeWithText("Tap to see how this source is checked").performClick()
        assertEquals(1, opened)
    }
}
