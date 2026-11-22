package com.vayunmathur.fooddelivery.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.stripe.android.paymentsheet.PaymentSheet
import com.vayunmathur.fooddelivery.R
import com.vayunmathur.fooddelivery.data.CheckoutDraft
import com.vayunmathur.fooddelivery.data.CheckoutDraftStore
import com.vayunmathur.fooddelivery.data.CheckoutResponse
import com.vayunmathur.fooddelivery.data.SavedAddress
import com.vayunmathur.fooddelivery.data.paymentIntentId
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.Text
import kotlinx.coroutines.launch

/** The place-order card: persists the presented draft, then shows the payment sheet. */
@Composable
fun CheckoutPlaceOrderCard(
    checkoutResponse: CheckoutResponse?,
    merchantId: Int,
    isPickup: Boolean,
    tipCents: Int,
    promoCode: String,
    selectedDealId: Int?,
    selectedAddress: SavedAddress?,
    deliveryInstructions: String,
    merchantName: String?,
    paying: Boolean,
    fetchingPrices: Boolean,
    payTotal: Double?,
    onPayingChange: (Boolean) -> Unit,
    paymentSheet: PaymentSheet,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Button(
                onClick = {
                    val response = checkoutResponse ?: return@Button
                    onPayingChange(true)
                    scope.launch {
                        // Persist presented=true BEFORE the sheet shows: death
                        // while it is up is then an unknown payment outcome,
                        // reconciled on entry — never a fresh checkout.
                        CheckoutDraftStore.save(
                            context,
                            CheckoutDraft(
                                merchantId = merchantId,
                                orderUuid = response.order?.uuid,
                                paymentIntentId = paymentIntentId(response.clientSecret),
                                clientSecret = response.clientSecret.takeIf { it.isNotBlank() },
                                isPickup = isPickup,
                                tipCents = tipCents,
                                promoCode = promoCode,
                                dealId = selectedDealId,
                                addressId = selectedAddress?.id,
                                deliveryInstructions = deliveryInstructions,
                                paymentPresented = true,
                                savedAtMs = System.currentTimeMillis(),
                            ),
                        )
                        paymentSheet.presentWithPaymentIntent(
                            response.clientSecret,
                            PaymentSheet.Configuration(
                                merchantDisplayName = merchantName ?: "Food Delivery",
                            ),
                        )
                    }
                },
                enabled = checkoutResponse != null && !paying && !fetchingPrices,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (paying || fetchingPrices) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                } else {
                    if (payTotal != null) {
                        Text("Place Order · $%.2f".format(payTotal))
                    } else {
                        Text(stringResource(R.string.place_order))
                    }
                }
            }
        }
    }
}
