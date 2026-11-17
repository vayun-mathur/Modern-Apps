package com.vayunmathur.contacts.ui.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.vayunmathur.library.ui.R as UiR
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.IconGroup
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TriStateCheckbox
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.platform.ContactViewModel
import com.vayunmathur.contacts.platform.addContactsToGroup
import com.vayunmathur.contacts.platform.removeContactsFromGroup

@Composable
fun AddToGroupDialog(
    viewModel: ContactViewModel,
    contactIds: List<Long>,
    onDismiss: () -> Unit
) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val memberships by viewModel.contactGroupMemberships.collectAsStateWithLifecycle()
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_to_group)) },
        text = {
            if (groups.isEmpty()) {
                Text(stringResource(R.string.no_groups_found))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)
                ) {
                    items(groups, key = { it.id }) { group ->
                        val groupMembers = memberships.filter { it.groupId == group.id }
                        val contactsInGroupCount = contactIds.count { id -> groupMembers.any { it.contactId == id } }
                        
                        val state = when {
                            contactsInGroupCount == 0 -> ToggleableState.Off
                            contactsInGroupCount == contactIds.size -> ToggleableState.On
                            else -> ToggleableState.Indeterminate
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (state == ToggleableState.Off) {
                                        viewModel.addContactsToGroup(contactIds, group.id)
                                    } else {
                                        // Indeterminate or On goes to Off
                                        viewModel.removeContactsFromGroup(contactIds, group.id)
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconGroup(
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = group.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            TriStateCheckbox(
                                state = state,
                                onClick = null // Handled by Row click
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(UiR.string.ok))
            }
        }
    )
}
