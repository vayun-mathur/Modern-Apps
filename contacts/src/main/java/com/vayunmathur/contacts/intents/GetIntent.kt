package com.vayunmathur.contacts.intents

import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.library.intents.contacts.ContactData
import com.vayunmathur.library.util.AssistantIntent
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.serializer

@OptIn(InternalSerializationApi::class)
class GetIntent: AssistantIntent<Unit, List<ContactData>>(serializer<Unit>(), serializer<List<ContactData>>()) {

    override suspend fun performCalculation(input: Unit): List<ContactData> {
        return Contact.getAllContacts(this).map { contact ->
            ContactData(
                name = contact.name.firstName + " " + contact.name.lastName,
                phoneNumber = contact.details.phoneNumbers.firstOrNull()?.number ?: ""
            )
        }
    }
}
