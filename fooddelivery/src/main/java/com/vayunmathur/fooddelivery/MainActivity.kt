package com.vayunmathur.fooddelivery

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.stripe.android.PaymentConfiguration
import com.vayunmathur.fooddelivery.platform.AppInit
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.ui.IconHome
import com.vayunmathur.library.ui.IconLocalOffer
import com.vayunmathur.library.ui.IconPackage
import com.vayunmathur.library.ui.IconPerson
import com.vayunmathur.library.ui.IconShoppingCart
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.PagerTab
import com.vayunmathur.library.ui.TabStyle
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.MorphPage
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.fooddelivery.data.CartItem
import com.vayunmathur.fooddelivery.data.CartStore
import com.vayunmathur.fooddelivery.ipc.OrderLookupContract
import com.vayunmathur.fooddelivery.notifications.OrderLiveUpdate
import com.vayunmathur.fooddelivery.ui.AccountScreen
import com.vayunmathur.fooddelivery.ui.CartScreen
import com.vayunmathur.fooddelivery.ui.CheckoutScreen
import com.vayunmathur.fooddelivery.ui.DealsScreen
import com.vayunmathur.fooddelivery.ui.EmailVerificationScreen
import com.vayunmathur.fooddelivery.ui.HomeScreen
import com.vayunmathur.fooddelivery.ui.OrderTrackingScreen
import com.vayunmathur.fooddelivery.ui.OrdersScreen
import com.vayunmathur.fooddelivery.ui.RestaurantScreen
import kotlinx.serialization.Serializable

/**
 * Re-tapping into the same destination class replaces the detail instead of stacking it.
 *
 * On a two-pane layout the list stays visible under its detail, so tapping a second
 * restaurant (or order) would otherwise pile Restaurant on Restaurant; back would then
 * step through stale details instead of leaving.
 */
internal fun NavBackStack<Route>.pushDetail(route: Route) {
    if (backStack.isNotEmpty() && last()::class == route::class) setLast(route) else add(route)
}

/**
 * Checkout posts every cart line to a single merchant's endpoint
 * (`/merchants/{id}/checkout`, keyed off the first line's merchant), so a cart may
 * only ever hold one merchant's items. A line with no merchant (0) carries no
 * evidence against the cart's merchant and is allowed through.
 */
internal fun List<CartItem>.canAddFromSameMerchant(item: CartItem): Boolean {
    val cartMerchant = firstOrNull()?.merchantId ?: return true
    return cartMerchant == 0 || item.merchantId == 0 || cartMerchant == item.merchantId
}

sealed interface Route : NavKey {
    @Serializable data class Main(val initialTab: Int = 0) : Route
    @Serializable data class Restaurant(val id: Int) : Route
    @Serializable data object Checkout : Route
    @Serializable data class OrderTracking(val orderId: Int) : Route
    @Serializable data class EmailVerification(val customerId: String, val token: String) : Route
}

class MainActivity : MaActivity() {
    // The order to open on the tracking screen, set from a notification tap. Held as
    // Compose state so onNewIntent can push a new deep link into the running UI.
    private val trackOrderId = mutableStateOf<Int?>(null)

    // A restaurant to open, set from a fooddelivery://restaurant/<id> deep link
    // (e.g. the maps place-sheet "Order" button). Same Compose-state pattern so
    // onNewIntent can re-deep-link into the running UI.
    private val openRestaurantId = mutableStateOf<Int?>(null)

    private val verifyEmail = mutableStateOf<Route.EmailVerification?>(null)

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    companion object {
        private const val STRIPE_PUBLISHABLE_KEY =
            "pk_live_51NQy7lFJFBMK4hv9KubgZcyH2Wy0MsXn9BtrtM7moEi762WE7pcmZ1JL9BrCKPRKw6ZJdGo9YJSA1pidb0KUthlJ00Wr4bcpVD"
    }

    override fun onCreateBeforeContent() {
        // Process-wide warm-up (trust bundle + saved auth token) used to live in
        // FoodDeliveryApplication; the cold-process intent path warms itself via AppInit.
        AppInit.start(this)
        trackOrderId.value = intent.trackOrderIdOrNull()
        openRestaurantId.value = intent.restaurantIdOrNull()
        verifyEmail.value = intent.emailVerificationOrNull()
        requestNotificationPermissionIfNeeded()
        // The TLS trust bundle and the saved auth token are warmed up off the main thread by
        // AppInit, so nothing here touches the network or disk.

        PaymentConfiguration.init(
            applicationContext,
            STRIPE_PUBLISHABLE_KEY,
        )
    }

    @Composable
    override fun Content() {
        FoodDeliveryApp(trackOrderId, openRestaurantId, verifyEmail)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.trackOrderIdOrNull()?.let { trackOrderId.value = it }
        intent.restaurantIdOrNull()?.let { openRestaurantId.value = it }
        intent.emailVerificationOrNull()?.let { verifyEmail.value = it }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private fun Intent.trackOrderIdOrNull(): Int? =
    getIntExtra(OrderLiveUpdate.EXTRA_TRACK_ORDER_ID, -1).takeIf { it > 0 }

// fooddelivery://restaurant/<id> — the cross-app "open this restaurant's order
// page" deep link (see OrderLookupContract). Returns null for anything else.
private fun Intent.restaurantIdOrNull(): Int? {
    val uri = data ?: return null
    if (uri.scheme != OrderLookupContract.DEEP_LINK_SCHEME) return null
    if (uri.host != OrderLookupContract.DEEP_LINK_HOST) return null
    return uri.lastPathSegment?.toIntOrNull()?.takeIf { it > 0 }
}

private const val EMAIL_VERIFICATION = "email-verification"

// Both segments end up in an API path, so only plain id characters are let through.
private val LINK_SEGMENT = Regex("[A-Za-z0-9_-]{1,256}")

/**
 * com.deliverycollective.storefront://email-verification/<customerId>/<token> or
 * https://withbites.com/email-verification/<customerId>/<token> — the same two forms the
 * official Bites app handles. Returns null for anything else.
 */
private fun Intent.emailVerificationOrNull(): Route.EmailVerification? {
    val uri = data ?: return null
    val segments = when {
        uri.scheme == "com.deliverycollective.storefront" && uri.host == EMAIL_VERIFICATION ->
            uri.pathSegments
        uri.scheme == "https" && uri.pathSegments.firstOrNull() == EMAIL_VERIFICATION ->
            uri.pathSegments.drop(1)
        else -> return null
    }
    if (segments.size != 2 || !segments.all { LINK_SEGMENT.matches(it) }) return null
    return Route.EmailVerification(customerId = segments[0], token = segments[1])
}

@Composable
private fun FoodDeliveryApp(
    trackOrderId: MutableState<Int?>,
    openRestaurantId: MutableState<Int?>,
    verifyEmail: MutableState<Route.EmailVerification?>,
) {
    val context = LocalContext.current
    val backStack = rememberNavBackStack<Route>(Route.Main())

    // A notification tap deep-links to that order's tracking screen.
    LaunchedEffect(trackOrderId.value) {
        val id = trackOrderId.value ?: return@LaunchedEffect
        backStack.add(Route.OrderTracking(id))
        trackOrderId.value = null
    }

    // A fooddelivery://restaurant/<id> deep link opens that restaurant's page.
    LaunchedEffect(openRestaurantId.value) {
        val id = openRestaurantId.value ?: return@LaunchedEffect
        backStack.add(Route.Restaurant(id))
        openRestaurantId.value = null
    }

    LaunchedEffect(verifyEmail.value) {
        val route = verifyEmail.value ?: return@LaunchedEffect
        backStack.add(route)
        verifyEmail.value = null
    }
    // Restoring the cart is a prefs read plus a JSON decode, so it happens off the main
    // thread rather than in the remember initialiser. Anything added while the read was in
    // flight stays in the cart, and is re-persisted alongside the restored lines.
    val cart = remember { mutableStateListOf<CartItem>() }
    // An item from a second restaurant, held until the user decides whether it
    // replaces the cart they already have (checkout can only order from one).
    var pendingCartItem by remember { mutableStateOf<CartItem?>(null) }
    pendingCartItem?.let { pending ->
        val currentName = cart.firstOrNull()?.merchantName?.ifBlank { "another restaurant" }
            ?: "another restaurant"
        val newName = pending.merchantName.ifBlank { "a different restaurant" }
        AlertDialog(
            onDismissRequest = { pendingCartItem = null },
            title = { Text("Start a new cart?") },
            text = {
                Text(
                    "Your cart has items from $currentName. Ordering is from one " +
                        "restaurant at a time, so adding from $newName will clear it."
                )
            },
            confirmButton = {
                Button(onClick = {
                    cart.clear()
                    cart.add(pending)
                    CartStore.save(context, cart)
                    pendingCartItem = null
                }) { Text("Start new cart") }
            },
            dismissButton = {
                TextButton(onClick = { pendingCartItem = null }) { Text("Keep my cart") }
            },
        )
    }
    LaunchedEffect(Unit) {
        val saved = CartStore.getAll(context)
        if (saved.isEmpty()) return@LaunchedEffect
        val addedMeanwhile = cart.isNotEmpty()
        cart.addAll(0, saved)
        if (addedMeanwhile) CartStore.save(context, cart)
    }

    MainNavigation(backStack) {
        entry<Route.Main>(metadata = ListPage()) { route ->
            FoodDeliveryTabs(
                initialTab = route.initialTab,
                backStack = backStack,
                cart = cart,
            )
        }
        entry<Route.Restaurant>(metadata = ListDetailPage() + MorphPage()) { route ->
            RestaurantScreen(
                merchantId = route.id,
                onBack = { backStack.pop() },
                onAddToCart = { item ->
                    if (cart.canAddFromSameMerchant(item)) {
                        cart.add(item)
                        CartStore.save(context, cart)
                    } else {
                        pendingCartItem = item
                    }
                }
            )
        }
        // Checkout is a flow step, not a detail of the tab list.
        entry<Route.Checkout>(metadata = ListDetailPage()) {
            CheckoutScreen(
                items = cart,
                onBack = { backStack.pop() },
                onOrderPlaced = {
                    cart.clear()
                    CartStore.clear(context)
                    backStack.reset(Route.Main(initialTab = 3))
                },
            )
        }
        // The brief keeps order-tracking full-screen custom; metadata still marks it a detail
        // so medium widths show it two-pane instead of covering the list.
        entry<Route.OrderTracking>(metadata = ListDetailPage() + MorphPage()) { route ->
            OrderTrackingScreen(orderId = route.orderId, onBack = { backStack.pop() })
        }
        entry<Route.EmailVerification>(metadata = ListDetailPage()) { route ->
            EmailVerificationScreen(
                customerId = route.customerId,
                token = route.token,
                onDone = { backStack.pop() },
            )
        }
    }
}

/**
 * The five bottom-nav tabs, hosted in a swipeable pager (see [TabbedPagerScaffold]).
 * Restaurant, Checkout and OrderTracking are pushed on top of this host as ordinary routes.
 */
@Composable
private fun FoodDeliveryTabs(
    initialTab: Int,
    backStack: NavBackStack<Route>,
    cart: androidx.compose.runtime.snapshots.SnapshotStateList<CartItem>,
) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(initialPage = initialTab.coerceIn(0, 4), pageCount = { 5 })
    val tabs = remember(backStack, cart, context) {
        listOf(
            PagerTab("Home", { IconHome() }) {
                HomeScreen(onMerchantClick = { id -> backStack.pushDetail(Route.Restaurant(id)) })
            },
            PagerTab("Cart", { IconShoppingCart() }) {
                CartScreen(
                    items = cart,
                    onRemoveItem = {
                        cart.removeAt(it)
                        CartStore.save(context, cart)
                    },
                    onCheckout = { backStack.add(Route.Checkout) },
                    onEditModifiers = { index, modifiers ->
                        cart[index] = cart[index].copy(selectedModifiers = modifiers)
                        CartStore.save(context, cart)
                    },
                )
            },
            PagerTab("Deals", { IconLocalOffer() }) {
                DealsScreen(onMerchantClick = { id -> backStack.pushDetail(Route.Restaurant(id)) })
            },
            PagerTab("Orders", { IconPackage() }) {
                OrdersScreen(onTrackOrder = { id -> backStack.pushDetail(Route.OrderTracking(id)) })
            },
            PagerTab("Account", { IconPerson() }) {
                AccountScreen()
            },
        )
    }
    TabbedPagerScaffold(tabs = tabs, pagerState = pagerState, tabStyle = TabStyle.BottomNav)
}
