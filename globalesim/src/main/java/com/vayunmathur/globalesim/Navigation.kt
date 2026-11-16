package com.vayunmathur.globalesim

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.vayunmathur.globalesim.platform.EsimViewModel
import com.vayunmathur.globalesim.ui.CardPaymentLaunchers
import com.vayunmathur.globalesim.ui.HomeScreen
import com.vayunmathur.globalesim.ui.PricingScreen
import com.vayunmathur.library.ui.IconGlobe
import com.vayunmathur.library.ui.IconSim
import com.vayunmathur.library.ui.RouteTab
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.SiblingPage
import com.vayunmathur.library.util.rememberNavBackStack

/**
 * The two bottom-nav tabs as sibling routes: Purchase (your eSIM + top-ups)
 * and Pricing (searchable per-country rates), switched by resetting the
 * back stack. ViewModel messages are shown here at the host, once, rather
 * than by each tab screen.
 */
@Composable
fun Navigation(viewModel: EsimViewModel, launchers: CardPaymentLaunchers? = null) {
    val backStack = rememberNavBackStack<Route>(Route.Purchase)
    val context = LocalContext.current
    val messenger = rememberMessenger()
    val message = viewModel.state.message
    LaunchedEffect(message) {
        if (message != null) {
            messenger.show(message)
            viewModel.dismissMessage()
        }
    }
    TabbedPagerScaffold(
        backStack = backStack,
        tabs = listOf<RouteTab<Route>>(
            RouteTab(Route.Purchase, "Purchase") { IconSim() },
            RouteTab(Route.Pricing, "Pricing") { IconGlobe() },
        ),
    ) {
        MainNavigation(backStack) {
            entry<Route.Purchase>(metadata = SiblingPage()) {
                HomeScreen(
                    state = viewModel.state,
                    launchers = launchers,
                    onStartCardPurchase = viewModel::startCardPurchase,
                    onStartCryptoPurchase = { cents ->
                        viewModel.startCryptoPurchase(cents) { url ->
                            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                        }
                    },
                    onClearCryptoInvoice = viewModel::clearPendingInvoice,
                    onClaim = viewModel::claim,
                    onRefresh = viewModel::refresh,
                )
            }
            entry<Route.Pricing>(metadata = SiblingPage()) {
                PricingScreen(state = viewModel.state, onRefresh = viewModel::refreshRates)
            }
        }
    }
}
