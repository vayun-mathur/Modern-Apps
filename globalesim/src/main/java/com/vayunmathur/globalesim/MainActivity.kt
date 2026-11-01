package com.vayunmathur.globalesim

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.stripe.android.payments.paymentlauncher.PaymentLauncher
import com.stripe.android.payments.paymentlauncher.PaymentResult
import com.vayunmathur.globalesim.platform.EsimViewModel
import com.vayunmathur.globalesim.ui.CardPaymentLaunchers
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.library.ui.MaActivity

class MainActivity : MaActivity() {
    private val viewModel: EsimViewModel by viewModels()

    /**
     * Stripe card launcher. Created here in onCreate (before STARTED) because
     * ActivityResult registration after STARTED throws — it must never be
     * created in tab composition, which the pager can run after RESUMED.
     * Uses the no-key overload (reads PaymentConfiguration lazily per
     * transaction; the purchase flow inits it from /esim/v1/stripe-key).
     * Results route straight into the ViewModel; its one-shot `message` is
     * already rendered by the tab host.
     */
    private lateinit var paymentLaunchers: CardPaymentLaunchers

    override fun onCreateBeforeContent() {
        // First-party backend only (api.maproj.org): Cloudflare Universal SSL
        // roots in FIRST_PARTY. The reseller vendor is never contacted here —
        // all vendor calls stay server-side behind /esim/v1/*.
        NetworkClient.init(this, TrustBundle.FIRST_PARTY)
        val paymentLauncher = PaymentLauncher.create(this) { result ->
            when (result) {
                is PaymentResult.Completed -> {
                    viewModel.notifyMessage("Payment complete — credit lands shortly.")
                    viewModel.finishCardPurchase(true)
                }
                is PaymentResult.Canceled -> viewModel.finishCardPurchase(false)
                is PaymentResult.Failed ->
                    viewModel.finishCardPurchaseWithError(
                        result.throwable.localizedMessage ?: "Payment failed",
                    )
            }
        }
        paymentLaunchers = CardPaymentLaunchers(paymentLauncher)
    }

    @Composable
    override fun Content() {
        Navigation(viewModel, paymentLaunchers)
    }
}
