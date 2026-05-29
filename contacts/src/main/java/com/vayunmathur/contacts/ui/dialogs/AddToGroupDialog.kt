package com.vayunmathur.contacts.ui.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.util.ContactViewModel

@Composable
fun AddToGroupDialog(
    viewModel: ContactViewModel,
    contactIds: List<Long>,
    onDismiss: () -> Unit
) {
    val groups by viewModel.groups.collectAsState()
    val allMatches by viewModel.allMatches.collectAsState()
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_to_group)) },
        text = {
            if (groups.isEmpty()) {
                Text("No groups found. Create one in the Groups tab.")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)
                ) {
                    items(groups, key = { it.id }) { group ->
                        val groupMatches = allMatches.filter { it.rightID == group.id && it.type == ContactViewModel.GROUP_MATCH_TYPE }
                        val contactsInGroupCount = contactIds.count { id -> groupMatches.any { it.leftID == id } }
                        
                        val state = when {
                            contactsInGroupCount == 0 -> ToggleableState.Off
                            contactsInGroupCount == contactIds.size -> ToggleableState.On
                            else -> ToggleableState.Indeterminate
                        }

                        ListItem(
                            headlineContent = { Text(group.name) },
                            trailingContent = {
                                TriStateCheckbox(
                                    state = state,
                                    onClick = null // Handled by ListItem click
                                )
                            },
                            modifier = Modifier.clickable {
                                if (state == ToggleableState.Off) {
                                    viewModel.addContactsToGroup(contactIds, group.id)
                                } else {
                                    // Indeterminate or On goes to Off
                                    viewModel.removeContactsFromGroup(contactIds, group.id)
                                }
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ok))
            }
        }
    )
}
