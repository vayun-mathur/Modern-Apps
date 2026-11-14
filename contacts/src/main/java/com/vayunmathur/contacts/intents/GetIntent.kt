package com.vayunmathur.contacts.intents

import android.provider.ContactsContract
import com.vayunmathur.library.intents.contacts.ContactData
import com.vayunmathur.library.util.AssistantIntent
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.serializer

@OptIn(InternalSerializationApi::class)
/** Assistant intent returning every contact's name and phone number from the provider. */
class GetIntent: AssistantIntent<Unit, List<ContactData>>(serializer<Unit>(), serializer<List<ContactData>>()) {

    override suspend fun performCalculation(input: Unit): List<ContactData> {
        val contacts = mutableListOf<ContactData>()
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )?.use { cursor ->
            val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                contacts.add(ContactData(
                    name = cursor.getString(nameIdx) ?: "",
                    phoneNumber = cursor.getString(numberIdx) ?: ""
                ))
            }
        }
        return contacts
    }
}
