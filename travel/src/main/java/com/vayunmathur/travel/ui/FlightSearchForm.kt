package com.vayunmathur.travel.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconSwapLanguages
import com.vayunmathur.library.ui.Text
import com.vayunmathur.travel.util.FlightQuery
import com.vayunmathur.travel.util.TravelViewModel
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import com.vayunmathur.travel.R
import kotlinx.datetime.LocalDate

/** The booking-provider cabin classes, with a display label. */
enum class Cabin(val code: String, @StringRes val label: Int) {
    ECONOMY("economy", R.string.economy),
    PREMIUM("premium_economy", R.string.premium),
    BUSINESS("business", R.string.business),
    FIRST("first", R.string.first),
}

/** The two supported trip shapes (multi-city was removed). */
enum class TripType(@StringRes val label: Int) {
    ONE_WAY(R.string.one_way),
    ROUND_TRIP(R.string.round_trip),
}

/** Max-stops options for the dropdown, mapped to the API's maxStops values. */
enum class MaxStops(val apiValue: Int, @StringRes val label: Int) {
    NONSTOP(0, R.string.stops_nonstop),
    ONE(1, R.string.stops_1),
    TWO_PLUS(2, R.string.stops_2_plus),
}

/**
 * Flight search form (Google-Flights style): a dropdown row (trip type,
 * passengers, cabin, max stops) above side-by-side From/To fields with a swap
 * button, then dates. Calls [onSearch] with a normalized [FlightQuery] when
 * the user taps "Search flights".
 *
 * Stateless: [state] is hoisted to the home screen so the form is still
 * filled in when the user comes back. Round trips pick both dates in one
 * range calendar.
 *
 * Date rules: no date before today; return not before depart.
 */
@Composable
fun FlightSearchForm(
    viewModel: TravelViewModel,
    state: FlightFormState,
    onState: (FlightFormState) -> Unit,
    modifier: Modifier = Modifier,
    onSearch: (FlightQuery) -> Unit,
) {
    // Bumped on swap so the autocomplete fields re-init from the swapped text
    // (they own their editing text internally).
    var swapNonce by remember { mutableIntStateOf(0) }

    val today = remember { todayUtc() }
    val departDate = remember(state.depart) { runCatching { LocalDate.parse(state.depart) }.getOrNull() }
    val returnLocalDate = remember(state.returnDate) { runCatching { LocalDate.parse(state.returnDate) }.getOrNull() }

    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchDropdown(
                display = stringResource(state.tripType.label),
                options = TripType.entries.map { it.name to stringResource(it.label) },
                selectedValue = state.tripType.name,
                onSelect = { name -> onState(state.copy(tripType = TripType.valueOf(name))) },
            )
            PassengerField(mix = state.mix, onMix = { onState(state.copy(mix = it)) })
            SearchDropdown(
                display = stringResource(state.cabin.label),
                options = Cabin.entries.map { it.name to stringResource(it.label) },
                selectedValue = state.cabin.name,
                onSelect = { name -> onState(state.copy(cabin = Cabin.valueOf(name))) },
            )
            SearchDropdown(
                display = stringResource(state.maxStops.label),
                options = MaxStops.entries.map { it.name to stringResource(it.label) },
                selectedValue = state.maxStops.name,
                onSelect = { name -> onState(state.copy(maxStops = MaxStops.valueOf(name))) },
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.runtime.key(swapNonce) {
                PlaceAutocompleteField(
                    "From",
                    viewModel,
                    onChange = { code, text -> onState(state.copy(origin = code, originText = text)) },
                    modifier = Modifier.weight(1f),
                    initialText = state.originText.ifBlank { state.origin },
                )
            }
            IconButton(onClick = {
                onState(state.swapped())
                swapNonce++
            }) { IconSwapLanguages() }
            androidx.compose.runtime.key(swapNonce) {
                PlaceAutocompleteField(
                    "To",
                    viewModel,
                    onChange = { code, text -> onState(state.copy(destination = code, destinationText = text)) },
                    modifier = Modifier.weight(1f),
                    initialText = state.destinationText.ifBlank { state.destination },
                )
            }
        }

        if (state.tripType == TripType.ROUND_TRIP) {
            DateRangeFields(
                startLabel = stringResource(R.string.depart),
                endLabel = stringResource(R.string.return_label),
                start = state.depart,
                end = state.returnDate,
                onRange = { start, end -> onState(state.copy(depart = start, returnDate = end)) },
                minDate = today,
            )
        } else {
            DateField(
                stringResource(R.string.depart),
                state.depart,
                onDate = { onState(state.copy(depart = it)) },
                modifier = Modifier.fillMaxWidth(),
                minDate = today,
            )
        }

        val departValid = departDate != null && departDate >= today
        val returnValid = state.tripType == TripType.ONE_WAY ||
            (returnLocalDate != null && departDate != null && returnLocalDate >= departDate)
        val valid = state.origin.isNotBlank() && state.destination.isNotBlank() && departValid && returnValid

        Button(
            onClick = {
                val mix = state.mix
                // Infants in seats fly as children (age 1) so they hold a seat;
                // infants on lap use the infant bucket.
                val childAges = mix.childAges + List(mix.infantsInSeat) { 1 }
                val (origin, destination) = state.origin to state.destination
                val slices = when (state.tripType) {
                    TripType.ROUND_TRIP -> "$origin:$destination:${state.depart},$destination:$origin:${state.returnDate}"
                    TripType.ONE_WAY -> "$origin:$destination:${state.depart}"
                }
                onSearch(
                    FlightQuery(
                        slices = slices,
                        adults = mix.adults,
                        children = childAges.joinToString(","),
                        infants = mix.infantsOnLap,
                        cabin = state.cabin.code,
                        maxConnections = state.maxStops.apiValue,
                        isRoundTrip = state.tripType == TripType.ROUND_TRIP,
                    )
                )
            },
            enabled = valid,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.search_flights)) }
    }
}
