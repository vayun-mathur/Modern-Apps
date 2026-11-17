package com.vayunmathur.findfamily.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.vayunmathur.findfamily.R
import com.vayunmathur.findfamily.Route
import com.vayunmathur.findfamily.ui.dialogs.SecurityCodeDialog
import com.vayunmathur.findfamily.platform.FindFamilyViewModel
import com.vayunmathur.findfamily.network.Networking
import com.vayunmathur.findfamily.platform.PersonActions
import com.vayunmathur.findfamily.platform.PersonUiState
import com.vayunmathur.findfamily.platform.Platform
import com.vayunmathur.findfamily.platform.UwbSessionManager
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.IconRestore
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.NavBackStack

/**
 * Sheet destination for one person (`Route.PersonSheet`), shown over the
 * map. The route argument is the selection: this entry exists only while a
 * person is selected, and dismissing it (back / scrim) pops back to the map.
 *
 * Hosts the person detail sheet plus the actions that used to branch on the
 * selected user in the map overlay/FAB: Find Nearby, verify, delete, and
 * entering history mode (the scrubber renders here; the map underneath
 * keeps showing the historical pin for [userId]).
 */
@Composable
fun PersonSheet(
    backStack: NavBackStack<Route>,
    ffViewModel: FindFamilyViewModel,
    platform: Platform,
    userId: Long,
) {
    // A pushed person starts in present mode with no stale historical pin.
    LaunchedEffect(userId) { ffViewModel.mapState.onPersonOpened() }

    val users by ffViewModel.users.collectAsState()
    val user = users.firstOrNull { it.id == userId }
    val userPositions by ffViewModel.latestLocationByUser.collectAsState()
    val waypoints by ffViewModel.waypoints.collectAsState()
    val globalSharingEnabled by ffViewModel.globalSharingEnabled.collectAsState()
    val crowdFindingEnabled by ffViewModel.crowdFindingEnabled.collectAsState()
    val connectedUsers by ffViewModel.connectedUsers.collectAsState()
    val isShowingPresent by ffViewModel.isShowingPresent.collectAsState()
    val historyMode = !isShowingPresent

    var showSecurityCode by remember { mutableStateOf(false) }

    val requestPickContact = platform.requestPickContact { name, photo ->
        ffViewModel.updateContactNamePhoto(userId, name, photo)
    }
    val personActions = remember(ffViewModel) {
        object : PersonActions by ffViewModel {
            override fun changeConnectedContact() = requestPickContact()
        }
    }

    Dialog(onDismissRequest = { backStack.pop() }) {
        Card {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (user == null) {
                    // Row deleted (or not yet loaded): nothing to show.
                    Text(stringResource(R.string.delete_person_content_description))
                } else if (historyMode) {
                    Text(
                        stringResource(R.string.history_title, user.name),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Box(Modifier.fillMaxWidth()) {
                        HistoryScrubber(backStack, ffViewModel, userId) {
                            ffViewModel.mapState.setHistoricalPosition(it)
                        }
                    }
                    OutlinedButton(
                        { ffViewModel.mapState.setShowingPresent(true) },
                        Modifier.fillMaxWidth(),
                    ) {
                        IconRestore()
                        Text(stringResource(R.string.history_title, user.name))
                    }
                } else {
                    PersonDetailSheet(
                        PersonUiState(
                            user,
                            userPositions[user.id],
                            waypoints,
                            globalSharingEnabled,
                            crowdFindingEnabled,
                            connectedUsers,
                        ),
                        personActions,
                    )
                    val context = LocalContext.current
                    val isSelf = userId == Networking.userid
                    if (!isSelf) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (UwbSessionManager.isAvailable(context)) {
                                OutlinedButton(
                                    { backStack.add(Route.UwbRangingPage(userId)) },
                                    Modifier.weight(1f),
                                ) { Text(stringResource(R.string.find_nearby_content_description)) }
                            }
                            OutlinedButton(
                                { showSecurityCode = true },
                                Modifier.weight(1f),
                            ) { Text(stringResource(R.string.verify_security_code_content_description)) }
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                { ffViewModel.mapState.setShowingPresent(false) },
                                Modifier.weight(1f),
                            ) {
                                IconRestore()
                                Text(stringResource(R.string.history_title, user.name))
                            }
                            OutlinedButton(
                                {
                                    ffViewModel.deleteUser(user)
                                    backStack.pop()
                                },
                                Modifier.weight(1f),
                            ) { Text(stringResource(R.string.delete_person_content_description)) }
                        }
                    }
                }
            }
        }
    }

    if (showSecurityCode && user != null && userId != Networking.userid) {
        SecurityCodeDialog(user, ffViewModel) { showSecurityCode = false }
    }
}
