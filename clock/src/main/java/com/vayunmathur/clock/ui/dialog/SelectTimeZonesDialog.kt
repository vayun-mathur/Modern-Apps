package com.vayunmathur.clock.ui.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.clock.Route
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.pop
import kotlinx.datetime.TimeZone

@Composable
fun SelectTimeZonesDialog(backStack: NavBackStack<Route>, ds: DataStoreUtils) {
    val selectedTimeZones by ds.stringSetFlow("time_zones").collectAsState(setOf())
    Dialog({backStack.pop()}) {
        Card {
            LazyColumn(Modifier.padding(16.dp)) {
                items(TimeZone.availableZoneIds.toList()) {id ->
                    ListItem({Text(id)}, trailingContent = {
                        Checkbox(id in selectedTimeZones, {
                            if(it) {
                                ds.addStringToSet("time_zones", id)
                            } else {
                                ds.removeStringFromSet("time_zones", id)
                            }
                        })
                    }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                }
            }
        }
    }
}