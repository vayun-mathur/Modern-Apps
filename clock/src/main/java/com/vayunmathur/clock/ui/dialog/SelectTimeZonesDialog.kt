package com.vayunmathur.clock.ui.dialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.clock.Route
import com.vayunmathur.clock.citiesToTimezones
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SelectTimeZonesDialog(backStack: NavBackStack<Route>, ds: DataStoreUtils) {
    val selectedTimeZones by ds.stringSetFlow("time_zones").collectAsState(initial = setOf())
    var searchQuery by remember { mutableStateOf("") }

    // Map all available IDs to a pair of (Clean City Name, Original ID)
    val allOptions = remember(citiesToTimezones) {
        citiesToTimezones?.entries?.map { Triple(it.key, it.value, "${it.key} ${it.value}".lowercase()) }
    } ?: listOf()

    val filteredOptions by produceState(initialValue = allOptions, searchQuery) {
        value = if(searchQuery.isEmpty()) {
            allOptions
        } else {
            withContext(Dispatchers.Default) {
                allOptions.filter { (_, _, searchable) ->
                    searchable.contains(searchQuery.lowercase())
                }
            }
        }
    }
    Dialog({ backStack.pop() }) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f) // Limit height so it doesn't take the whole screen
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Select Cities", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search city or region...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                LazyColumn(Modifier.weight(1f)) {
                    items(filteredOptions) { (city, id) ->
                        val isSelected = city in selectedTimeZones
                        ListItem(
                            headlineContent = { Text(city) },
                            supportingContent = { Text(id, style = MaterialTheme.typography.labelSmall) },
                            trailingContent = {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { checked ->
                                        if (checked) ds.addStringToSet("time_zones", city)
                                        else ds.removeStringFromSet("time_zones", city)
                                    }
                                )
                            },
                            modifier = Modifier.clickable {
                                // Toggle on row click for better UX
                                if (!isSelected) ds.addStringToSet("time_zones", city)
                                else ds.removeStringFromSet("time_zones", city)
                            },
                            colors = ListItemDefaults.colors(Color.Transparent)
                        )
                    }
                }
            }
        }
    }
}