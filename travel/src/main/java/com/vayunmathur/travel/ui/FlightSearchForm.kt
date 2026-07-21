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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.travel.util.FlightQuery
import com.vayunmathur.travel.util.TravelViewModel

/** The Duffel cabin classes, with a display label. */
enum class Cabin(val code: String, val label: String) {
    ECONOMY("economy", "Economy"),
    PREMIUM("premium_economy", "Premium"),
    BUSINESS("business", "Business"),
    FIRST("first", "First"),
}

/** The three supported trip shapes. */
enum class TripType(val label: String) {
    ONE_WAY("One-way"),
    ROUND_TRIP("Round trip"),
    MULTI_CITY("Multi-city"),
}

/** A single editable leg (origin/destination/date) used by the form. */
private data class Leg(var origin: String = "", var destination: String = "", var date: String = "")

/**
 * Flight search form: trip type (one-way / round-trip / multi-city), origin /
 * destination autocomplete, dates, cabin class, passenger mix (adults, children
 * with ages, infants) and a nonstop toggle. Calls [onSearch] with a normalized
 * [FlightQuery] when the user taps "Search flights".
 */
@Composable
fun FlightSearchForm(
    viewModel: TravelViewModel,
    modifier: Modifier = Modifier,
    onSearch: (FlightQuery) -> Unit,
) {
    var tripType by remember { mutableStateOf(TripType.ONE_WAY) }

    // One-way / round-trip fields.
    var origin by remember { mutableStateOf("") }
    var destination by remember { mutableStateOf("") }
    var depart by remember { mutableStateOf("") }
    var returnDate by remember { mutableStateOf("") }

    // Multi-city legs.
    val legs = remember { mutableStateListOf(Leg(), Leg()) }

    // Passengers.
    var adults by remember { mutableIntStateOf(1) }
    val childAges = remember { mutableStateListOf<Int>() }
    var infants by remember { mutableIntStateOf(0) }

    var cabin by remember { mutableStateOf(Cabin.ECONOMY) }
    var nonstop by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TripType.entries.forEach { t ->
                FilterChip(selected = tripType == t, onClick = { tripType = t }, label = { Text(t.label) })
            }
        }

        when (tripType) {
            TripType.MULTI_CITY -> {
                legs.forEachIndexed { index, leg ->
                    MultiCityLeg(
                        index = index,
                        viewModel = viewModel,
                        onOrigin = { legs[index] = leg.copy(origin = it) },
                        onDestination = { legs[index] = leg.copy(destination = it) },
                        date = leg.date,
                        onDate = { legs[index] = leg.copy(date = it) },
                        removable = legs.size > 2,
                        onRemove = { legs.removeAt(index) },
                    )
                }
                if (legs.size < 5) {
                    OutlinedButton(onClick = { legs.add(Leg()) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Add another flight")
                    }
                }
            }
            else -> {
                PlaceAutocompleteField("From", viewModel, onCodeChange = { origin = it })
                PlaceAutocompleteField("To", viewModel, onCodeChange = { destination = it })
                DateField("Depart", depart, onDate = { depart = it })
                if (tripType == TripType.ROUND_TRIP) {
                    DateField("Return", returnDate, onDate = { returnDate = it })
                }
            }
        }

        HorizontalDivider()

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Cabin.entries.forEach { c ->
                FilterChip(selected = cabin == c, onClick = { cabin = c }, label = { Text(c.label) })
            }
        }

        CountStepper("Adults", adults, onCount = { adults = it }, min = 1)
        CountStepper("Children", childAges.size, onCount = { count ->
            while (childAges.size < count) childAges.add(8)
            while (childAges.size > count) childAges.removeAt(childAges.size - 1)
        }, min = 0, max = 6)
        childAges.forEachIndexed { index, age ->
            CountStepper("  Child ${index + 1} age", age, onCount = { childAges[index] = it }, min = 0, max = 17)
        }
        CountStepper("Infants (on lap)", infants, onCount = { infants = it }, min = 0, max = adults)

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Nonstop only")
            Switch(checked = nonstop, onCheckedChange = { nonstop = it })
        }

        val valid = when (tripType) {
            TripType.MULTI_CITY -> legs.all { it.origin.isNotBlank() && it.destination.isNotBlank() && it.date.isNotBlank() }
            TripType.ROUND_TRIP -> origin.isNotBlank() && destination.isNotBlank() && depart.isNotBlank() && returnDate.isNotBlank()
            TripType.ONE_WAY -> origin.isNotBlank() && destination.isNotBlank() && depart.isNotBlank()
        }

        Button(
            onClick = {
                val slices = when (tripType) {
                    TripType.MULTI_CITY -> legs.joinToString(",") { "${it.origin}:${it.destination}:${it.date}" }
                    TripType.ROUND_TRIP -> "$origin:$destination:$depart,$destination:$origin:$returnDate"
                    TripType.ONE_WAY -> "$origin:$destination:$depart"
                }
                onSearch(
                    FlightQuery(
                        slices = slices,
                        adults = adults,
                        children = childAges.joinToString(","),
                        infants = infants,
                        cabin = cabin.code,
                        maxConnections = if (nonstop) 0 else -1,
                        isRoundTrip = tripType == TripType.ROUND_TRIP,
                    )
                )
            },
            enabled = valid,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Search flights") }
    }
}

@Composable
private fun MultiCityLeg(
    index: Int,
    viewModel: TravelViewModel,
    onOrigin: (String) -> Unit,
    onDestination: (String) -> Unit,
    date: String,
    onDate: (String) -> Unit,
    removable: Boolean,
    onRemove: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Flight ${index + 1}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            if (removable) {
                IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = "Remove flight") }
            }
        }
        PlaceAutocompleteField("From", viewModel, onCodeChange = onOrigin)
        PlaceAutocompleteField("To", viewModel, onCodeChange = onDestination)
        DateField("Date", date, onDate = onDate)
    }
}
