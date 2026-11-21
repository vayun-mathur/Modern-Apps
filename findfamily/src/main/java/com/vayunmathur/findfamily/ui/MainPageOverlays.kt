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
import com.vayunmathur.findfamily.platform.MainPageActions
import com.vayunmathur.findfamily.platform.MainPageUiState
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

    // Selection-scoped actions (Find Nearby / verify / delete) live on the
    // PersonSheet / WaypointSheet destinations. The map's own overlay only
    // carries the GPS warning and the backup menu; dismissal of a sheet is
    // the back stack's job, so there is no navigate-back affordance here.
    val overlayActions: List<OverlayAction> = listOfNotNull(
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

    TopAppBarOverlay(
        onNavigateBack = null,
        actions = overlayActions,
        title = {
            RelayStatusChip(
                relayConnected = state.relayConnected,
                lastLocationMillis = state.lastLocationMillis,
            )
        }
    )
}
