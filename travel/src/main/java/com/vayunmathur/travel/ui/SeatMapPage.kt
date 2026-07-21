package com.vayunmathur.travel.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.network.SeatCabinDto
import com.vayunmathur.travel.network.SeatDto
import com.vayunmathur.travel.util.TravelViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeatMapPage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
    route: Route.SeatMap,
) {
    val state by viewModel.seatMap.collectAsStateWithLifecycle()
    val selectedSeats by viewModel.selectedSeats.collectAsStateWithLifecycle()

    LaunchedEffect(route.offerId) { viewModel.loadSeatMaps(route.offerId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Choose seats") },
                navigationIcon = {
                    IconButton(onClick = { backStack.pop() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        val cabins = state.cabins.filter { it.segmentId == route.segmentId }
        if (state.loading || state.error != null || cabins.isEmpty()) {
            StatusBox(
                loading = state.loading,
                error = state.error,
                isEmpty = !state.loading && state.error == null && cabins.isEmpty(),
                modifier = Modifier.padding(padding),
                emptyMessage = "No seat map available for this flight.",
            )
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            cabins.forEach { cabin -> CabinGrid(cabin, route.segmentId, selectedSeats) { viewModel.toggleSeat(route.segmentId, it) } }
            Button(onClick = { backStack.pop() }, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        }
    }
}

@Composable
private fun CabinGrid(
    cabin: SeatCabinDto,
    segmentId: String,
    selectedSeats: Map<String, SeatDto>,
    onToggle: (SeatDto) -> Unit,
) {
    if (cabin.cabinClass.isNotBlank()) {
        Text(
            cabin.cabinClass.replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    cabin.rows.forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.seats.forEach { seat ->
                val selected = selectedSeats.containsKey("$segmentId|${seat.designator}")
                SeatChip(seat, selected) { onToggle(seat) }
            }
        }
    }
}

@Composable
private fun SeatChip(seat: SeatDto, selected: Boolean, onClick: () -> Unit) {
    val bg = when {
        selected -> MaterialTheme.colorScheme.primary
        seat.available -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when {
        selected -> MaterialTheme.colorScheme.onPrimary
        seat.available -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .let { if (seat.available) it.clickable { onClick() } else it },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            seat.designator,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            textAlign = TextAlign.Center,
        )
    }
}
