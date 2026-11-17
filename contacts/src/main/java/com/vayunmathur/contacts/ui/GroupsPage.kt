package com.vayunmathur.contacts.ui

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.R as UiR
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.ExpandVisibility
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.LoadingState
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TextField
import com.vayunmathur.library.ui.animatedColor
import com.vayunmathur.library.ui.animatedDp
import com.vayunmathur.library.ui.appBarScrollBehavior
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.Route
import com.vayunmathur.contacts.platform.ContactViewModel
import com.vayunmathur.contacts.platform.ContactsActions
import com.vayunmathur.contacts.platform.GroupWithContacts
import com.vayunmathur.contacts.platform.GroupsUiState
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconGroup
import com.vayunmathur.library.util.NavBackStack
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Binds [ContactViewModel] to the stateless [GroupsScreen]. */
@Composable
fun GroupsPage(viewModel: ContactViewModel, backStack: NavBackStack<Route>, expandGroupId: Long? = null) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val hasLoadedContacts by viewModel.hasLoadedContacts.collectAsStateWithLifecycle()

    // Membership is already part of every contact, so the rows are derived here rather
    // than collected per group — a stateless screen cannot subscribe to one flow per row.
    val groupsWithContacts = remember(groups, contacts) {
        groups.map { group ->
            GroupWithContacts(group, contacts.filter { c -> c.details.groups.any { it.groupId == group.id } })
        }
    }

    GroupsScreen(
        state = GroupsUiState(groups = groupsWithContacts, isLoading = !hasLoadedContacts),
        actions = object : ContactsActions by viewModel {
            override fun openContact(contact: com.vayunmathur.contacts.data.Contact) {
                backStack.add(Route.ContactDetail(contact.id))
            }
        },
        expandGroupId = expandGroupId,
    )
}
