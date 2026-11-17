package com.vayunmathur.travel.ui
import com.vayunmathur.travel.R

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.AssistChip
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.domain.groupInboundSlices
import com.vayunmathur.travel.domain.groupOutboundSlices
import com.vayunmathur.travel.domain.sliceSignature
import com.vayunmathur.travel.network.OfferDto
import com.vayunmathur.travel.platform.FlightQuery
import com.vayunmathur.travel.platform.PartialFlowState
import com.vayunmathur.travel.platform.TravelViewModel
import com.vayunmathur.travel.platform.loadPartialFares
import com.vayunmathur.travel.platform.selectOffer
import com.vayunmathur.travel.platform.startPartialSearch
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FareSelectPage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
    route: Route.FareSelect,
) {
    LaunchedEffect(route.outboundSignature, route.inboundSignature) {
        viewModel.loadPartialFares(route.outboundSignature, route.inboundSignature)
    }
    val state by viewModel.partialFlow.collectAsStateWithLifecycle()
    PartialScaffold(
        title = stringResource(R.string.choose_fare),
        subtitle = stringResource(R.string.step_2_of_2),
        backStack = backStack,
        state = state,
        isEmpty = state.offers.isEmpty(),
    ) {
        items(state.offers) { offer ->
            FareCard(offer) {
                viewModel.selectOffer(offer)
                backStack.add(Route.OfferReview(offer.offerId))
            }
        }
    }
}
