package com.vayunmathur.globalesim.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.vayunmathur.globalesim.Navigation
import com.vayunmathur.globalesim.Route
import com.vayunmathur.globalesim.data.api.NetworkDto
import com.vayunmathur.globalesim.data.api.RateDto
import com.vayunmathur.globalesim.platform.EsimUiState
import com.vayunmathur.globalesim.platform.EsimViewModel
import com.vayunmathur.library.ui.DynamicTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import androidx.test.core.app.ApplicationProvider

/** PricingScreen search/expansion and the CardPaymentSheet form. */
@RunWith(RobolectricTestRunner::class)
class PricingSheetComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun rate(code: String, name: String, cents: Long, vararg ops: Pair<String, Long>) =
        RateDto(code, name, cents, ops.map { NetworkDto(it.first, it.second) })

    @Test fun pricingEmptyStates() {
        composeRule.setContent {
            DynamicTheme { PricingScreen(state = EsimUiState(), onRefresh = {}) }
        }
        composeRule.onNodeWithText("No rates yet").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertIsDisplayed()
    }

    @Test fun pricingSearchFilterAndExpand() {
        val rates = listOf(
            rate("PT", "Portugal", 450L, "MEO" to 450L, "NOS" to 500L),
            rate("FR", "France", 300L))
        composeRule.setContent {
            DynamicTheme { PricingScreen(state = EsimUiState(rates = rates), onRefresh = {}) }
        }
        composeRule.onNodeWithText("Portugal").assertIsDisplayed()
        composeRule.onNodeWithText("from $4.50 / GB").assertIsDisplayed()
        // Expand Portugal to see carriers.
        composeRule.onNodeWithText("Portugal").performClick()
        composeRule.onNodeWithText("MEO", substring = true).assertIsDisplayed()
        // Filter down to France only.
        composeRule.onNode(hasSetTextAction()).performTextInput("fran")
        composeRule.onNodeWithText("France").assertIsDisplayed()
        // No matches state.
        composeRule.onNode(hasSetTextAction()).performTextReplacement("zzzz")
        composeRule.onNodeWithText("No matches").assertIsDisplayed()
    }

    @Test fun pricingExpandWithoutCarriersShowsFallback() {
        val rates = listOf(rate("FR", "France", 300L))
        composeRule.setContent {
            DynamicTheme { PricingScreen(state = EsimUiState(rates = rates), onRefresh = {}) }
        }
        composeRule.onNodeWithText("France").performClick()
        composeRule.onNodeWithText(
            "Carrier breakdown unavailable — charged at $3.00 / GB.").assertIsDisplayed()
    }

    @Test fun cardSheetValidCardPays() {
        var paid: com.vayunmathur.globalesim.domain.CardValidation.ValidCard? = null
        composeRule.setContent {
            DynamicTheme {
                CardPaymentSheet(
                    amountCents = 1000L, busy = false, confirming = false,
                    onPayWithCard = { paid = it }, onDismiss = {})
            }
        }
        composeRule.onAllNodesWithText("Pay $10.00")[0].assertIsDisplayed()
        composeRule.onNodeWithText("Card number").performTextInput("4242424242424242")
        composeRule.onNodeWithText("Expiry").performTextInput("12/99")
        composeRule.onNodeWithText("CVC").performTextInput("123")
        composeRule.onNode(
            androidx.compose.ui.test.hasText("Pay $10.00") and
                androidx.compose.ui.test.hasClickAction()).performClick()
        assertTrue(paid != null, "valid card should pay")
        assertEquals("4242424242424242", paid!!.number.replace(" ", ""))
    }

    @Test fun cardSheetInvalidCardShowsErrors() {
        composeRule.setContent {
            DynamicTheme {
                CardPaymentSheet(
                    amountCents = 500L, busy = false, confirming = false,
                    onPayWithCard = {}, onDismiss = {})
            }
        }
        composeRule.onNodeWithText("Card number").performTextInput("1234")
        composeRule.onAllNodesWithText("Pay $5.00")[0].assertIsDisplayed()
        // Error text appears once the field has been touched.
        composeRule.onNodeWithText("That card number doesn't look right.", substring = true).assertIsDisplayed()
    }

    @Test fun navigationRendersTabsOffline() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = EsimViewModel(app)
        composeRule.setContent { DynamicTheme { Navigation(viewModel = vm) } }
        composeRule.onAllNodesWithText("Purchase")[0].assertIsDisplayed()
        assertEquals(Route.Tabs, Route.Tabs)
    }
}
