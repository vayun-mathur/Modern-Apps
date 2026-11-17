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

/**
 * The groups page, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`, which is where the store listing images come from.
 *
 * [expandGroupId] starts one group expanded; the app passes the group it navigated to, and
 * previews use it to capture the expanded state without driving the UI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupsScreen(state: GroupsUiState, actions: ContactsActions, expandGroupId: Long? = null) {
    var showAddDialog by remember { mutableStateOf(false) }
    var newGroupName by remember { mutableStateOf("") }
    var groupToDelete by remember { mutableStateOf<com.vayunmathur.contacts.data.ContactGroup?>(null) }
    val expandedGroups = remember {
        val list = mutableStateListOf<Long>()
        expandGroupId?.let { list.add(it) }
        list
    }

    LazyListScaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                IconAdd()
            }
        },
        horizontalPadding = 8.dp,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        scrollBehavior = appBarScrollBehavior(),
    ) {
            if (state.groups.isEmpty()) {
                item(key = "groups-empty") {
                    if (state.isLoading) {
                        LoadingState(modifier = Modifier.fillParentMaxSize())
                    } else {
                        EmptyState(
                            title = stringResource(R.string.no_groups_yet),
                            modifier = Modifier.fillParentMaxSize(),
                            message = stringResource(R.string.no_groups_yet_message),
                            icon = { IconGroup() },
                        )
                    }
                }
            }

            state.groups.forEach { (group, contactsInGroup) ->
                val isExpanded = group.id in expandedGroups
                // Combine each group's header + (optional) expanded contacts
                // into ONE item so we can animate the header color + bottom-corner
                // radius alongside the contacts list's expand/collapse without the
                // LazyColumn swapping items underneath us mid-animation.
                item(key = "group-${group.id}") {
                    val attachContacts = isExpanded && contactsInGroup.isNotEmpty()

                    val collapsedColor = MaterialTheme.colorScheme.surfaceVariant
                    val expandedColor = MaterialTheme.colorScheme.secondaryContainer
                    val headerColor = animatedColor(
                        if (attachContacts) expandedColor else collapsedColor
                    )
                    val bottomRadius = animatedDp(if (attachContacts) 4.dp else 16.dp)
                    val headerShape = RoundedCornerShape(
                        topStart = 16.dp, topEnd = 16.dp,
                        bottomStart = bottomRadius, bottomEnd = bottomRadius,
                    )

                    Column {
                        Surface(
                            onClick = {
                                if (isExpanded) {
                                    expandedGroups.remove(group.id)
                                } else {
                                    expandedGroups.add(group.id)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = headerShape,
                            color = headerColor,
                        ) {
                            ListItem(
                                content = {
                                    Text(
                                        text = group.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                },
                                supportingContent = {
                                    Text(pluralStringResource(R.plurals.contacts_count, contactsInGroup.size, contactsInGroup.size))
                                },
                                leadingContent = {
                                    Box(
                                        modifier = Modifier
                                            .size(50.dp)
                                            .background(
                                                color = getAvatarColor(group.id),
                                                shape = CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        IconGroup(
                                            tint = Color.White
                                        )
                                    }
                                },
                                trailingContent = {
                                    IconButton(onClick = { groupToDelete = group }) {
                                        IconDelete()
                                    }
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                        }

                        // Animate the contacts list expanding/collapsing in
                        // sync with the header's shape+color animation. The
                        // 2dp gap is part of the visible block so it appears
                        // and disappears with the list rather than leaving a
                        // gap when collapsed.
                        ExpandVisibility(visible = attachContacts) {
                            Column {
                                Spacer(Modifier.height(2.dp))
                                GroupedContactSection(
                                    count = contactsInGroup.size,
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    topAttached = true,
                                ) { idx ->
                                    val contact = contactsInGroup[idx]
                                    ContactItem(
                                        contact = contact,
                                        isSelected = false,
                                        showAccountLabels = false,
                                        decodePhoto = actions::decodePhoto,
                                        embeddedInCard = true,
                                        onClick = { actions.openContact(contact) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(stringResource(R.string.add_group)) },
            text = {
                TextField(
                    value = newGroupName,
                    onValueChange = { newGroupName = it },
                    placeholder = { Text(stringResource(R.string.enter_group_name)) }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newGroupName.isNotBlank()) {
                        actions.addGroup(newGroupName)
                        newGroupName = ""
                        showAddDialog = false
                    }
                }) {
                    Text(stringResource(UiR.string.add))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text(stringResource(UiR.string.cancel))
                }
            }
        )
    }

    if (groupToDelete != null) {
        AlertDialog(
            onDismissRequest = { groupToDelete = null },
            title = { Text(stringResource(R.string.delete_group_title)) },
            text = { Text(stringResource(R.string.delete_group_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    groupToDelete?.let { actions.deleteGroup(it.id) }
                    groupToDelete = null
                }) {
                    Text(stringResource(UiR.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { groupToDelete = null }) {
                    Text(stringResource(UiR.string.cancel))
                }
            }
        )
    }
}
