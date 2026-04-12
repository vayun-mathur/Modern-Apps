package com.vayunmathur.contacts.intents

import com.vayunmathur.contacts.CDKPhone
import com.vayunmathur.contacts.Contact
import com.vayunmathur.contacts.ContactDetails
import com.vayunmathur.contacts.Name
import com.vayunmathur.contacts.PhoneNumber
import com.vayunmathur.library.intents.contacts.ContactData
import com.vayunmathur.library.util.AssistantIntent
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.serializer

@OptIn(InternalSerializationApi::class)
class InsertIntent: AssistantIntent<ContactData, Unit>(serializer<ContactData>(), serializer<Unit>()) {

    override suspend fun performCalculation(input: ContactData) {
        val contact = Contact(0L, null, null, false, ContactDetails(
            phoneNumbers = listOf(PhoneNumber(0, input.phoneNumber, CDKPhone.TYPE_MOBILE)),
            emails = emptyList(),
            addresses = emptyList(),
            dates = emptyList(),
            photos = emptyList(),
            names = listOf(Name(0, "", input.name, "", "", "")),
            orgs = emptyList(),
            notes = emptyList(),
            nicknames = emptyList()
        ))
        contact.save(this, contact.details, ContactDetails.empty())
    }
}
