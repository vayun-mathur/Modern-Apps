package com.vayunmathur.shop.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.vayunmathur.library.ui.IconPerson
import com.vayunmathur.library.ui.IconReceipt
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconShoppingCart
import com.vayunmathur.library.ui.PagerTab
import com.vayunmathur.library.ui.TabStyle
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.shop.R
import com.vayunmathur.shop.platform.ShopViewModel

private const val ORDERS_PAGE = 2

@Composable
fun ShopTabs(viewModel: ShopViewModel, onProductClick: (String, String, String) -> Unit, onPayInApp: () -> Unit) {
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
    LaunchedEffect(viewModel.focusOrdersRequest) {
        if (viewModel.focusOrdersRequest > 0) pagerState.scrollToPage(ORDERS_PAGE)
    }
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage == ORDERS_PAGE) viewModel.refreshTracking(context)
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(viewModel.notificationPermissionRequest) {
        if (viewModel.notificationPermissionRequest > 0 &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val tabs = listOf(
        PagerTab(stringResource(R.string.search), { IconSearch() }) {
            SearchScreen(viewModel, onProductClick)
        },
        PagerTab(stringResource(R.string.cart), { IconShoppingCart() }) {
            CartScreen(viewModel, onPayInApp = onPayInApp)
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
