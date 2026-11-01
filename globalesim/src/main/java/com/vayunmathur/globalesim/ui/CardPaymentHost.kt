package com.vayunmathur.globalesim.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.stripe.android.PaymentConfiguration
import com.stripe.android.model.CardParams
import com.stripe.android.model.ConfirmPaymentIntentParams
import com.stripe.android.model.PaymentMethodCreateParams
import com.stripe.android.payments.paymentlauncher.PaymentLauncher
import com.vayunmathur.globalesim.data.StripeKeyStore
import com.vayunmathur.globalesim.domain.CardValidation.ValidCard
import com.vayunmathur.globalesim.platform.EsimUiState

/**
 * Stripe launcher for card payment, created once in [com.vayunmathur.globalesim.MainActivity]
 * (inside onCreate, before STARTED — ActivityResult registration is illegal
 * after that point, so it must never be created in tab composition, which
 * the pager can run after RESUMED). Null in previews.
 */
data class CardPaymentLaunchers(
    val paymentLauncher: PaymentLauncher,
)
/**
 * Card-payment orchestration for the Purchase tab: our own form
 * ([CardPaymentSheet]) confirmed via the activity-created [PaymentLauncher].
 * The ViewModel still owns secret fetch + credit watch and receives launcher
 * results directly — this host only presents, confirms, and closes the sheet
 * when the flow ends.
 *
 * Extracted from HomeScreen to keep ui files under the 350-line limit.
 * Previews pass null [launchers] (plus [onPresentPaymentSheet]) and render
 * launcher-free.
 */
@Composable
fun CardPaymentHost(
    state: EsimUiState,
    launchers: CardPaymentLaunchers?,
    sheetAmountCents: Long?,
    onDismissSheet: () -> Unit,
    onSheetAmountConsumed: () -> Unit,
    onStartCardPurchase: (amountCents: Long, publishableKey: String?, onKey: (String) -> Unit) -> Unit,
    onPresentPaymentSheet: ((secret: String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val keyStore = remember { StripeKeyStore(context) }
    var publishableKey by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf(false) }
    // The validated card waits only for the secret; cleared on confirm.
    // Never logged or persisted — consumed into Stripe params immediately.
    var pendingCard by remember { mutableStateOf<ValidCard?>(null) }
    // Confirm each client secret once. Saved across recreation so a rotation
    // with the secret still pending doesn't confirm a second time.
    val pendingSecret = state.pendingClientSecret
    var presentedSecret by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingSecret) {
        if (pendingSecret != null && pendingSecret != presentedSecret) {
            presentedSecret = pendingSecret
            if (onPresentPaymentSheet != null) {
                onPresentPaymentSheet.invoke(pendingSecret)
            } else {
                val card = pendingCard
                pendingCard = null
                val paymentLauncher = launchers?.paymentLauncher
                if (card != null && paymentLauncher != null) {
                    val cardParams = CardParams(
                        number = card.number,
                        expMonth = card.expMonth,
                        expYear = card.expYear,
                        cvc = card.cvc,
                    )
                    val methodParams = PaymentMethodCreateParams.createCard(cardParams)
                    val confirmParams = ConfirmPaymentIntentParams.createWithPaymentMethodCreateParams(
                        paymentMethodCreateParams = methodParams,
                        clientSecret = pendingSecret,
                    )
                    confirming = true
                    paymentLauncher.confirm(confirmParams)
                }
            }
        }
    }
    // The launcher callback clears the secret via finishCardPurchase; that is
    // the flow-done signal: close the sheet and drop the confirming state.
    LaunchedEffect(pendingSecret, confirming, sheetAmountCents) {
        if (sheetAmountCents != null && confirming && pendingSecret == null && !state.loading) {
            confirming = false
            onSheetAmountConsumed()
        }
    }
    if (sheetAmountCents != null) {
        CardPaymentSheet(
            amountCents = sheetAmountCents,
            busy = state.loading,
            confirming = confirming,
            onPayWithCard = { card ->
                pendingCard = card
                onStartCardPurchase(sheetAmountCents, publishableKey) { key ->
                    publishableKey = key
                    keyStore.save(key)
                    PaymentConfiguration.init(context, key)
                }
            },
            onDismiss = {
                if (!confirming && state.pendingClientSecret == null) {
                    pendingCard = null
                    onDismissSheet()
                }
            },
        )
    }
}
