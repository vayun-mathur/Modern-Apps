package com.vayunmathur.fooddelivery.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.fooddelivery.Navigation
import com.vayunmathur.fooddelivery.Route
import com.vayunmathur.fooddelivery.data.CartItem
import com.vayunmathur.fooddelivery.data.CartStore
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.util.rememberNavBackStack

@Composable
internal fun FoodDeliveryApp(
    trackOrderId: MutableState<Int?>,
    openRestaurantId: MutableState<Int?>,
    openCheckout: MutableState<Boolean>,
    verifyEmail: MutableState<Route.EmailVerification?>,
) {
    val context = LocalContext.current
    val backStack = rememberNavBackStack<Route>(Route.Home)

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

    // A fooddelivery://checkout deep link opens checkout for the current cart.
    LaunchedEffect(openCheckout.value) {
        if (!openCheckout.value) return@LaunchedEffect
        backStack.add(Route.Checkout)
        openCheckout.value = false
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
        PendingCartDialog(
            cart = cart,
            pending = pending,
            onDismiss = { pendingCartItem = null },
            onConfirm = {
                cart.clear()
                cart.add(pending)
                CartStore.save(context, cart)
                pendingCartItem = null
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

    Navigation(
        backStack = backStack,
        cart = cart,
        onPendingCartItem = { pendingCartItem = it },
    )

}


@Composable
private fun PendingCartDialog(
    cart: androidx.compose.runtime.snapshots.SnapshotStateList<CartItem>,
    pending: CartItem,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val currentName = cart.firstOrNull()?.merchantName?.ifBlank { "another restaurant" }
        ?: "another restaurant"
    val newName = pending.merchantName.ifBlank { "a different restaurant" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start a new cart?") },
        text = {
            Text(
                "Your cart has items from $currentName. Ordering is from one " +
                    "restaurant at a time, so adding from $newName will clear it."
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text("Start new cart") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Keep my cart") }
        },
    )
}
