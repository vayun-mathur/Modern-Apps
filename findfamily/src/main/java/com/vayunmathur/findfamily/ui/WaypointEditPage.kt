package com.vayunmathur.findfamily.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FlexibleBottomAppBar
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.findfamily.Route
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.pop

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaypointEditPage(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel, waypointID: Long) {
    val waypoint by viewModel.get<Waypoint>(waypointID)

    var name by remember { mutableStateOf(waypoint.name) }
    var range by remember { mutableStateOf(waypoint.range.toString()) }
    var coord by remember { mutableStateOf(waypoint.coord) }

    Scaffold(floatingActionButton = {
        FloatingActionButton({
            if(range.toDoubleOrNull() == null || name.isBlank()) return@FloatingActionButton
            viewModel.upsert(waypoint.copy(name = name, range = range.toDouble(), coord = coord))
            backStack.pop()
        }) {
            IconSave()
        }
    }, bottomBar = {
        Surface(Modifier.heightIn(max = 400.dp)) {
            Column(Modifier.padding(16.dp).padding(bottom = 12.dp)) {
                OutlinedTextField(name, {name = it}, Modifier.fillMaxWidth(), isError = name.isBlank(), supportingText = if(name.isBlank()) { {Text("Name cannot be blank") } } else null)
                Spacer(Modifier.heightIn(8.dp))
                OutlinedTextField(range, {range = it}, Modifier.fillMaxWidth(), suffix = {Text("meters")}, keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number
                ), isError = range.toDoubleOrNull() == null, supportingText = if(range.toDoubleOrNull() == null) { {Text("Range must be a number") } } else null)
            }
        }
    }) { paddingValues ->
        Column(Modifier.padding(paddingValues)) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                MapView(backStack, viewModel, navEnabled = true, selectedWaypoint = SelectedWaypoint(waypoint, range.toDoubleOrNull() ?: 0.0, {coord = it}))
            }
        }
    }
}