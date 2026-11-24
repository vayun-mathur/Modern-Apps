package com.vayunmathur.contacts.wear

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Navigation destinations for the wear companion: list, and detail carrying its contact id. */
sealed interface Route : NavKey {

    @Serializable
    data object ContactList : Route

    @Serializable
    data class ContactDetail(val contactId: Long) : Route
}
