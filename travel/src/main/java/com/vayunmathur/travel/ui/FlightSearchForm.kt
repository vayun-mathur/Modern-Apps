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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import com.vayunmathur.travel.util.TravelViewModel

/** The Duffel cabin classes, with a display label. */
enum class Cabin(val code: String, val label: String) {
    ECONOMY("economy", "Economy"),
    PREMIUM("premium_economy", "Premium"),
    BUSINESS("business", "Business"),
    FIRST("first", "First"),
}

/**
 * Flight search form: origin/destination autocomplete, depart (+ optional
 * return) dates, cabin class, and a passenger count. Calls [onSearch] with the
 * collected query when the user taps "Search flights".
 */
@Composable
fun FlightSearchForm(
    viewModel: TravelViewModel,
    modifier: Modifier = Modifier,
    onSearch: (origin: String, destination: String, depart: String, ret: String?, adults: Int, cabin: String) -> Unit,
) {
    var origin by remember { mutableStateOf("") }
    var destination by remember { mutableStateOf("") }
    var depart by remember { mutableStateOf("") }
    var roundTrip by remember { mutableStateOf(false) }
    var returnDate by remember { mutableStateOf("") }
    var adults by remember { mutableIntStateOf(1) }
    var cabin by remember { mutableStateOf(Cabin.ECONOMY) }

    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PlaceAutocompleteField("From", viewModel, onCodeChange = { origin = it })
        PlaceAutocompleteField("To", viewModel, onCodeChange = { destination = it })
        DateField("Depart", depart, onDate = { depart = it })

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Round trip")
            Switch(checked = roundTrip, onCheckedChange = { roundTrip = it })
        }
        if (roundTrip) {
            DateField("Return", returnDate, onDate = { returnDate = it })
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Cabin.entries.forEach { c ->
                FilterChip(
                    selected = cabin == c,
                    onClick = { cabin = c },
                    label = { Text(c.label) },
                )
            }
        }

        CountStepper("Passengers", adults, onCount = { adults = it })

        Button(
            onClick = {
                onSearch(
                    origin,
                    destination,
                    depart,
                    returnDate.takeIf { roundTrip && it.isNotBlank() },
                    adults,
                    cabin.code,
                )
            },
            enabled = origin.isNotBlank() && destination.isNotBlank() && depart.isNotBlank() &&
                (!roundTrip || returnDate.isNotBlank()),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Search flights") }
    }
}
