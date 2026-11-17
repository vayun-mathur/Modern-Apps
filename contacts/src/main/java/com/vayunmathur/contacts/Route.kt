package com.vayunmathur.contacts

import com.vayunmathur.contacts.data.ContactPrefill
import com.vayunmathur.library.util.NavKey
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/** Navigation destinations for the contacts host: sibling tab routes, the rest push onto the back stack. */
sealed interface Route: NavKey {
    @Serializable
    /** Contacts tab destination (sibling route, the home tab). */
    object ContactsList : Route

    @Serializable
    /** Groups tab destination (sibling route), optionally expanding the group with [expandGroupId]. */
    data class GroupsList(val expandGroupId: Long? = null) : Route

    @Serializable
    /** Dialog destination adding [contactIds] to a chosen group. */
    data class AddToGroupDialog(val contactIds: List<Long>) : Route

    @Serializable
    /** Detail page destination for the contact with [contactId]. */
    data class ContactDetail(val contactId: Long) : Route

    @Serializable
    /** Editor destination for [contactId] (null creates a contact), seeded by [prefill] when present. */
    data class EditContact(
        val contactId: Long?,
        val prefill: ContactPrefill? = null
    ) : Route

    @Serializable
    /** Insert-or-edit destination seeded by [prefill], used by dialer-style intents. */
    data class InsertOrEditContact(
        val prefill: ContactPrefill? = null
    ) : Route

    @Serializable
    /** Date-picker dialog destination identified by [id], starting at [initialDate]. */
    data class EventDatePickerDialog(val id: String, val initialDate: LocalDate?): Route

    @Serializable
    /** Delete-confirmation dialog destination for [contactId], labelled with [contactName]. */
    data class EventDeleteConfirmDialog(val contactId: Long, val contactName: String?): Route

    @Serializable
    /** Settings tab destination (sibling route). */
    object Settings : Route

    @Serializable
    /** Dialog destination for creating a new contacts account. */
    object AddAccountDialog : Route

    @Serializable
    /** Photo-crop destination for the image at [uri]. */
    data class CropPhoto(val uri: String) : Route

    @Serializable
    /** vCard import destination for the files at [uris]. */
    data class ImportVcf(val uris: List<String>) : Route
}
