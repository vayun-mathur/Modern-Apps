package com.vayunmathur.globalesim.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.globalesim.data.api.ProfileDto
import org.robolectric.Shadows
import com.vayunmathur.globalesim.platform.EsimUiState
import com.vayunmathur.library.ui.DynamicTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** HomeScreen states and TopUpCard/ClaimCard interactions. */
@RunWith(RobolectricTestRunner::class)
class HomeTopUpComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun home(
        state: EsimUiState,
        onClaim: (String) -> Unit = {},
        onStartCryptoPurchase: (Long) -> Unit = {},
    ) {
        composeRule.setContent {
            DynamicTheme {
                HomeScreen(
                    state = state,
                    onStartCardPurchase = { _, _, _ -> },
                    onStartCryptoPurchase = onStartCryptoPurchase,
                    onClearCryptoInvoice = {},
                    onClaim = onClaim,
                    onRefresh = {})
            }
        }
    }

    @Test fun loadingStateShowsPurchaseShell() {
        home(EsimUiState(statusLoaded = false))
        composeRule.onNodeWithText("Purchase").assertIsDisplayed()
    }

    @Test fun noProfileShowsSetupTermsAndClaim() {
        var claimed: String? = null
        home(EsimUiState(statusLoaded = true), onClaim = { claimed = it })
        composeRule.onNodeWithText("One global eSIM for 200+ countries").assertIsDisplayed()
        composeRule.onNodeWithText("$3 one-time setup fee").assertIsDisplayed()
        composeRule.onNodeWithText("Top up amount (USD)").assertIsDisplayed()
        composeRule.onAllNodes(hasSetTextAction())[1].performTextInput("sim-abc")
        composeRule.onNodeWithText("sim-abc").assertExists()
        composeRule.onNode(
            androidx.compose.ui.test.hasText("Claim on this phone") and
                androidx.compose.ui.test.hasClickAction())
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        assertEquals("sim-abc", claimed)
    }

    @Test fun pendingCreditShowsCreatingMessage() {
        home(EsimUiState(statusLoaded = true, pendingCreditCents = 500L))
        composeRule.onNodeWithText("Payment received").assertIsDisplayed()
        composeRule.onNodeWithText("Creating your eSIM. This page updates on its own.").assertIsDisplayed()
    }

    @Test fun profileShowsBalanceAndActions() {
        home(
            EsimUiState(
                statusLoaded = true,
                simId = "sim-xyz",
                profile = ProfileDto(
                    iccid = "8901", status = "active", walletUsdCents = 1234L,
                    activationCode = "CODE1", smdpAddress = "smdp.example")))
        composeRule.onNodeWithText("$12.34").assertIsDisplayed()
        composeRule.onNodeWithText("Balance · Active").assertIsDisplayed()
        composeRule.onNodeWithText("Install eSIM").assertIsDisplayed()
        composeRule.onNodeWithText("Copy SIM ID").assertIsDisplayed()
    }

    @Test fun profileStaleUnknownBalanceIsDash() {
        home(
            EsimUiState(
                statusLoaded = true,
                balanceStale = true,
                profile = ProfileDto(iccid = "1", status = "unknown", walletUsdCents = 0L),
                pendingCreditCents = 200L))
        composeRule.onNodeWithText("—").assertIsDisplayed()
        composeRule.onNodeWithText("$2.00 on its way. No need to pay again.").assertIsDisplayed()
    }

    private fun assertStatusLabel(status: String, label: String) {
        home(
            EsimUiState(
                statusLoaded = true,
                profile = ProfileDto(iccid = "1", status = status, walletUsdCents = 100L)))
        composeRule.onNodeWithText(label).assertIsDisplayed()
    }

    @Test fun statusPendingIsActivating() = assertStatusLabel("pending", "Balance · Activating")
    @Test fun statusSuspendedIsPaused() = assertStatusLabel("suspended", "Balance · Paused")
    @Test fun statusTerminated() = assertStatusLabel("terminated", "Balance · Terminated")
    @Test fun statusUnknownIsChecking() = assertStatusLabel("unknown", "Balance · Checking…")
    @Test fun statusCustomIsCapitalized() = assertStatusLabel("weird", "Balance · Weird")

    @Test fun profileInstallAndCopyActions() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        home(
            EsimUiState(
                statusLoaded = true,
                simId = "sim-copy-me",
                profile = ProfileDto(
                    iccid = "8901", status = "active", walletUsdCents = 100L,
                    activationCode = "CODE9", smdpAddress = "smdp.example")))
        composeRule.onNodeWithText("Install eSIM").performClick()
        val intent = Shadows.shadowOf(ctx as android.app.Application).nextStartedActivity
        assertTrue(intent.data.toString().contains("esim_qrcode_provisioning"), "${intent.data}")
        assertTrue(intent.data.toString().contains("LPA:1\$smdp.example\$CODE9"), "${intent.data}")
        composeRule.onNodeWithText("Copy SIM ID").performClick()
        val clipboard = ctx.getSystemService(android.content.ClipboardManager::class.java)
        assertEquals("sim-copy-me", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test fun topUpAmountValidationAndBuy() {
        var card: Long? = null
        var crypto: Long? = null
        composeRule.setContent {
            DynamicTheme {
                TopUpCard(
                    busy = false,
                    onBuyWithCard = { card = it },
                    onBuyWithCrypto = { crypto = it },
                    pendingInvoiceUrl = null,
                    onClearCryptoInvoice = {})
            }
        }
        // Too small.
        composeRule.onNode(hasSetTextAction()).performTextInput("3")
        composeRule.onNodeWithText("Minimum $5.00 payment.").assertIsDisplayed()
        // Valid amount enables both rails.
        composeRule.onNode(hasSetTextAction()).performTextReplacement("12.50")
        composeRule.onNodeWithText("Card topup").performClick()
        assertEquals(1250L, card)
        composeRule.onNodeWithText("Crypto topup").performClick()
        assertEquals(1250L, crypto)
    }

    @Test fun topUpInvalidAndTooLargeAmounts() {
        composeRule.setContent {
            DynamicTheme {
                TopUpCard(
                    busy = false,
                    onBuyWithCard = {}, onBuyWithCrypto = {},
                    pendingInvoiceUrl = null, onClearCryptoInvoice = {})
            }
        }
        composeRule.onNode(hasSetTextAction()).performTextInput("abc")
        // Non-numeric input filters to blank through QuoteMath parsing; type digits beyond max.
        composeRule.onNode(hasSetTextAction()).performTextReplacement("25")
        composeRule.onNodeWithText("$20.00 max payment.").assertIsDisplayed()
    }

    @Test fun topUpPendingInvoiceRow() {
        var cleared = 0
        composeRule.setContent {
            DynamicTheme {
                TopUpCard(
                    busy = false,
                    onBuyWithCard = {}, onBuyWithCrypto = {},
                    pendingInvoiceUrl = "https://pay.example/invoice",
                    onClearCryptoInvoice = { cleared++ })
            }
        }
        composeRule.onNodeWithText("Reopen checkout").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        assertEquals(1, cleared)
        assertTrue(true)
    }
}
