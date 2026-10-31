package com.vayunmathur.findfamily.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.padding
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.OverlayAction
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.TopAppBarOverlay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import com.vayunmathur.findfamily.R
import com.vayunmathur.findfamily.util.MainPageActions
import com.vayunmathur.findfamily.util.MainPageUiState
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconNavigationArrow
import com.vayunmathur.library.ui.IconVerify

@Composable
internal fun MapOverlayBar(
    state: MainPageUiState,
    actions: MainPageActions,
    backupPassphrase: String? = null,
) {
    var backupMenuExpanded by remember { mutableStateOf(false) }

    val gpsWarningDescription = stringResource(R.string.gps_fallback_warning_content_description)
    val menuDescription = stringResource(R.string.map_menu_content_description)
    val findNearbyDescription = stringResource(R.string.find_nearby_content_description)
    val verifyDescription = stringResource(R.string.verify_security_code_content_description)
    val deletePersonDescription = stringResource(R.string.delete_person_content_description)
    val deletePlaceDescription = stringResource(R.string.delete_place_content_description)

    val selectedUserId = state.selectedUserId
    val selectedWaypointId = state.selectedWaypointId

    val overlayActions: List<OverlayAction> =
        if (selectedUserId == null && (selectedWaypointId == null || selectedWaypointId == 0L)) {
            listOfNotNull(
                if (state.usingGpsFallback) {
                    OverlayAction(
                        icon = {
                            // Use Image (not the tinting IconWarning) so the
                            // custom yellow-and-red drawable keeps both colors.
                            Image(
                                painter = painterResource(R.drawable.ic_warning_gps),
                                contentDescription = gpsWarningDescription
                            )
                        },
                        contentDescription = gpsWarningDescription,
                        onClick = { actions.onGpsWarningClick() }
                    )
                } else null,
                // Singular backup entry point: one overflow button whose menu holds
                // "Back up" and "Restore" (GitHub #879). Null in previews, where no
                // passphrase exists and the button stays hidden.
                backupPassphrase?.let { passphrase ->
                    OverlayAction(
                        icon = {
                            BackupMenuButton(passphrase, backupMenuExpanded, { backupMenuExpanded = it })
                        },
                        contentDescription = menuDescription,
                        onClick = { backupMenuExpanded = !backupMenuExpanded }
                    )
                }
            )
        } else if (selectedUserId != null && !state.historyMode) {
            if (state.isSelfSelected) emptyList() else listOfNotNull(
                // Find Nearby (UWB) needs both the public android.ranging API
                // (Android 16+) and an actual UWB radio. Hide the entry point otherwise.
                if (state.uwbAvailable) {
                    OverlayAction({ IconNavigationArrow() }, findNearbyDescription) {
                        actions.openUwbRanging(selectedUserId)
                    }
                } else null,
                OverlayAction({ IconVerify() }, verifyDescription) { actions.onShowSecurityCode() },
                OverlayAction({ IconDelete() }, deletePersonDescription) { actions.deleteSelectedUser() }
            )
        } else if (selectedWaypointId != null && selectedWaypointId != 0L) {
            listOf(
                OverlayAction({ IconDelete() }, deletePlaceDescription) { actions.deleteSelectedWaypoint() }
            )
        } else {
            emptyList()
        }

    TopAppBarOverlay(
        onNavigateBack = if (selectedUserId != null || selectedWaypointId != null) {
            {
                if (state.historyMode) actions.setShowingPresent(true) else actions.clearSelection()
            }
        } else null,
        actions = overlayActions,
        // The overlay draws no container of its own, so the history title carries its own
        // surface to stay legible over the map.
        title = if (state.historyMode) {
            {
                Card(Modifier.padding(start = Spacing.sm)) {
                    Text(
                        stringResource(R.string.history_title, state.selectedUser?.name ?: ""),
                        Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        } else null
    )
}
