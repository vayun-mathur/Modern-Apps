package com.vayunmathur.travel.ui
import com.vayunmathur.travel.R

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.travel.util.TravelViewModel
import com.vayunmathur.travel.util.staySuggestions
import androidx.compose.ui.res.stringResource
import kotlinx.datetime.LocalDate

/**
 * Hotel search form (used from Home when the "Stays" product is selected):
 * location autocomplete, check-in/out dates, rooms and guests dropdowns.
 *
 * Stateless: [state] is hoisted to the home screen so it survives a round
 * trip to the results. Check-in/out are picked together in a range calendar.
 *
 * Date rules: check-in not before today; check-out after check-in.
 */
@Composable
fun StaySearchForm(
    viewModel: TravelViewModel,
    state: StayFormState,
    onState: (StayFormState) -> Unit,
    modifier: Modifier = Modifier,
    onSearch: (place: String, checkIn: String, checkOut: String, rooms: Int, adults: Int, latitude: Double?, longitude: Double?) -> Unit,
) {
    val today = remember { todayUtc() }
    val checkInDate = remember(state.checkIn) { runCatching { LocalDate.parse(state.checkIn) }.getOrNull() }
    val checkOutDate = remember(state.checkOut) { runCatching { LocalDate.parse(state.checkOut) }.getOrNull() }

    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchDropdown(
                display = "${state.rooms} ${stringResource(R.string.rooms)}",
                options = (1..5).map { it.toString() to "$it" },
                selectedValue = state.rooms.toString(),
                onSelect = { onState(state.copy(rooms = it.toInt())) },
            )
            SearchDropdown(
                display = "${state.guests} ${stringResource(R.string.guests)}",
                options = (1..9).map { it.toString() to "$it" },
                selectedValue = state.guests.toString(),
                onSelect = { onState(state.copy(guests = it.toInt())) },
            )
        }
        StaySuggestField(
            label = stringResource(R.string.destination_or_hotel),
            viewModel = viewModel,
            initialText = state.placeText.ifBlank { state.place },
            onSelect = { name, lat, lng, text ->
                onState(state.copy(place = name, latitude = lat, longitude = lng, placeText = text))
            },
        )
        DateRangeFields(
            startLabel = stringResource(R.string.check_in),
            endLabel = stringResource(R.string.check_out),
            start = state.checkIn,
            end = state.checkOut,
            onRange = { start, end -> onState(state.copy(checkIn = start, checkOut = end)) },
            minDate = today,
            allowSameDay = false,
        )
        val checkInValid = checkInDate != null && checkInDate >= today
        val checkOutValid = checkOutDate != null && checkInDate != null && checkOutDate > checkInDate
        Button(
            onClick = {
                onSearch(state.place, state.checkIn, state.checkOut, state.rooms, state.guests, state.latitude, state.longitude)
            },
            enabled = state.place.isNotBlank() && checkInValid && checkOutValid,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.search_hotels)) }
    }
}

/**
 * Stays location autocomplete: type a city or hotel name and pick a suggestion,
 * which carries coordinates so the search can run without an airport code.
 */
@Composable
private fun StaySuggestField(
    label: String,
    viewModel: TravelViewModel,
    initialText: String,
    onSelect: (name: String, latitude: Double?, longitude: Double?, text: String) -> Unit,
) {
    var text by remember { mutableStateOf(initialText) }
    // Restoring the saved text must not pop the suggestion list open.
    var justSelected by remember { mutableStateOf(initialText.isNotBlank()) }
    var suggestions by remember { mutableStateOf<List<com.vayunmathur.travel.network.StaySuggestionDto>>(emptyList()) }

    LaunchedEffect(text) {
        if (justSelected) {
            justSelected = false
            return@LaunchedEffect
        }
        if (text.length < 2) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(100)
        suggestions = viewModel.staySuggestions(text)
    }

    Column {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                // Free-text fallback: pass the typed text with no coordinates so
                // the server resolves it as an IATA code.
                onSelect(it.trim(), null, null, it)
            },
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (suggestions.isNotEmpty()) {
            ElevatedCard(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Column {
                    suggestions.take(6).forEach { s ->
                        com.vayunmathur.library.ui.ListItem(
                            modifier = Modifier.clickable {
                                onSelect(s.name, s.latitude, s.longitude, s.name)
                                text = s.name
                                justSelected = true
                                suggestions = emptyList()
                            },
                        ) { Text(s.name) }
                    }
                }
            }
        }
    }
}
