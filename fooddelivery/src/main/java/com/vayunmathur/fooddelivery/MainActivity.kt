package com.vayunmathur.fooddelivery

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.stripe.android.PaymentConfiguration
import com.vayunmathur.fooddelivery.platform.AppInit
import com.vayunmathur.fooddelivery.platform.emailVerificationOrNull
import com.vayunmathur.fooddelivery.platform.isCheckoutDeepLink
import com.vayunmathur.fooddelivery.platform.restaurantIdOrNull
import com.vayunmathur.fooddelivery.platform.trackOrderIdOrNull
import com.vayunmathur.fooddelivery.ui.FoodDeliveryApp
import com.vayunmathur.library.ui.MaActivity

/** Entry activity that initialises Stripe and app state and routes notification and deep-link intents into the UI. */
class MainActivity : MaActivity() {
    // The order to open on the tracking screen, set from a notification tap. Held as
    // Compose state so onNewIntent can push a new deep link into the running UI.
    private val trackOrderId = mutableStateOf<Int?>(null)

    // A restaurant to open, set from a fooddelivery://restaurant/<id> deep link
    // (e.g. the maps place-sheet "Order" button). Same Compose-state pattern so
    // onNewIntent can re-deep-link into the running UI.
    private val openRestaurantId = mutableStateOf<Int?>(null)

    // fooddelivery://checkout — the assistant handoff: the cart was prepared
    // provider-side, and the user places/pays for the order here in-app.
    private val openCheckout = mutableStateOf(false)

    private val verifyEmail = mutableStateOf<Route.EmailVerification?>(null)

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

        /** Holds the Stripe publishable key used to initialise payments. */
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
        openCheckout.value = intent.isCheckoutDeepLink()
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
        FoodDeliveryApp(trackOrderId, openRestaurantId, openCheckout, verifyEmail)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.trackOrderIdOrNull()?.let { trackOrderId.value = it }
        intent.restaurantIdOrNull()?.let { openRestaurantId.value = it }
        if (intent.isCheckoutDeepLink()) openCheckout.value = true
        intent.emailVerificationOrNull()?.let { verifyEmail.value = it }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
