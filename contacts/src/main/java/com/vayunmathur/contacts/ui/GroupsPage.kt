package com.vayunmathur.contacts.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.Route
import com.vayunmathur.contacts.util.ContactViewModel
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.util.NavBackStack
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupsPage(viewModel: ContactViewModel, backStack: NavBackStack<Route>, expandGroupId: Long? = null) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }
    var newGroupName by remember { mutableStateOf("") }
    var groupToDelete by remember { mutableStateOf<com.vayunmathur.contacts.data.ContactGroup?>(null) }
    val expandedGroups = remember { 
        val list = mutableStateListOf<Long>()
        expandGroupId?.let { list.add(it) }
        list
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.groups)) }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                IconAdd()
            }
        },
        bottomBar = {
            ContactsBottomNavBar(backStack)
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            groups.forEach { group ->
                val isExpanded = group.id in expandedGroups
                // Combine each group's header + (optional) expanded contacts
                // into ONE item so we can:
                //   - share the same `getContactsForGroup` collect between
                //     "show count" and "render rows",
                //   - animate the header color + bottom-corner radius alongside
                //     the contacts list's expand/collapse without the LazyColumn
                //     swapping items underneath us mid-animation.
                item(key = "group-${group.id}") {
                    val contactsInGroup by viewModel.getContactsForGroup(group.id).collectAsStateWithLifecycle(initialValue = emptyList())
                    val attachContacts = isExpanded && contactsInGroup.isNotEmpty()

                    val collapsedColor = MaterialTheme.colorScheme.surfaceVariant
                    val expandedColor = MaterialTheme.colorScheme.secondaryContainer
                    val headerColor by animateColorAsState(
                        targetValue = if (attachContacts) expandedColor else collapsedColor,
                        label = "groupHeaderColor",
                    )
                    val bottomRadius by animateDpAsState(
                        targetValue = if (attachContacts) 4.dp else 16.dp,
                        label = "groupHeaderBottomRadius",
                    )
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
                                    Text(stringResource(R.string.contacts_count, contactsInGroup.size))
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
                                        Icon(
                                            painter = painterResource(R.drawable.baseline_group_24),
                                            contentDescription = null,
                                            tint = Color.White
                                        )
                                    }
                                },
                                trailingContent = {
                                    IconButton(onClick = { groupToDelete = group }) {
                                        IconDelete()
                                    }
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }

                        // Animate the contacts list expanding/collapsing in
                        // sync with the header's shape+color animation. The
                        // 2dp gap is part of the visible block so it appears
                        // and disappears with the list rather than leaving a
                        // gap when collapsed.
                        AnimatedVisibility(visible = attachContacts) {
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
                                        viewModel = viewModel,
                                        embeddedInCard = true,
                                        onClick = {
                                            backStack.add(Route.ContactDetail(contact.id))
                                        }
                                    )
                                }
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
                        viewModel.addGroup(newGroupName)
                        newGroupName = ""
                        showAddDialog = false
                    }
                }) {
                    Text(stringResource(R.string.add))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text(stringResource(R.string.cancel))
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
                    groupToDelete?.let { viewModel.deleteGroup(it.id) }
                    groupToDelete = null
                }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { groupToDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
