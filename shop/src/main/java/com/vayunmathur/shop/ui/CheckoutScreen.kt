package com.vayunmathur.shop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.DetailSection
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.shop.R
import com.vayunmathur.shop.data.CheckoutPhase
import com.vayunmathur.shop.data.NativeCheckoutState
import com.vayunmathur.shop.domain.MerchantUrls
import com.vayunmathur.shop.domain.ProfileCheckout
import com.vayunmathur.shop.platform.ShopViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Native session review only. Payment and purchase confirmation remain on the merchant website. */
@Composable
fun CheckoutScreen(viewModel: ShopViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        while (isActive) { viewModel.refreshCheckoutExpiry(); delay(1000) }
    }
    AppScaffold(title = stringResource(R.string.checkout), onNavigateBack = onBack, scrollBehavior = appBarScrollBehavior()) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text("Review a merchant checkout session. No order is placed or payment collected here.")
                Text("Your profile details are sent only when you press Create or Update.", style = MaterialTheme.typography.bodySmall)
            }
            viewModel.cart.groupBy { it.platformId to it.shopDomain }.forEach { (key, lines) ->
                val state = viewModel.nativeCheckouts.firstOrNull { it.platformId == key.first && it.shopDomain == key.second }
                    ?: NativeCheckoutState(key.first, key.second)
                item(key = "merchant-${key.first}-${key.second}") {
                    DetailSection(key.second.ifBlank { "Unknown store" }) {
                        lines.forEach { line -> Text("${line.productTitle} x ${line.quantity}", style = MaterialTheme.typography.bodyMedium) }
                        val editable = !state.busy && !viewModel.placingCheckout && state.phase !in setOf(CheckoutPhase.Uncertain, CheckoutPhase.Expired, CheckoutPhase.BrowserRequired)
                        val profileInputs = viewModel.checkoutInputs(state)
                        Text("Buyer details come from your profile.", style = MaterialTheme.typography.bodySmall)
                        val checkout = state.checkout
                        if (ProfileCheckout.needsDeliveryAddress(checkout) && viewModel.profile?.hasAddress != true) {
                            Text("This merchant needs a delivery address. Add one in the Profile tab, then update the session.", color = MaterialTheme.colorScheme.error)
                        }
                        if (checkout != null) {
                            CheckoutReviewContent(
                                state = state,
                                editable = editable,
                                onInputsChange = { viewModel.editCheckoutInputs(key.first, key.second, it) },
                                onOpenUrl = { openContinueUrl(context, it) },
                                onRefresh = { viewModel.reconcileCheckout(key.first, key.second) },
                            )
                        }
                        state.message?.let { Text(it) }
                        if (state.phase == CheckoutPhase.Expired) Text("Session expired. Native updates are disabled.")
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = editable && profileInputs != null && (checkout == null || profileInputs != state.submittedInputs),
                            onClick = { viewModel.prepareMerchantCheckout(key.first, key.second, context) },
                        ) {
                            if (state.busy) CircularProgressIndicator()
                            else Text(if (checkout == null) "Create review session" else "Update review session")
                        }
                        val merchantSiteUrl = MerchantUrls.domain(key.second)?.let { "https://$it" }
                        val checkoutUrl = checkout?.continueUrl?.let(MerchantUrls::httpsUrl)
                        val cartUrl = MerchantUrls.shopifyCartUrl(key.second, lines.map { it.variantId to it.quantity })
                        (checkoutUrl ?: cartUrl ?: merchantSiteUrl)?.let { url ->
                            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { openContinueUrl(context, url) }) {
                                Text(when (url) {
                                    checkoutUrl -> "Continue checkout on merchant website"
                                    cartUrl -> "Open cart on merchant website"
                                    else -> "Open merchant website"
                                })
                            }
                        }
                    }
                }
            }
            if (viewModel.cart.isEmpty()) item { Text("Your cart is empty.") }
        }
    }
}
