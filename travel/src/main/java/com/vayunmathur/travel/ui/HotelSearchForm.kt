package com.vayunmathur.travel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.travel.util.TravelViewModel

/**
 * Hotel search form: destination (free text — the proxy resolves the location),
 * check-in/out dates, and a guest count.
 */
@Composable
fun HotelSearchForm(
    @Suppress("UNUSED_PARAMETER") viewModel: TravelViewModel,
    onSearch: (location: String, checkin: String, checkout: String, adults: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var location by remember { mutableStateOf("") }
    var checkin by remember { mutableStateOf("") }
    var checkout by remember { mutableStateOf("") }
    var adults by remember { mutableIntStateOf(2) }

    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = location,
            onValueChange = { location = it },
            label = { Text("City or destination") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        DateField("Check-in", checkin, onDate = { checkin = it })
        DateField("Check-out", checkout, onDate = { checkout = it })
        CountStepper("Guests", adults, onCount = { adults = it })

        Button(
            onClick = { onSearch(location, checkin, checkout, adults) },
            enabled = location.isNotBlank() && checkin.isNotBlank() && checkout.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Search hotels") }
    }
}
