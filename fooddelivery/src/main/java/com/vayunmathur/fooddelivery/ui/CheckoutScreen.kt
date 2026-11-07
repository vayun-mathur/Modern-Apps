package com.vayunmathur.fooddelivery.ui

import com.vayunmathur.library.log.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.stripe.android.PaymentConfiguration
import com.stripe.android.Stripe
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PaymentSheetResult
import com.stripe.android.paymentsheet.rememberPaymentSheet
import com.vayunmathur.fooddelivery.BuildConfig
import com.vayunmathur.fooddelivery.R
import com.vayunmathur.fooddelivery.api.BitesCustomers
import com.vayunmathur.fooddelivery.api.BitesDeals
import com.vayunmathur.fooddelivery.api.BitesOrders
import com.vayunmathur.fooddelivery.data.AddressStore
import com.vayunmathur.fooddelivery.data.CartItem
import com.vayunmathur.fooddelivery.data.CheckoutAddress
import com.vayunmathur.fooddelivery.data.CheckoutCartItem
import com.vayunmathur.fooddelivery.data.CheckoutRequest
import com.vayunmathur.fooddelivery.data.CheckoutResponse
import com.vayunmathur.fooddelivery.data.Customer
import com.vayunmathur.fooddelivery.data.Deal
import com.vayunmathur.fooddelivery.data.OrderRewards
import com.vayunmathur.fooddelivery.data.SavedAddress
import com.vayunmathur.fooddelivery.notifications.OrderTrackingService
import com.vayunmathur.fooddelivery.platform.AppInit
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun CheckoutScreen(
    items: List<CartItem>,
    onBack: () -> Unit,
    onOrderPlaced: () -> Unit,
    // Payment has succeeded: clear the cart, but stay here so the confirmation
    // is actually seen. Defaults to onOrderPlaced for callers that want the old
    // navigate-away-immediately behaviour.
    onOrderCompleted: () -> Unit = onOrderPlaced,
) {
    val context = LocalContext.current

    var isPickup by remember { mutableStateOf(false) }
    var tipCents by remember { mutableIntStateOf(300) }
    var deliveryInstructions by remember { mutableStateOf("") }
    var paying by remember { mutableStateOf(false) }
    var fetchingPrices by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var orderSuccess by remember { mutableStateOf(false) }
    var checkoutResponse by remember { mutableStateOf<CheckoutResponse?>(null) }

    var promoCode by remember { mutableStateOf("") }
    var customer by remember { mutableStateOf<Customer?>(null) }
    /** Reward credit applied to this order, per GET /orders/{uuid}/rewards. */
    var rewards by remember { mutableStateOf<OrderRewards?>(null) }
    var deals by remember { mutableStateOf<List<Deal>>(emptyList()) }
    /** Order created by the last successful checkout call; re-priced in place, not re-created. */
    var lastOrderUuid by remember { mutableStateOf<String?>(null) }
    var selectedDealId by remember { mutableStateOf<Int?>(null) }

    // One prefs read plus one JSON decode, off the main thread — the default address is
    // picked out of the list already in hand rather than re-reading the store for it.
    var addresses by remember { mutableStateOf<List<SavedAddress>>(emptyList()) }
    var addressesLoaded by remember { mutableStateOf(false) }
    var selectedAddress by remember { mutableStateOf<SavedAddress?>(null) }

    LaunchedEffect(Unit) {
        val all = AddressStore.getAll(context)
        val default = all.firstOrNull { it.isDefault } ?: all.firstOrNull()
        addresses = all
        selectedAddress = default
        // Don't overwrite anything typed while the read was in flight.
        if (deliveryInstructions.isEmpty()) {
            deliveryInstructions = default?.deliveryInstructions ?: ""
        }
        addressesLoaded = true
    }

    // The reference sends the customer's identity with every checkout.
    LaunchedEffect(Unit) {
        AppInit.awaitReady()
        customer = BitesCustomers.getCustomer()
    }

    val subtotalCents = items.sumOf {
        (it.menuItem.price + it.selectedModifiers.sumOf { m -> m.price * m.quantity }) * it.quantity
    }
    val subtotal = subtotalCents / 100.0

    val confirmedOrder = checkoutResponse?.order
    /** Reference formula: component sum minus the reward credit applied to this order. */
    val rewardsApplied = (rewards?.rewardsAvailable ?: 0) / 100.0
    val payTotal = confirmedOrder?.let { it.componentTotal - rewardsApplied }
    val merchantId = items.firstOrNull()?.merchantId ?: 0
    val canFetch = items.isNotEmpty() && (isPickup || selectedAddress != null)

    // Deals the merchant currently has running; picking one sends its dealId with checkout.
    LaunchedEffect(merchantId) {
        AppInit.awaitReady()
        deals = if (merchantId != 0) BitesDeals.getActiveDealsByMerchant(merchantId) else emptyList()
    }

    LaunchedEffect(isPickup, tipCents, selectedAddress?.id, promoCode, customer?.uuid, selectedDealId) {
        if (!canFetch) return@LaunchedEffect
        AppInit.awaitReady()
        checkoutResponse = null
        error = null
        fetchingPrices = true
        delay(400)
        // Modifiers already carry their group id, price and quantity from selection time,
        // so they go over the wire exactly as the reference cart stores them.
        val cartItems = items.map { item ->
            CheckoutCartItem(
                itemId = item.menuItem.id,
                quantity = item.quantity,
                specialInstructions = item.specialInstructions,
                modifiers = item.selectedModifiers,
            )
        }
        val addr = if (!isPickup) selectedAddress?.let { a ->
            CheckoutAddress(
                addressStreet = a.addressStreet,
                addressCity = a.addressCity,
                addressState = a.addressState,
                addressZip = a.addressZip,
                addressUnit = a.aptUnit,
                latitude = a.latitude,
                longitude = a.longitude,
            )
        } else null
        val request = CheckoutRequest(
            cartItems = cartItems,
            address = addr,
            isPickup = isPickup,
            tips = tipCents,
            promoCode = promoCode.trim().ifBlank { null },
            dealId = selectedDealId,
            deliveryInstructions = deliveryInstructions.ifBlank { null },
            gateCode = selectedAddress?.gateCode?.ifBlank { null },
            uuid = lastOrderUuid,
            firstName = customer?.firstName?.ifBlank { null },
            lastName = customer?.lastName?.ifBlank { null },
            email = customer?.email?.ifBlank { null },
            phone = customer?.phone?.ifBlank { null },
        )
        val response = BitesOrders.checkout(merchantId, request)
        if (BuildConfig.DEV_BUILD) {
            Log.dev("Checkout", "response.order=${response?.order}")
            Log.dev("Checkout", "response.clientSecret=${response?.clientSecret?.take(20)}")
            Log.dev("Checkout", "response.serviceable=${response?.serviceable}")
            response?.order?.let { o ->
                Log.dev("Checkout", "order: foodTotal=${o.foodTotal} taxes=${o.taxes} deliveryFee=${o.deliveryFee} fees=${o.fees} tips=${o.tips} displayTotal=${o.displayTotal}")
            }
        }
        // Reuse the draft order on the next re-price; drop it if this call failed so we
        // don't keep asking the server to update an order it can't find.
        lastOrderUuid = response?.order?.uuid
        if (response == null) {
            error = "Failed to load pricing. Please try again."
        } else if (!response.isServiceable) {
            error = "This address is not serviceable for delivery."
        } else {
            checkoutResponse = response
        }
        fetchingPrices = false
    }

    // The screen showed foodTotal+fees+taxes+deliveryFee+tips, which is the total *before*
    // any reward credit — the reference fetches GET /orders/{uuid}/rewards and subtracts
    // `rewardsAvailable` from that same sum before displaying it, which is why a discounted
    // order read higher on screen than Stripe charged.
    // The reference gates this on context.customer being present (:1255352) — it's a
    // signed-in check, not a feature flag, so mirror it rather than always fetching.
    LaunchedEffect(confirmedOrder?.uuid, customer?.uuid) {
        val orderUuid = confirmedOrder?.uuid?.takeIf { it.isNotBlank() }
        rewards = if (customer == null || orderUuid == null) null
        else BitesOrders.getOrderRewards(orderUuid)
        if (BuildConfig.DEV_BUILD) {
            Log.dev("Checkout", "rewardsAvailable=${rewards?.rewardsAvailable} rate=${rewards?.rewardsRate}")
        }
    }

    // Cross-check against what Stripe will really charge; UI follows the reference formula,
    // but log loudly if the PaymentIntent disagrees so any remaining gap is visible.
    LaunchedEffect(checkoutResponse?.clientSecret, payTotal) {
        val secret = checkoutResponse?.clientSecret?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val amount = withContext(Dispatchers.IO) {
            runCatching {
                Stripe(context, PaymentConfiguration.getInstance(context).publishableKey)
                    .retrievePaymentIntentSynchronous(secret).amount?.toInt()
            }.onFailure { Log.status("Checkout", "PaymentIntent lookup failed", it) }.getOrNull()
        } ?: return@LaunchedEffect
        val shown = payTotal ?: return@LaunchedEffect
        if (kotlin.math.abs(amount / 100.0 - shown) > 0.005 && BuildConfig.DEV_BUILD) {
            Log.status("Checkout", "MISMATCH: stripe=${amount / 100.0} shown=$shown " +
                "componentTotal=${confirmedOrder.componentTotal} rewards=${rewards?.rewardsAvailable}")
        }
    }

    val paymentSheet = rememberPaymentSheet { result ->
        when (result) {
            is PaymentSheetResult.Completed -> {
                orderSuccess = true
                OrderTrackingService.start(context, checkoutResponse?.order?.id)
                // Clear the cart now (a paid cart must not be payable again from
                // the back stack), but do not navigate yet: leaving here is what
                // the confirmation's button does, via onOrderPlaced.
                onOrderCompleted()
            }
            is PaymentSheetResult.Canceled -> {
                paying = false
            }
            is PaymentSheetResult.Failed -> {
                error = result.error.localizedMessage ?: "Payment failed"
                paying = false
            }
        }
    }

    if (orderSuccess) {
        CheckoutSuccess(onContinue = onOrderPlaced)
        return
    }

    AppScaffold(
        title = stringResource(R.string.checkout),
        onNavigateBack = onBack,
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                item { CheckoutOrderSummary(items) }
                item { CheckoutOrderTypePicker(isPickup) { isPickup = it } }

                if (!isPickup) {
                    item {
                        CheckoutAddressPicker(
                            addresses = addresses,
                            addressesLoaded = addressesLoaded,
                            selectedAddress = selectedAddress,
                            onSelect = {
                                selectedAddress = it
                                if (it.deliveryInstructions.isNotEmpty()) {
                                    deliveryInstructions = it.deliveryInstructions
                                }
                            },
                            deliveryInstructions = deliveryInstructions,
                            onInstructionsChange = { deliveryInstructions = it },
                        )
                    }
                }

                item { CheckoutTipPicker(tipCents) { tipCents = it } }

                item {
                    CheckoutDealsPicker(
                        deals = deals,
                        selectedDealId = selectedDealId,
                        onSelect = { selectedDealId = it },
                        promoCode = promoCode,
                        onPromoChange = { promoCode = it },
                    )
                }

                item {
                    CheckoutTotals(
                        subtotal = subtotal,
                        fetchingPrices = fetchingPrices,
                        taxesDollars = confirmedOrder?.taxesDollars,
                        deliveryFeeDollars = confirmedOrder?.deliveryFeeDollars,
                        feesDollars = confirmedOrder?.fees?.div(100.0),
                        tipsDollars = confirmedOrder?.tipsDollars,
                        rewardsApplied = rewardsApplied,
                        payTotal = payTotal,
                        displayTotal = confirmedOrder?.displayTotal,
                    )
                }

                if (error != null) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        Text(error!!, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Card(Modifier.fillMaxWidth().padding(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Button(
                        onClick = {
                            paying = true
                            paymentSheet.presentWithPaymentIntent(
                                checkoutResponse!!.clientSecret,
                                PaymentSheet.Configuration(
                                    merchantDisplayName = items.firstOrNull()?.merchantName ?: "Food Delivery",
                                )
                            )
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
    }
}
