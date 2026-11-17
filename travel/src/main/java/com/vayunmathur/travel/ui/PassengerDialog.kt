package com.vayunmathur.travel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.travel.R
import com.vayunmathur.library.ui.R as UiR

/**
 * Google-Flights-style passenger popup: Adults, Children (aged 2-11),
 * Infants in seat, Infants on lap — each a +/- stepper — with Cancel/Done.
 * State is staged locally so Cancel discards edits.
 */
@Composable
fun PassengerDialog(
    mix: PassengerMix,
    onDone: (PassengerMix) -> Unit,
    onCancel: () -> Unit,
) {
    var adults by remember(mix) { mutableIntStateOf(mix.adults) }
    var children by remember(mix) { mutableIntStateOf(mix.childAges.size) }
    var infantsInSeat by remember(mix) { mutableIntStateOf(mix.infantsInSeat) }
    var infantsOnLap by remember(mix) { mutableIntStateOf(mix.infantsOnLap) }

    Dialog(onDismissRequest = onCancel) {
        Surface(shape = MaterialTheme.shapes.medium) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CountStepper(stringResource(R.string.adults), adults, onCount = { adults = it }, min = 1, max = 9)
                Column {
                    CountStepper(
                        stringResource(R.string.children),
                        children,
                        onCount = { children = it },
                        min = 0,
                        max = 6,
                    )
                    Text(
                        stringResource(R.string.children_ages),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
                Column {
                    CountStepper(
                        stringResource(R.string.infants_in_seat),
                        infantsInSeat,
                        onCount = { infantsInSeat = it },
                        min = 0,
                        max = adults,
                    )
                    Text(
                        stringResource(R.string.in_seat),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
                Column {
                    CountStepper(
                        stringResource(R.string.infants_on_lap),
                        infantsOnLap,
                        onCount = { infantsOnLap = it },
                        min = 0,
                        max = adults,
                    )
                    Text(
                        stringResource(R.string.on_lap),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onCancel) { Text(stringResource(UiR.string.cancel)) }
                    TextButton(onClick = {
                        // Preserve existing child ages where possible; new
                        // children default to age 8.
                        val ages = (0 until children).map { mix.childAges.getOrElse(it) { 8 } }
                        onDone(
                            PassengerMix(
                                adults = adults,
                                childAges = ages,
                                infantsInSeat = infantsInSeat,
                                infantsOnLap = infantsOnLap,
                            )
                        )
                    }) { Text(stringResource(UiR.string.done)) }
                }
            }
        }
    }
}
