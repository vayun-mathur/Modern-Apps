package com.vayunmathur.globalesim

import android.content.Intent
import androidx.compose.foundation.pager.rememberPagerState
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
import com.vayunmathur.library.ui.PagerTab
import com.vayunmathur.library.ui.TabStyle
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack

@Composable
fun Navigation(viewModel: EsimViewModel, launchers: CardPaymentLaunchers? = null) {
    val backStack = rememberNavBackStack<Route>(Route.Tabs)

    MainNavigation(backStack) {
        entry<Route.Tabs> {
            EsimTabs(viewModel = viewModel, launchers = launchers)
        }
    }
}

/**
 * The two bottom-nav tabs: Purchase (your eSIM + top-ups) and Pricing
 * (searchable per-country rates). ViewModel messages are shown here, once,
 * rather than by each tab (both tabs stay composed in the pager).
 */
@Composable
private fun EsimTabs(viewModel: EsimViewModel, launchers: CardPaymentLaunchers?) {
    val context = LocalContext.current
    val messenger = rememberMessenger()
    val message = viewModel.state.message
    LaunchedEffect(message) {
        if (message != null) {
            messenger.show(message)
            viewModel.dismissMessage()
        }
    }
    val pagerState = rememberPagerState(pageCount = { 2 })
    val tabs = listOf(
        PagerTab("Purchase", { IconSim() }) {
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
        },
        PagerTab("Pricing", { IconGlobe() }) {
            PricingScreen(state = viewModel.state, onRefresh = viewModel::refreshRates)
        },
    )
    TabbedPagerScaffold(tabs = tabs, pagerState = pagerState, tabStyle = TabStyle.BottomNav)
}
