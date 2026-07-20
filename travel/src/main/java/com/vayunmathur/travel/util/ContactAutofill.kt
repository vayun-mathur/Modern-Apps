package com.vayunmathur.travel.util

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName

/** The subset of a contact used to autofill a passenger form. */
data class ContactInfo(
    val givenName: String,
    val familyName: String,
    val email: String,
    val phone: String,
    /** ISO `YYYY-MM-DD`, or blank if the contact has no full-date birthday. */
    val bornOn: String,
)

/**
 * Read name / email / phone for the contact at [contactUri] (as returned by the
 * system contact picker).
 *
 * No `READ_CONTACTS` permission is required: the picker grants temporary read
 * access to the chosen contact, and querying that contact's *entities*
 * directory (which joins its Data rows) is covered by the same grant. We must
 * therefore go through `contactUri` — querying the global Email/Phone/Data
 * tables by id would *not* be covered and would need the permission.
 *
 * Runs synchronous ContentResolver queries, so call off the main thread.
 * Returns null if the contact can't be read.
 */
fun readContact(context: Context, contactUri: Uri): ContactInfo? {
    val entityUri = Uri.withAppendedPath(
        contactUri, ContactsContract.Contacts.Entity.CONTENT_DIRECTORY,
    )
    val projection = arrayOf(
        ContactsContract.Contacts.Entity.MIMETYPE,
        ContactsContract.Contacts.Entity.DATA1,
        ContactsContract.Contacts.Entity.DATA2,
        ContactsContract.Contacts.Entity.DATA3,
        ContactsContract.Contacts.Entity.DISPLAY_NAME,
    )

    var given = ""
    var family = ""
    var email = ""
    var phone = ""
    var birthday = ""
    var display = ""

    context.contentResolver.query(entityUri, projection, null, null, null)?.use { c ->
        val mimeIdx = c.getColumnIndexOrThrow(ContactsContract.Contacts.Entity.MIMETYPE)
        // Email.ADDRESS / Phone.NUMBER / Event.START_DATE map to DATA1;
        // StructuredName given/family map to DATA2/DATA3; Event.TYPE is DATA2.
        // These are the shared generic Data columns.
        val data1 = c.getColumnIndexOrThrow(ContactsContract.Contacts.Entity.DATA1)
        val data2 = c.getColumnIndexOrThrow(ContactsContract.Contacts.Entity.DATA2)
        val data3 = c.getColumnIndexOrThrow(ContactsContract.Contacts.Entity.DATA3)
        val nameIdx = c.getColumnIndexOrThrow(ContactsContract.Contacts.Entity.DISPLAY_NAME)
        while (c.moveToNext()) {
            if (display.isBlank()) display = c.getString(nameIdx).orEmpty()
            when (c.getString(mimeIdx)) {
                StructuredName.CONTENT_ITEM_TYPE -> {
                    given = c.getString(data2).orEmpty()
                    family = c.getString(data3).orEmpty()
                }
                Email.CONTENT_ITEM_TYPE -> if (email.isBlank()) email = c.getString(data1).orEmpty()
                Phone.CONTENT_ITEM_TYPE -> if (phone.isBlank()) phone = c.getString(data1).orEmpty()
                Event.CONTENT_ITEM_TYPE -> {
                    if (birthday.isBlank() && c.getInt(data2) == Event.TYPE_BIRTHDAY) {
                        birthday = isoBirthday(c.getString(data1))
                    }
                }
            }
        }
    } ?: return null

    // Fall back to splitting the display name if there's no structured name.
    if (given.isBlank() && family.isBlank() && display.isNotBlank()) {
        val parts = display.trim().split(" ").filter { it.isNotBlank() }
        given = parts.firstOrNull().orEmpty()
        family = if (parts.size > 1) parts.drop(1).joinToString(" ") else ""
    }

    return ContactInfo(
        givenName = given,
        familyName = family,
        email = email,
        phone = normalizePhone(phone),
        bornOn = birthday,
    )
}

/** Strip spaces/dashes/parens so the number is closer to the E.164 Duffel wants. */
private fun normalizePhone(raw: String): String =
    raw.filter { it.isDigit() || it == '+' }

/**
 * Normalize a contact birthday to ISO `YYYY-MM-DD`. Contacts store birthdays as
 * `yyyy-MM-dd` or, when the year is unknown, `--MM-dd`. We only accept a full
 * date (a year is required for Duffel's `born_on`), returning blank otherwise.
 */
private fun isoBirthday(raw: String?): String {
    val s = raw?.trim().orEmpty()
    return if (s.length == 10 && s[4] == '-' && s[7] == '-' && s.take(4).all { it.isDigit() }) s else ""
}
