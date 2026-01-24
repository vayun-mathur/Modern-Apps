package com.vayunmathur.calendar.ui.dialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.library.util.LocalNavResultRegistry
import com.vayunmathur.library.util.pop
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimezonePickerDialog(backStack: NavBackStack<com.vayunmathur.calendar.Route>, resultKey: String) {
    val registry = LocalNavResultRegistry.current
    val scope = rememberCoroutineScope()
    val allZones = java.time.ZoneId.getAvailableZoneIds().toList().sorted()

    var query by remember { mutableStateOf("") }

    // Build a list of pairs (zoneId, offsetString) once so we can search offsets too
    val zoneWithOffset = remember(allZones) {
        allZones.map { z ->
            val zid = java.time.ZoneId.of(z)
            // compute current offset for this zone
            val offset = java.time.ZonedDateTime.now(zid).offset
            val offsetId = if (offset.id == "Z") "+00:00" else offset.id
            val offsetStr = "UTC${offsetId}"
            z to offsetStr
        }
    }

    val zones = if (query.isBlank()) zoneWithOffset
    else zoneWithOffset.filter { (z, off) -> z.contains(query, ignoreCase = true) || off.contains(query, ignoreCase = true) }

    AlertDialog(
        onDismissRequest = { backStack.pop() },
        title = { Text("Choose timezone") },
        text = {
            Column {
                // Search field
                OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), label = { Text("Search") })

                // Scrollable list of zones with offsets
                LazyColumn(modifier = Modifier.height(360.dp)) {
                    items(zones) { (z, off) ->
                        Row(modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                scope.launch { registry.dispatchResult(resultKey, z) }
                                backStack.pop()
                            }
                            .padding(12.dp)) {
                            Column(modifier = Modifier.fillMaxWidth().padding(end = 8.dp)) {
                                Text(text = z, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(text = "($off)", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { backStack.pop() }) { Text("Close") }
        }
    )
}
