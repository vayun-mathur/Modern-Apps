package com.vayunmathur.travel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.util.TravelViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmationPage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
    route: Route.Confirmation,
) {
    val trips by viewModel.bookedTrips.collectAsStateWithLifecycle()
    val trip = trips.find { it.orderId == route.orderId }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Booking confirmed") }) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (trip == null) {
                Text("Trip not found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { backStack.reset(Route.Home) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Back to search")
                }
                return@Column
            }

            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp).padding(top = 8.dp),
            )
            Text("You're booked!", style = MaterialTheme.typography.headlineSmall)

            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DetailRow("Booking reference", trip.bookingReference, emphasize = true)
                    HorizontalDivider()
                    DetailRow("Route", trip.route)
                    DetailRow("Departs", TravelViewModel.prettyDate(trip.departDate))
                    DetailRow("Amount paid", formatMoney(trip.amount, trip.currency))
                    DetailRow("Status", trip.status.replaceFirstChar(Char::uppercase))
                }
            }

            Button(onClick = { backStack.reset(Route.Home) }, modifier = Modifier.fillMaxWidth()) {
                Text("Done")
            }
            OutlinedButton(
                onClick = { backStack.reset(Route.Home, Route.Trips) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("View my trips") }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, emphasize: Boolean = false) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = if (emphasize) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            color = if (emphasize) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (emphasize) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
