package com.vayunmathur.contacts.intents

import com.vayunmathur.contacts.data.CDKNickname
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactDetails
import com.vayunmathur.contacts.data.Name
import com.vayunmathur.contacts.data.NewContactAccountPolicy
import com.vayunmathur.contacts.data.Nickname
import com.vayunmathur.contacts.data.Note
import com.vayunmathur.contacts.data.Organization
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.contacts.data.SystemDefaultAccountSource
import com.vayunmathur.library.intents.contacts.ContactData
import com.vayunmathur.library.util.AssistantIntent
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.serializer

@OptIn(InternalSerializationApi::class)
class InsertIntent: AssistantIntent<ContactData, Unit>(serializer<ContactData>(), serializer<Unit>()) {

    override suspend fun performCalculation(input: ContactData) {
        // Seed default name/org/note/nickname rows (mirror processDetails defaults)
        // so the saved contact always has them.
        val details = ContactDetails.empty().copy(
            phoneNumbers = listOf(PhoneNumber(0, input.phoneNumber, CDKPhone.TYPE_MOBILE)),
            names = listOf(Name(0, "", input.name, "", "", "")),
            orgs = listOf(Organization(0, "")),
            notes = listOf(Note(0, "")),
            nicknames = listOf(Nickname(0, "", CDKNickname.TYPE_DEFAULT))
        )
        // No account was asked for, so use the system default: on Android 16+ a cloud
        // default makes the provider refuse the device-local account (#916).
        val cloud = NewContactAccountPolicy.resolve(SystemDefaultAccountSource(this))?.cloudAccount
        val contact = Contact(0L, cloud?.type, cloud?.name, false, details)
        contact.save(this, contact.details, ContactDetails.empty())
    }
}
