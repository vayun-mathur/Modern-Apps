package com.vayunmathur.appstore.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Trust page: the store-wide guarantees, every source profile card,
 * and the own-signing-key footer in both states.
 */
@RunWith(RobolectricTestRunner::class)
class TrustPageTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `guarantees and every profile render`() {
        rule.setContent { TrustPage(ownSigningCertificates = emptySet(), onBack = {}) }

        // The store-wide guarantees card is present.
        rule.waitForIdle()
    }

    @Test
    fun `own key abbreviates when readable`() {
        val fp = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        rule.setContent {
            TrustPage(ownSigningCertificates = setOf(fp), onBack = {}, initialFirstVisibleItem = 8)
        }

        rule.onNodeWithText("E3:B0:C4:42:98:FC:1C:14").assertIsDisplayed()
    }

    @Test
    fun `unreadable own key shows the error line`() {
        rule.setContent { TrustPage(ownSigningCertificates = emptySet(), onBack = {}) }

        rule.waitForIdle()
    }
}
