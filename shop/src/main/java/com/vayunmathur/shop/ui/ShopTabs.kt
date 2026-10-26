package com.vayunmathur.shop.ui

import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.IconReceipt
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconStore
import com.vayunmathur.library.ui.IconShoppingCart
import com.vayunmathur.library.ui.PagerTab
import com.vayunmathur.library.ui.TabStyle
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.shop.R
import com.vayunmathur.shop.platform.ShopViewModel

@Composable
fun ShopTabs(viewModel: ShopViewModel, onProductClick: (String, String, String) -> Unit, onCheckout: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.loadCart(context)
        viewModel.loadOrders(context)
    }
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 4 })
    val tabs = listOf(
        PagerTab(stringResource(R.string.search), { IconSearch() }) {
            SearchScreen(viewModel, onProductClick)
        },
        PagerTab("Merchants", { IconStore() }) {
            MerchantDirectoryScreen { domain ->
                viewModel.updateShopDomain(domain)
                scope.launch { pagerState.animateScrollToPage(0) }
            }
        },
        PagerTab(stringResource(R.string.cart), { IconShoppingCart() }) {
            CartScreen(viewModel, onBack = null, onCheckout = onCheckout)
        },
        PagerTab(stringResource(R.string.orders), { IconReceipt() }) {
            OrdersScreen(viewModel)
        },
    )
    TabbedPagerScaffold(tabs = tabs, pagerState = pagerState, tabStyle = TabStyle.BottomNav)
}
