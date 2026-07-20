package com.vayunmathur.travel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
 * Car search form: pickup location + pickup/drop-off dates. Upstream car
 * coverage is limited, so this is deep-link-first — the results page opens the
 * affiliate provider to see live prices.
 */
@Composable
fun CarSearchForm(
    @Suppress("UNUSED_PARAMETER") viewModel: TravelViewModel,
    onSearch: (location: String, pickup: String, dropoff: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var location by remember { mutableStateOf("") }
    var pickup by remember { mutableStateOf("") }
    var dropoff by remember { mutableStateOf("") }

    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = location,
            onValueChange = { location = it },
            label = { Text("Pickup city or airport") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        DateField("Pickup", pickup, onDate = { pickup = it })
        DateField("Drop-off", dropoff, onDate = { dropoff = it })

        Button(
            onClick = { onSearch(location, pickup, dropoff) },
            enabled = location.isNotBlank() && pickup.isNotBlank() && dropoff.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Search cars") }
    }
}
