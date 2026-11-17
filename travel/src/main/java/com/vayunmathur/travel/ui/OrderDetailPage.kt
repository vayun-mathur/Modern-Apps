package com.vayunmathur.travel.ui
import com.vayunmathur.travel.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.rememberIs24Hour
import com.vayunmathur.library.ui.DetailScaffold
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.domain.isCheckoutUnexpired
import com.vayunmathur.travel.network.OrderDetailDto
import com.vayunmathur.travel.network.SliceDto
import com.vayunmathur.travel.platform.CheckoutState
import com.vayunmathur.travel.platform.OrderDetailActions
import com.vayunmathur.travel.platform.OrderDetailUiState
import com.vayunmathur.travel.platform.TravelViewModel
import com.vayunmathur.travel.platform.acceptNewPrice
import com.vayunmathur.travel.platform.loadBookingDetail
import com.vayunmathur.travel.platform.refreshBookingDetail
import androidx.compose.ui.res.stringResource

/** Binds [TravelViewModel] and the back stack to the stateless [OrderDetailScreen]. */
@Composable
fun OrderDetailPage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
    route: Route.OrderDetail,
) {
    val state by viewModel.orderDetail.collectAsStateWithLifecycle()
    val trips by viewModel.bookedTrips.collectAsStateWithLifecycle()
    val checkout by viewModel.checkout.collectAsStateWithLifecycle()
    val stored = trips.find { it.orderId == route.orderId }

    LaunchedEffect(route.orderId) {
        viewModel.loadBookingDetail(route.orderId)
    }

    val actions = remember(viewModel, backStack, route) {
        object : OrderDetailActions {
            override fun refresh() = viewModel.refreshBookingDetail(route.orderId)
            // The confirmation screen owns the in-app payment sheet and polling.
            override fun completePayment() {
                backStack.add(Route.Confirmation(route.orderId))
            }
            override fun acceptNewPrice() = viewModel.acceptNewPrice()
            override fun openChange(orderId: String) = backStack.add(Route.Change(orderId))
            override fun openCancel(orderId: String) = backStack.add(Route.Cancel(orderId))
            override fun back() = backStack.pop()
        }
    }

    OrderDetailScreen(
        state = OrderDetailUiState(
            loading = state.loading,
            error = state.error,
            order = state.order,
            refreshing = state.loading,
            checkout = checkout,
        ),
        pendingCheckout = stored?.status == "pending_payment" &&
            isCheckoutUnexpired(stored.checkoutExpiresAt),
        actions = actions,
    )
}
