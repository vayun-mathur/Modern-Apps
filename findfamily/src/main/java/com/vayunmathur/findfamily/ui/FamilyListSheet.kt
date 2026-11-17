package com.vayunmathur.findfamily.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.findfamily.R
import androidx.compose.runtime.Composable
import com.vayunmathur.findfamily.data.TemporaryLink
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.findfamily.ui.dialogs.encodeBase26
import com.vayunmathur.findfamily.platform.FamilyListActions
import com.vayunmathur.findfamily.platform.FamilyListUiState
import com.vayunmathur.library.ui.IconCopy
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconAdd

@Composable
fun FamilyListSheet(state: FamilyListUiState, actions: FamilyListActions) {
    LazyColumn(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        // Keys are namespaced per section. All four lists live in one LazyColumn but their
        // ids come from independent tables, so a user and a waypoint that happen to share an
        // id would collide and Compose would throw "Key N was already used".
        items(
            state.connectedUsers,
            key = { "user-${it.id}" }
        ) {
            UserCard(it, state.locationByUser[it.id], true) {
                actions.selectUser(it.id)
            }
        }
        if (state.awaitingRequestUsers.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.section_location_sharing_requests)) }
        }
        items(
            state.awaitingRequestUsers,
            key = { "request-${it.id}" }
        ) {
            AwaitingRequestCard(it.id) { actions.acceptRequest(it.id) }
        }
        if (state.temporaryLinks.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.section_temporary_links)) }
        }
        items(state.temporaryLinks, key = { "link-${it.id}" }) {
            TemporaryLinkCard(it, { actions.copyLink(it) }, { actions.deleteTemporaryLink(it) })
        }
        if (state.waypoints.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.section_saved_places)) }
        }
        items(state.waypoints, key = { "waypoint-${it.id}" }) {
            WaypointCard(it, state.userNamesByLocationName[it.name].orEmpty()) {
                actions.beginEditWaypoint(it)
            }
        }
    }
}








