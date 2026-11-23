package com.vayunmathur.fooddelivery.ui

import com.vayunmathur.library.log.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.stripe.android.PaymentConfiguration
import com.stripe.android.Stripe
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PaymentSheetResult
import com.stripe.android.paymentsheet.rememberPaymentSheet
import com.vayunmathur.fooddelivery.BuildConfig
import com.vayunmathur.fooddelivery.R
import com.vayunmathur.fooddelivery.network.BitesCustomers
import com.vayunmathur.fooddelivery.network.BitesDeals
import com.vayunmathur.fooddelivery.network.BitesOrders
import com.vayunmathur.fooddelivery.data.AddressStore
import com.vayunmathur.fooddelivery.data.CartItem
import com.vayunmathur.fooddelivery.data.CheckoutDraft
import com.vayunmathur.fooddelivery.data.CheckoutDraftAction
import com.vayunmathur.fooddelivery.data.CheckoutDraftStore
import com.vayunmathur.fooddelivery.data.checkoutDraftAction
import com.vayunmathur.fooddelivery.data.paymentIntentId
import com.vayunmathur.fooddelivery.data.CheckoutResponse
import com.vayunmathur.fooddelivery.data.Customer
import com.vayunmathur.fooddelivery.data.Deal
import com.vayunmathur.fooddelivery.data.OrderRewards
import com.vayunmathur.fooddelivery.data.SavedAddress
import com.vayunmathur.fooddelivery.notifications.OrderTrackingService
import com.vayunmathur.fooddelivery.platform.AppInit
import com.vayunmathur.library.ui.AppScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    val scope = rememberCoroutineScope()

    var isPickup by remember { mutableStateOf(false) }
    var tipCents by remember { mutableIntStateOf(300) }
    var deliveryInstructions by remember { mutableStateOf("") }
    var paying by remember { mutableStateOf(false) }
    var fetchingPrices by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var orderSuccess by remember { mutableStateOf(false) }
    var checkoutResponse by remember { mutableStateOf<CheckoutResponse?>(null) }
    CheckoutNotificationPermissionEffect(orderSuccess)

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
    /** Address id restored from a persisted checkout draft, applied once addresses load. */
    var restoredAddressId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val all = AddressStore.getAll(context)
        val default = all.firstOrNull { it.id == restoredAddressId } ?: all.firstOrNull { it.isDefault } ?: all.firstOrNull()
        addresses = all
        selectedAddress = default
        // Don't overwrite anything typed while the read was in flight.
        if (deliveryInstructions.isEmpty()) {
            deliveryInstructions = default?.deliveryInstructions ?: ""
        }
        addressesLoaded = true
    }

    // Entry reconciliation for a checkout interrupted by process death. The draft
    // (order uuid + PaymentIntent id) was persisted before PaymentSheet presented,
    // so poll the placed-order list for that uuid before any new checkout is
    // created: found means the payment already placed the order (restore the
    // success/tracking path, never pay again); presented-but-not-found means the
    // payment outcome is unknown (check Orders, don't re-create or re-present);
    // otherwise resume the same draft uuid so re-pricing UPDATEs it in place.
    LaunchedEffect(Unit) {
        val draft = CheckoutDraftStore.get(context) ?: return@LaunchedEffect
        AppInit.awaitReady()
        val existing = draft.orderUuid?.takeIf { it.isNotBlank() }?.let { uuid ->
            BitesOrders.getOrders().firstOrNull { it.uuid == uuid }
        }
        when (checkoutDraftAction(draft, existing != null)) {
            CheckoutDraftAction.RestorePlaced -> {
                CheckoutDraftStore.clear(context)
                if (existing != null) {
                    OrderTrackingService.start(context, existing.id)
                    orderSuccess = true
                    onOrderCompleted()
                }
            }
            CheckoutDraftAction.CheckOrders, CheckoutDraftAction.ResumeDraft -> {
                isPickup = draft.isPickup
                tipCents = draft.tipCents
                promoCode = draft.promoCode
                selectedDealId = draft.dealId
                deliveryInstructions = draft.deliveryInstructions
                restoredAddressId = draft.addressId
                if (addressesLoaded) {
                    selectedAddress = addresses.firstOrNull { it.id == draft.addressId } ?: selectedAddress
                }
                lastOrderUuid = draft.orderUuid
                if (checkoutDraftAction(draft, existing != null) == CheckoutDraftAction.CheckOrders) {
                    error = "Your last payment may have completed. Check Orders before paying again."
                }
            }
            CheckoutDraftAction.None -> Unit
        }
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

    CheckoutPricingEffect(
        items = items,
        merchantId = merchantId,
        canFetch = canFetch,
        orderSuccess = orderSuccess,
        isPickup = isPickup,
        tipCents = tipCents,
        selectedAddress = selectedAddress,
        promoCode = promoCode,
        customer = customer,
        selectedDealId = selectedDealId,
        deliveryInstructions = deliveryInstructions,
        lastOrderUuid = lastOrderUuid,
        onCheckoutResponseChange = { checkoutResponse = it },
        onErrorChange = { error = it },
        onFetchingPricesChange = { fetchingPrices = it },
        onLastOrderUuidChange = { lastOrderUuid = it },
    )

    // Persist the draft as soon as a priced checkout exists, long before payment:
    // process death at any later point resumes this same order uuid / PaymentIntent.
    LaunchedEffect(checkoutResponse?.order?.uuid, checkoutResponse?.clientSecret) {
        val response = checkoutResponse ?: return@LaunchedEffect
        val uuid = response.order?.uuid?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        CheckoutDraftStore.save(
            context,
            CheckoutDraft(
                merchantId = merchantId,
                orderUuid = uuid,
                paymentIntentId = paymentIntentId(response.clientSecret),
                clientSecret = response.clientSecret.takeIf { it.isNotBlank() },
                isPickup = isPickup,
                tipCents = tipCents,
                promoCode = promoCode,
                dealId = selectedDealId,
                addressId = selectedAddress?.id,
                deliveryInstructions = deliveryInstructions,
                paymentPresented = false,
                savedAtMs = System.currentTimeMillis(),
            ),
        )
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
                scope.launch { CheckoutDraftStore.clear(context) }
                orderSuccess = true
                OrderTrackingService.start(context, checkoutResponse?.order?.id)
                // Clear the cart now (a paid cart must not be payable again from
                // the back stack), but do not navigate yet: leaving here is what
                // the confirmation's button does, via onOrderPlaced.
                onOrderCompleted()
            }
            is PaymentSheetResult.Canceled -> {
                // A live cancel is a known outcome: the draft stays resumable and
                // is no longer "presented with unknown outcome".
                scope.launch {
                    CheckoutDraftStore.get(context)?.let {
                        CheckoutDraftStore.save(context, it.copy(paymentPresented = false))
                    }
                }
                paying = false
            }
            is PaymentSheetResult.Failed -> {
                scope.launch {
                    CheckoutDraftStore.get(context)?.let {
                        CheckoutDraftStore.save(context, it.copy(paymentPresented = false))
                    }
                }
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
        isRefreshing = fetchingPrices,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            CheckoutOrderContent(
                items = items,
                isPickup = isPickup,
                onPickupChange = { isPickup = it },
                addresses = addresses,
                addressesLoaded = addressesLoaded,
                selectedAddress = selectedAddress,
                onAddressSelect = {
                    selectedAddress = it
                    if (it.deliveryInstructions.isNotEmpty()) {
                        deliveryInstructions = it.deliveryInstructions
                    }
                },
                deliveryInstructions = deliveryInstructions,
                onInstructionsChange = { deliveryInstructions = it },
                tipCents = tipCents,
                onTipChange = { tipCents = it },
                deals = deals,
                selectedDealId = selectedDealId,
                onDealSelect = { selectedDealId = it },
                promoCode = promoCode,
                onPromoChange = { promoCode = it },
                subtotal = subtotal,
                fetchingPrices = fetchingPrices,
                taxesDollars = confirmedOrder?.taxesDollars,
                deliveryFeeDollars = confirmedOrder?.deliveryFeeDollars,
                feesDollars = confirmedOrder?.fees?.div(100.0),
                tipsDollars = confirmedOrder?.tipsDollars,
                rewardsApplied = rewardsApplied,
                payTotal = payTotal,
                displayTotal = confirmedOrder?.displayTotal,
                error = error,
                modifier = Modifier.weight(1f),
            )
            CheckoutPlaceOrderCard(
                checkoutResponse = checkoutResponse,
                merchantId = merchantId,
                isPickup = isPickup,
                tipCents = tipCents,
                promoCode = promoCode,
                selectedDealId = selectedDealId,
                selectedAddress = selectedAddress,
                deliveryInstructions = deliveryInstructions,
                merchantName = items.firstOrNull()?.merchantName,
                paying = paying,
                fetchingPrices = fetchingPrices,
                payTotal = payTotal,
                onPayingChange = { paying = it },
                paymentSheet = paymentSheet,
            )
        }
    }
}
