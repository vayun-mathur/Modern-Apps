package com.vayunmathur.travel.ui
import com.vayunmathur.travel.R

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.sharedContainer
import com.vayunmathur.library.util.sharedText
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.network.StayRateDto
import com.vayunmathur.travel.platform.TravelViewModel
import com.vayunmathur.travel.platform.loadStayRates
import com.vayunmathur.travel.platform.selectStayRate
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StayDetailPage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
    route: Route.StayDetail,
) {
    val state by viewModel.stayRates.collectAsStateWithLifecycle()
    LaunchedEffect(route.searchResultId) { viewModel.loadStayRates(route.searchResultId, route.name) }

    AppScaffold(
        title = route.name.ifBlank { stringResource(R.string.hotel) },
        backStack = backStack,
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        val rates = state.rates
        if (rates == null) {
            StatusBox(loading = state.loading, error = state.error, isEmpty = !state.loading, modifier = Modifier.padding(padding))
            return@AppScaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            rates.photos.firstOrNull()?.let { photo ->
                AsyncImage(
                    model = photo,
                    contentDescription = rates.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .sharedContainer("travel-stay-photo-${route.searchResultId}"),
                )
            }
            Column(Modifier.padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                Text(
                    rates.name.ifBlank { stringResource(R.string.hotel) },
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.sharedText("travel-stay-name-${route.searchResultId}"),
                )
                Text(
                    starLabel(rates.rating, rates.reviewScore),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.sharedText("travel-stay-rating-${route.searchResultId}"),
                )
                if (rates.address.isNotBlank()) {
                    Text(rates.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (rates.amenities.isNotEmpty()) {
                    Text(
                        rates.amenities.take(8).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (rates.description.isNotBlank()) {
                    Text(rates.description, style = MaterialTheme.typography.bodyMedium)
                }
                val times = listOf(
                    rates.checkInTime.takeIf { it.isNotBlank() }
                        ?.let { "${stringResource(R.string.check_in)} $it" },
                    rates.checkOutTime.takeIf { it.isNotBlank() }
                        ?.let { "${stringResource(R.string.check_out)} $it" },
                ).filterNotNull()
                if (times.isNotEmpty()) {
                    Text(
                        times.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider()
            SectionHeader(stringResource(R.string.rooms))
            rates.rooms.forEach { room ->
                Text(
                    room.name.ifBlank { stringResource(R.string.room) },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                room.rates.forEach { rate ->
                    RateRow(rate) {
                        viewModel.selectStayRate(rate)
                        backStack.add(Route.StayGuests)
                    }
                }
            }
            Column(Modifier.padding(bottom = 24.dp)) {}
        }
    }
}

@Composable
private fun RateRow(rate: StayRateDto, onSelect: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable { onSelect() },
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(rate.boardType.ifBlank { stringResource(R.string.room_only) }.replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.bodyLarge)
                Text(rate.cancellation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                formatMoney(rate.totalAmount, rate.totalCurrency),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
