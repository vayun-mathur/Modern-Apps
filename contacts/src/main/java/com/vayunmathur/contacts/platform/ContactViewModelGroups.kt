package com.vayunmathur.contacts.platform

import android.app.Application
import android.content.ContentProviderOperation
import android.provider.ContactsContract
import com.vayunmathur.library.log.Log
import androidx.lifecycle.viewModelScope
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.isSimAccountType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Group-membership batch operations and contact lookup queries, kept out of
 * ContactViewModel so the class stays under the function cap (see TooManyFunctions).
 * Called as viewModel.*.
 */
/**
 * Group-membership batch operations, kept with the account helpers so
 * ContactViewModel stays under the function cap (see TooManyFunctions).
 * Called as viewModel.*.
 */
fun ContactViewModel.addContactsToGroup(contactIds: List<Long>, groupId: Long) {
    viewModelScope.launch(Dispatchers.IO) {
        // Filter out SIM contacts (they don't support groups)
        val deviceIds = contactIds.filter { id ->
            val c = allContactsState.value.find { it.id == id }
            c == null || !isSimAccountType(c.accountType)
        }
        if (deviceIds.isEmpty()) return@launch
        val resolver = getApplication<Application>().contentResolver
        val ops = ArrayList<ContentProviderOperation>()
        deviceIds.forEach { contactId ->
            ops.add(buildAddToGroupOperation(contactId, groupId))
        }
        applyGroupBatch(resolver, ops, "Error adding contacts to group")
    }
}

private fun buildAddToGroupOperation(
    contactId: Long,
    groupId: Long,
): ContentProviderOperation =
    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
        .withValue(ContactsContract.Data.RAW_CONTACT_ID, contactId)
        .withValue(
            ContactsContract.Data.MIMETYPE,
            ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE
        )
        .withValue(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID, groupId)
        .build()

// Broad catch is deliberate: provider batch writes fail with OEM-specific
// RuntimeExceptions beyond the checked batch exceptions, and a group edit
// must log rather than crash the caller.
@Suppress("TooGenericExceptionCaught")
private fun ContactViewModel.applyGroupBatch(
    resolver: android.content.ContentResolver,
    ops: ArrayList<ContentProviderOperation>,
    errorMessage: String,
) {
    try {
        resolver.applyBatch(ContactsContract.AUTHORITY, ops)
    } catch (e: android.content.OperationApplicationException) {
        Log.error("ContactViewModel", errorMessage, e)
    } catch (e: android.os.RemoteException) {
        Log.error("ContactViewModel", errorMessage, e)
    } catch (e: Exception) {
        Log.error("ContactViewModel", errorMessage, e)
    }
}

fun ContactViewModel.removeContactsFromGroup(contactIds: List<Long>, groupId: Long) {
    viewModelScope.launch(Dispatchers.IO) {
        val resolver = getApplication<Application>().contentResolver
        val ops = ArrayList<ContentProviderOperation>()
        contactIds.forEach { contactId ->
            ops.add(buildRemoveFromGroupOperation(contactId, groupId))
        }
        applyGroupBatch(resolver, ops, "Error removing contacts from group")
    }
}

private fun buildRemoveFromGroupOperation(
    contactId: Long,
    groupId: Long,
): ContentProviderOperation =
    ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
        .withSelection(
            "${ContactsContract.Data.RAW_CONTACT_ID} = ? " +
                "AND ${ContactsContract.Data.MIMETYPE} = ? " +
                "AND ${ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID} = ?",
            arrayOf(
                contactId.toString(),
                ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE,
                groupId.toString()
            )
        )
        .build()

/**
 * Contact lookup and group-membership queries, kept with the account helpers
 * so ContactViewModel stays under the function cap (see TooManyFunctions).
 * Called as viewModel.*.
 */

/**
 * Contact lookup and group-membership queries, kept with the account helpers
 * so ContactViewModel stays under the function cap (see TooManyFunctions).
 * Called as viewModel.*.
 */
internal fun ContactViewModel.findContact(contactId: Long): Contact? {
    return contacts.value.find { it.id == contactId }
        ?: allContactsState.value.find { it.id == contactId }
}

internal fun ContactViewModel.contactsInGroup(groupId: Long): Flow<List<Contact>> {
    return combine(contacts, contactGroupMemberships) { list, memberships ->
        val contactIds = memberships.filter { it.groupId == groupId }.map { it.contactId }
        list.filter { it.id in contactIds }
    }
}

internal fun ContactViewModel.contactFlow(contactId: Long): Flow<Contact?> {
    return contacts.map { list ->
        list.find { it.id == contactId } ?: allContactsState.value.find { it.id == contactId }
    }
}
