package com.vayunmathur.findfamily.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.vayunmathur.findfamily.R
import com.vayunmathur.findfamily.Route
import com.vayunmathur.findfamily.platform.FindFamilyViewModel
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.NavBackStack

/**
 * Sheet destination for one waypoint (`Route.WaypointSheet`), shown over
 * the map. The route argument is the selection (0 = create a new place);
 * the editable form state itself (name / range / coordinate) lives in
 * `MapPageState` and is prefilled by `beginCreateWaypoint` /
 * `beginEditWaypoint` before this destination is pushed.
 *
 * The coordinate follows the map centre while the sheet is open (the map
 * underneath keeps reporting its dragged centre for the selected
 * waypoint), matching the previous in-page editor.
 */
@Composable
fun WaypointSheet(
    backStack: NavBackStack<Route>,
    ffViewModel: FindFamilyViewModel,
    waypointId: Long,
) {
    val waypointName by ffViewModel.waypointName.collectAsState()
    val waypointRange by ffViewModel.waypointRange.collectAsState()
    val waypoints by ffViewModel.waypoints.collectAsState()
    val existing = waypoints.firstOrNull { it.id == waypointId }

    Dialog(onDismissRequest = { backStack.pop() }) {
        Card {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    waypointName,
                    { ffViewModel.mapState.setWaypointName(it) },
                    Modifier.fillMaxWidth(),
                    isError = waypointName.isBlank(),
                    supportingText = if (waypointName.isBlank()) {
                        { Text(stringResource(R.string.waypoint_name_blank_error)) }
                    } else null,
                )
                OutlinedTextField(
                    waypointRange,
                    { ffViewModel.mapState.setWaypointRange(it) },
                    Modifier.fillMaxWidth(),
                    suffix = { Text(stringResource(R.string.waypoint_range_suffix)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = waypointRange.toDoubleOrNull() == null,
                    supportingText = if (waypointRange.toDoubleOrNull() == null) {
                        { Text(stringResource(R.string.waypoint_range_error)) }
                    } else null,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        { backStack.pop() },
                        Modifier.weight(1f),
                    ) { Text(stringResource(android.R.string.cancel)) }
                    Button(
                        {
                            if (ffViewModel.mapState.saveCurrentWaypoint(waypointId)) {
                                backStack.pop()
                            }
                        },
                        Modifier.weight(1f),
                    ) { Text(stringResource(android.R.string.ok)) }
                }
                if (waypointId != 0L && existing != null) {
                    OutlinedButton(
                        {
                            ffViewModel.deleteWaypoint(existing)
                            backStack.pop()
                        },
                        Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.delete_place_content_description)) }
                }
            }
        }
    }
}
