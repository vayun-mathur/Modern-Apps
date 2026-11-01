package com.vayunmathur.travel.platform

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.stripe.android.PaymentConfiguration
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PaymentSheetResult
import com.stripe.android.paymentsheet.rememberPaymentSheet

/** Outcome of the in-app card payment sheet. */
sealed interface CardPaymentResult {
    data object Completed : CardPaymentResult
    data object Canceled : CardPaymentResult
    data class Failed(val message: String) : CardPaymentResult
}

/**
 * Presents the native payment sheet for a server-issued payment intent. The
 * publishable key comes with each checkout because it must match the account
 * and environment the intent was created on.
 */
class CardPayment internal constructor(
    private val context: Context,
    private val sheet: PaymentSheet,
) {
    /** Returns false (without presenting) when the checkout lacks payment details. */
    fun present(clientSecret: String, publishableKey: String): Boolean {
        if (clientSecret.isBlank() || publishableKey.isBlank()) return false
        PaymentConfiguration.init(context, publishableKey)
        sheet.presentWithPaymentIntent(
            clientSecret,
            PaymentSheet.Configuration(merchantDisplayName = MERCHANT_DISPLAY_NAME),
        )
        return true
    }

    private companion object {
        const val MERCHANT_DISPLAY_NAME = "Travel"
    }
}

@Composable
fun rememberCardPayment(onResult: (CardPaymentResult) -> Unit): CardPayment {
    val context = LocalContext.current.applicationContext
    val callback by rememberUpdatedState(onResult)
    val sheet = rememberPaymentSheet { result ->
        callback(
            when (result) {
                is PaymentSheetResult.Completed -> CardPaymentResult.Completed
                is PaymentSheetResult.Canceled -> CardPaymentResult.Canceled
                is PaymentSheetResult.Failed ->
                    CardPaymentResult.Failed(result.error.localizedMessage ?: "Payment failed. Please try again.")
            }
        )
    }
    return remember(sheet) { CardPayment(context, sheet) }
}
