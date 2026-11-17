package com.vayunmathur.contacts.platform

import android.graphics.Bitmap
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactGroup

/** A group together with its members, so a row can show its count without its own flow. */
data class GroupWithContacts(val group: ContactGroup, val contacts: List<Contact>)

/** Everything the contact list draws. */
data class ContactListUiState(
    val contacts: List<Contact> = emptyList(),
    val groups: List<ContactGroup> = emptyList(),
    val searchQuery: String = "",
    val showAccountLabels: Boolean = true,
    /**
     * Storage badge text per contact, keyed "accountType|accountName".
     * Absent keys fall back to the raw account name.
     */
    val accountLabels: Map<String, String> = emptyMap(),
    /** Contact open in the detail pane, highlighted in the list on a wide screen. */
    val openContactId: Long? = null,
    val showAddButton: Boolean = true,
    /** True until the first sync from the provider finishes, so an empty list isn't mistaken for "no contacts". */
    val isLoading: Boolean = false,
)

/** Everything the contact details page draws. */
data class ContactDetailsUiState(
    val contact: Contact,
    val groups: List<ContactGroup> = emptyList(),
    /** Messaging apps that have a row for this contact, resolved from the package manager. */
    val platforms: ContactPlatforms = ContactPlatforms(),
    val isGoogleMeetInstalled: Boolean = false,
)

/** Everything the groups page draws. */
data class GroupsUiState(
    val groups: List<GroupWithContacts> = emptyList(),
    /** True until the first sync from the provider finishes. */
    val isLoading: Boolean = false,
)

/**
 * Contact screen callbacks. Every method has a no-op default so a preview can render a
 * screen without supplying behaviour — [Noop] is the whole implementation a preview needs.
 */
interface ContactsActions {
    fun setSearchQuery(query: String) {}
    fun deleteContact(contact: Contact) {}
    fun saveContact(contact: Contact) {}
    fun addGroup(name: String) {}
    fun deleteGroup(groupId: Long) {}

    /** Decodes a Base64 contact photo, through the ViewModel's cache. */
    fun decodePhoto(base64: String): Bitmap? = null

    fun openContact(contact: Contact) {}
    fun addContact() {}
    fun editContact(contactId: Long) {}

    /** Asks for confirmation before deleting — the plain [deleteContact] does not. */
    fun confirmDeleteContact(contact: Contact) {}
    fun closeContact() {}
    /** Opens the system ringtone picker; the result is saved straight onto [contact]. */
    fun pickRingtone(contact: Contact) {}
    fun addToGroup(contactIds: List<Long>) {}

    /** Opens the move-to-account flow for [contactIds] (details + multi-select entry points). */
    fun moveContacts(contactIds: List<Long>) {}

    /** Exports [contacts] to a VCF named [filename] and opens a share chooser. */
    fun shareContacts(contacts: List<Contact>, filename: String) {}

    /** Shared no-op implementation for previews and stateless screens. */
    companion object {
        val Noop: ContactsActions = object : ContactsActions {}
    }
}
