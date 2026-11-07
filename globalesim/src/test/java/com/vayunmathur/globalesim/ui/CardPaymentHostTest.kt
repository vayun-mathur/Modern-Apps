package com.vayunmathur.globalesim.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.globalesim.MainActivity
import com.vayunmathur.globalesim.data.StripeKeyStore
import com.vayunmathur.globalesim.data.api.AccountRequest
import com.vayunmathur.globalesim.data.api.NetworkDto
import com.vayunmathur.globalesim.data.api.NowPayInvoiceRequest
import com.vayunmathur.globalesim.data.api.NowPayInvoiceResponse
import com.vayunmathur.globalesim.data.api.OkResponse
import com.vayunmathur.globalesim.data.api.RateDto
import com.vayunmathur.globalesim.data.api.RatesResponse
import com.vayunmathur.globalesim.data.api.StatusResponse
import com.vayunmathur.globalesim.data.api.StripeIntentRequest
import com.vayunmathur.globalesim.data.api.StripeIntentResponse
import com.vayunmathur.globalesim.data.api.StripeKeyResponse
import com.vayunmathur.globalesim.data.api.TransferInRequest
import com.vayunmathur.globalesim.platform.EsimUiState
import com.vayunmathur.library.ui.DynamicTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** CardPaymentHost secret-presentation and sheet pay flow, plus DTO defaults. */
@RunWith(RobolectricTestRunner::class)
class CardPaymentHostTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test fun pendingSecretPresentedOnceToHoistedCallback() {
        val presented = mutableListOf<String>()
        composeRule.setContent {
            DynamicTheme {
                CardPaymentHost(
                    state = EsimUiState(pendingClientSecret = "secret_123"),
                    launchers = null,
                    sheetAmountCents = null,
                    onDismissSheet = {},
                    onSheetAmountConsumed = {},
                    onStartCardPurchase = { _, _, _ -> },
                    onPresentPaymentSheet = { presented.add(it) })
            }
        }
        composeRule.waitForIdle()
        assertEquals(listOf("secret_123"), presented)
    }

    @Test fun sheetPayFlowStartsPurchaseAndCachesKey() {
        var startedAmount: Long? = null
        composeRule.setContent {
            DynamicTheme {
                CardPaymentHost(
                    state = EsimUiState(),
                    launchers = null,
                    sheetAmountCents = 1500L,
                    onDismissSheet = {},
                    onSheetAmountConsumed = {},
                    onStartCardPurchase = { amount, _, onKey ->
                        startedAmount = amount
                        onKey("pk_test_host")
                    })
            }
        }
        composeRule.onAllNodesWithText("Pay $15.00")[0].assertIsDisplayed()
        composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("4242424242424242")
        composeRule.onAllNodes(hasSetTextAction())[1].performTextInput("12/99")
        composeRule.onAllNodes(hasSetTextAction())[2].performTextInput("123")
        composeRule.onNode(hasText("Pay $15.00") and hasClickAction()).performClick()
        assertEquals(1500L, startedAmount)
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals("pk_test_host", StripeKeyStore(ctx).getCached())
    }

    @Test fun mainActivityLaunchesToPurchaseTab() {
        androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            composeRule.waitForIdle()
            scenario.onActivity { activity ->
                assertTrue(activity.javaClass == MainActivity::class.java)
            }
            composeRule.onAllNodesWithText("Purchase")[0].assertIsDisplayed()
        }
    }

    @Test fun dtoDefaultsAndCopies() {
        assertEquals("a", AccountRequest("a").accountId)
        assertEquals("old", TransferInRequest("old", "new").copy(newAccountId = "x").oldAccountId)
        assertEquals(0, RatesResponse().rates.size)
        assertEquals("", StripeKeyResponse().publishableKey)
        assertEquals("", StripeIntentResponse().clientSecret)
        assertEquals(500L, StripeIntentRequest("a", 500L).amountCents)
        assertEquals(500L, NowPayInvoiceRequest("a", 500L).amountCents)
        val invoice = NowPayInvoiceResponse()
        assertEquals("", invoice.invoiceUrl)
        assertEquals(false, invoice.existing)
        assertEquals(false, OkResponse().ok)
        assertEquals(null, StatusResponse().profile)
        val rate = RateDto("PT", "Portugal", 100L)
        assertEquals(emptyList(), rate.networks)
        assertEquals(100L, NetworkDto("MEO", 100L).pricePerGbUsdCents)
        assertTrue(RateDto("PT", "Portugal", 100L) == rate)
        assertTrue(RateDto("PT", "Portugal", 100L).hashCode() == rate.hashCode())
        assertTrue(rate.toString().contains("Portugal"))
    }
}
