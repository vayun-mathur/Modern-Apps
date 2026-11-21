package com.vayunmathur.findfamily.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.vayunmathur.findfamily.R
import com.vayunmathur.findfamily.Route
import com.vayunmathur.findfamily.domain.FirstRun
import com.vayunmathur.findfamily.domain.FirstRunStage
import com.vayunmathur.findfamily.platform.FindFamilyViewModel
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.library.util.NavBackStack

/**
 * Staged first-run sheet (`Route.FirstRunSheet`), shown over the map only
 * for a brand-new user (see [FirstRun.isFirstRun]). Returning users and the
 * already-granted fast path never reach it.
 *
 * The stage is derived, not stored: no family yet is [FirstRunStage.PairFamily]
 * (explain + add a family member, no permission asked), and once a family
 * member exists it becomes [FirstRunStage.EnableLocationSharing] (location
 * is asked for only now, when sharing is being enabled). Notifications are
 * not part of this sheet at all — they are requested contextually at the
 * first alert-relevant action (see [rememberNotificationPermissionAction]).
 *
 * Dismissing with "Later" only pops the sheet for this launch; finishing the
 * location stage marks the flow complete in the ViewModel.
 */
@Composable
fun FirstRunSheet(
    backStack: NavBackStack<Route>,
    ffViewModel: FindFamilyViewModel,
    onFinished: () -> Unit = {},
) {
    val hasFamily by ffViewModel.hasFamilyMember.collectAsState()
    val hasForeground by ffViewModel.hasForeground.collectAsState()
    val hasBackground by ffViewModel.hasBackground.collectAsState()
    val hasBluetooth by ffViewModel.hasBluetooth.collectAsState()
    val locationGranted = hasForeground && hasBackground && hasBluetooth
    val stage = FirstRun.stage(hasFamily, locationGranted)

    val messenger = rememberMessenger()
    val locationDeniedMessage = stringResource(R.string.first_run_location_denied)
    val ensureLocationSharing = rememberLocationSharingAction {
        messenger.show(locationDeniedMessage)
    }

    Dialog(onDismissRequest = { backStack.pop() }) {
        Card {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (stage) {
                    FirstRunStage.PairFamily -> {
                        Text(
                            stringResource(R.string.first_run_pair_title),
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Text(stringResource(R.string.first_run_pair_body))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                { backStack.pop() },
                                Modifier.weight(1f),
                            ) { Text(stringResource(R.string.first_run_later)) }
                            Button(
                                { backStack.add(Route.AddPersonDialog()) },
                                Modifier.weight(1f),
                            ) { Text(stringResource(R.string.first_run_add_family)) }
                        }
                    }
                    FirstRunStage.EnableLocationSharing -> {
                        Text(
                            stringResource(R.string.first_run_location_title),
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Text(stringResource(R.string.first_run_location_body))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                { backStack.pop() },
                                Modifier.weight(1f),
                            ) { Text(stringResource(R.string.first_run_later)) }
                            Button(
                                {
                                    ensureLocationSharing {
                                        ffViewModel.refreshPermissions()
                                        onFinished()
                                        backStack.pop()
                                    }
                                },
                                Modifier.weight(1f),
                            ) { Text(stringResource(R.string.first_run_allow_location)) }
                        }
                    }
                    FirstRunStage.Done -> {
                        // Family + location are in place; the sheet has
                        // nothing left to stage, so close it.
                        Text(
                            stringResource(R.string.first_run_done_title),
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Text(stringResource(R.string.first_run_done_body))
                        Button(
                            {
                                onFinished()
                                backStack.pop()
                            },
                            Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.first_run_done_action)) }
                    }
                }
            }
        }
    }
}
