package com.vayunmathur.shop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.DetailSection
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.shop.R
import com.vayunmathur.shop.data.CheckoutInputs
import com.vayunmathur.shop.data.CheckoutPhase
import com.vayunmathur.shop.data.NativeCheckoutState
import com.vayunmathur.shop.domain.MerchantUrls
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
                Text("Buyer details stay in this app session and are sent only when you press Create or Update.", style = MaterialTheme.typography.bodySmall)
            }
            viewModel.cart.groupBy { it.platformId to it.shopDomain }.forEach { (key, lines) ->
                val state = viewModel.nativeCheckouts.firstOrNull { it.platformId == key.first && it.shopDomain == key.second }
                    ?: NativeCheckoutState(key.first, key.second)
                item(key = "merchant-${key.first}-${key.second}") {
                    DetailSection(key.second.ifBlank { "Unknown store" }) {
                        lines.forEach { line -> Text("${line.productTitle} x ${line.quantity}", style = MaterialTheme.typography.bodyMedium) }
                        val editable = !state.busy && !viewModel.placingCheckout && state.phase !in setOf(CheckoutPhase.Uncertain, CheckoutPhase.Expired, CheckoutPhase.BrowserRequired)
                        if (state.checkout?.review?.availableShippingLineIds?.isNotEmpty() == true && state.checkout.review.shipping.isEmpty()) {
                            FilterChip(selected = state.inputs.shippingRequested, enabled = editable,
                                onClick = { viewModel.editCheckoutInputs(key.first, key.second, state.inputs.copy(shippingRequested = true)) },
                                label = { Text("Use merchant-advertised shipping") })
                            Text("Select shipping, add an address and update to obtain delivery options.", style = MaterialTheme.typography.bodySmall)
                        }
                        CheckoutBuyerForm(state.inputs, editable, state.checkout?.review?.shipping?.isNotEmpty() == true || state.inputs.shippingRequested) {
                            viewModel.editCheckoutInputs(key.first, key.second, it)
                        }
                        val checkout = state.checkout
                        if (checkout != null) {
                            CheckoutReviewContent(
                                state = state,
                                editable = editable,
                                onInputsChange = { viewModel.editCheckoutInputs(key.first, key.second, it) },
                                onOpenUrl = { openContinueUrl(context, it) },
                                onRefresh = { viewModel.reconcileCheckout(key.first, key.second) },
                            )
                        }
                        state.inputs.validationError()?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        state.message?.let { Text(it) }
                        if (state.phase == CheckoutPhase.Expired) Text("Session expired. Native updates are disabled.")
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = editable && state.inputs.validationError() == null && (checkout == null || state.inputs != state.submittedInputs),
                            onClick = { viewModel.prepareMerchantCheckout(key.first, key.second, context) },
                        ) {
                            if (state.busy) CircularProgressIndicator()
                            else Text(if (checkout == null) "Create review session" else "Update review session")
                        }
                        val browserUrl = checkout?.continueUrl?.let(MerchantUrls::httpsUrl)
                            ?: MerchantUrls.domain(key.second)?.let { "https://$it" }
                        if (browserUrl != null) {
                            TextButton(onClick = { openContinueUrl(context, browserUrl) }) {
                                Text(if (checkout?.continueUrl?.let(MerchantUrls::httpsUrl) != null) "Continue in browser" else "Open merchant website")
                            }
                        }
                    }
                }
            }
            if (viewModel.cart.isEmpty()) item { Text("Your cart is empty.") }
        }
    }
}

@Composable
private fun CheckoutBuyerForm(inputs: CheckoutInputs, enabled: Boolean, shipping: Boolean, onChange: (CheckoutInputs) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BuyerField("First name", inputs.firstName, enabled) { onChange(inputs.copy(firstName = it)) }
        BuyerField("Last name", inputs.lastName, enabled) { onChange(inputs.copy(lastName = it)) }
        BuyerField("Email", inputs.email, enabled, KeyboardType.Email) { onChange(inputs.copy(email = it)) }
        BuyerField("Phone (international format)", inputs.phone, enabled, KeyboardType.Phone) { onChange(inputs.copy(phone = it)) }
        if (shipping) {
            Text("Shipping address", style = MaterialTheme.typography.titleSmall)
            BuyerField("Street address", inputs.street, enabled) { onChange(inputs.copy(street = it)) }
            BuyerField("Apartment / additional address", inputs.extendedAddress, enabled) { onChange(inputs.copy(extendedAddress = it)) }
            BuyerField("City", inputs.city, enabled) { onChange(inputs.copy(city = it)) }
            BuyerField("State / province", inputs.region, enabled) { onChange(inputs.copy(region = it)) }
            BuyerField("Country (two-letter code)", inputs.country, enabled) { onChange(inputs.copy(country = it.uppercase())) }
            BuyerField("Postal code", inputs.postalCode, enabled) { onChange(inputs.copy(postalCode = it)) }
        } else Text("Create a session first to discover the merchant's shipping choices.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BuyerField(label: String, value: String, enabled: Boolean, keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, enabled = enabled, label = { Text(label) },
        modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = keyboard))
}
