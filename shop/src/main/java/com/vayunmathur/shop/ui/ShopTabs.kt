package com.vayunmathur.shop.ui

import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.IconPerson
import com.vayunmathur.library.ui.IconReceipt
import com.vayunmathur.library.ui.IconSearch
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
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 4 })
    // A scoped search opened from a merchant link always lands on the Search tab.
    LaunchedEffect(viewModel.focusSearchRequest) {
        if (viewModel.focusSearchRequest > 0) pagerState.animateScrollToPage(0)
    }
    val tabs = listOf(
        PagerTab(stringResource(R.string.search), { IconSearch() }) {
            SearchScreen(viewModel, onProductClick)
        },
        PagerTab(stringResource(R.string.cart), { IconShoppingCart() }) {
            CartScreen(viewModel, onCheckout = onCheckout)
        },
        PagerTab(stringResource(R.string.orders), { IconReceipt() }) {
            OrdersScreen(viewModel)
        },
        PagerTab("Profile", { IconPerson() }) {
            ProfileScreen(viewModel, required = false)
        },
    )
    TabbedPagerScaffold(tabs = tabs, pagerState = pagerState, tabStyle = TabStyle.BottomNav)
}
