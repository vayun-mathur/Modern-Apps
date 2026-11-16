package com.vayunmathur.shop

import android.content.Intent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.shop.platform.AppInit
import com.vayunmathur.shop.platform.ShopViewModel

/** Main Activity: a ma activity implementation for main activity. */
class MainActivity : MaActivity() {
    private val viewModel: ShopViewModel by viewModels()

    /**
     * Counts Orders deep-links rather than latching a flag: the notification can be
     * tapped again after the user has moved to another tab, and an unchanged flag
     * would not re-navigate. Navigation turns a new count into a direct
     * `backStack.reset(Route.Orders)` (and into the start route on a cold start).
     */
    private var ordersRequest by mutableIntStateOf(0)

    /** Counts `shop://cart` handoffs, sequenced like [ordersRequest]. */
    private var cartRequest by mutableIntStateOf(0)

    /** Latest merchant link to open, sequenced so re-delivering the same URL re-navigates. */
    private var linkRequest by mutableStateOf<Pair<Int, String>?>(null)
    private var linkSequence = 0

    override fun onCreateBeforeContent() {
        // Trust-bundle warm-up off the main thread; screens await AppInit.awaitReady().
        AppInit.start(this)
        // The ViewModel outlives configuration changes, which re-deliver the launch intent.
        if (!viewModel.launchLinkHandled) {
            viewModel.launchLinkHandled = true
            handleLink(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLink(intent)
    }

    private fun handleLink(intent: Intent?) {
        if (intent?.action == ACTION_SHOW_ORDERS) {
            ordersRequest++
            return
        }
        // Assistant checkout handoff (shop://cart): open the cart only;
        // preparing a cart is not a checkout and never a paid order.
        val data = intent?.data
        if (intent?.action == Intent.ACTION_VIEW && data?.scheme == "shop" && data.host == "cart") {
            cartRequest++
            return
        }
        if (intent?.action != Intent.ACTION_VIEW) return
        intent.dataString?.let { linkRequest = ++linkSequence to it }
    }

    /** Shared constants and helpers for MainActivity. */
    companion object {
        const val ACTION_SHOW_ORDERS = "com.vayunmathur.shop.action.SHOW_ORDERS"
    }

    @Composable
    override fun Content() {
        Navigation(
            viewModel,
            ordersRequest = ordersRequest,
            cartRequest = cartRequest,
            linkRequest = linkRequest,
        )
    }
}
