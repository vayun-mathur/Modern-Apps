package com.vayunmathur.contacts.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.data.ContactGroup
import com.vayunmathur.contacts.data.GroupMembership
import com.vayunmathur.contacts.platform.ContactViewModel
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconEvent
import com.vayunmathur.library.ui.MultiCategoryPicker
import com.vayunmathur.library.util.expandFromLine
import com.vayunmathur.library.util.sharedContainer

/**
 * Groups picker, last to match the detail page. Anything on both pages is in the same order on
 * both, so a value does not have to be hunted for after the morph, and nothing has to travel past
 * the whole form to reach its counterpart.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColumnScope.EditContactGroupSection(
    draft: ContactViewModel.ContactDraft,
    allGroups: List<ContactGroup>,
    contactId: Long?,
    onUpdate: (ContactViewModel.ContactDraft) -> Unit,
) {
    val draftGroupIds = draft.groupMemberships.map { it.groupId }.toSet()
    val memberGroups = allGroups.filter { it.id in draftGroupIds && it.name.trim().isNotEmpty() }
    val availableGroups = allGroups.filter { it.id !in draftGroupIds && it.name.trim().isNotEmpty() }
    GroupMembershipSection(
        memberGroups = memberGroups,
        availableGroups = availableGroups,
        onAddGroup = { groupId ->
            onUpdate(draft.copy(
                groupMemberships = draft.groupMemberships + GroupMembership(0, groupId)
            ))
        },
        onRemoveGroup = { groupId ->
            onUpdate(draft.copy(
                groupMemberships = draft.groupMemberships.filter { gm -> gm.groupId != groupId }
            ))
        },
        sharedId = contactId,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ColumnScope.GroupMembershipSection(
    memberGroups: List<ContactGroup>,
    availableGroups: List<ContactGroup>,
    onAddGroup: (Long) -> Unit,
    onRemoveGroup: (Long) -> Unit,
    sharedId: Long? = null,
) {
    if (memberGroups.isEmpty() && availableGroups.isEmpty()) return

    MultiCategoryPicker(
        label = stringResource(R.string.groups),
        selected = memberGroups,
        available = availableGroups,
        itemLabel = { it.name },
        modifier = Modifier.expandFromLine(),
        onAdd = { onAddGroup(it.id) },
        onRemove = { onRemoveGroup(it.id) },
        chipModifier = { group ->
            if (sharedId == null) Modifier
            else Modifier.sharedContainer("contact-group-$sharedId-${group.id}")
        },
    )
}

/** Icon for the date-details add button, kept next to its section. */
@Composable
internal fun DateDetailsAddIcon() {
    IconEvent()
}
