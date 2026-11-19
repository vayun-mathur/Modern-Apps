package com.vayunmathur.travel.ui
import com.vayunmathur.travel.R

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.domain.assignSeat
import com.vayunmathur.travel.domain.seatHolder
import com.vayunmathur.travel.domain.seatKey
import com.vayunmathur.travel.domain.seatedPassengers
import com.vayunmathur.travel.network.SeatCabinDto
import com.vayunmathur.travel.network.SeatElementDto
import com.vayunmathur.travel.network.SeatRowDto
import com.vayunmathur.travel.platform.SeatMapActions
import com.vayunmathur.travel.platform.SeatMapState
import com.vayunmathur.travel.platform.TravelViewModel
import com.vayunmathur.travel.platform.loadSeatMaps
import com.vayunmathur.travel.platform.toggleSeat
import androidx.compose.ui.res.stringResource






/** Binds [TravelViewModel] and the back stack to the stateless [SeatMapScreen]. */
@Composable
fun SeatMapPage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
    route: Route.SeatMap,
) {
    val state by viewModel.seatMap.collectAsStateWithLifecycle()
    val selectedSeats by viewModel.selectedSeats.collectAsStateWithLifecycle()
    val review by viewModel.review.collectAsStateWithLifecycle()
    val inputs by viewModel.passengers.collectAsStateWithLifecycle()
    val passengers = seatedPassengers(review.offer?.passengers.orEmpty()).mapIndexed { i, p ->
        val input = inputs.firstOrNull { it.id == p.id }
        val name = listOfNotNull(input?.givenName?.ifBlank { null }, input?.familyName?.ifBlank { null })
            .joinToString(" ")
        SeatPassenger(p.id, name.ifBlank { "Passenger ${i + 1}" })
    }

    // Seat maps ride on the trip ancillaries (loaded before this screen).
    LaunchedEffect(route.offerId) { viewModel.loadSeatMaps() }

    val actions = remember(viewModel, backStack) {
        object : SeatMapActions {
            override fun toggleSeat(segmentId: String, passengerId: String, seat: SeatElementDto) =
                viewModel.toggleSeat(segmentId, passengerId, seat)

            override fun done() = backStack.pop()
            override fun back() = backStack.pop()
        }
    }

    SeatMapScreen(
        segmentId = route.segmentId,
        state = state,
        selectedSeats = selectedSeats,
        actions = actions,
        passengers = passengers,
    )
}
