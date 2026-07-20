package com.vayunmathur.travel.util

import android.content.Context
import android.database.Cursor
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

/** The Data MIME types we ask the picker for / read out of the result. */
val REQUESTED_CONTACT_FIELDS: List<String> = listOf(
    StructuredName.CONTENT_ITEM_TYPE,
    Email.CONTENT_ITEM_TYPE,
    Phone.CONTENT_ITEM_TYPE,
    Event.CONTENT_ITEM_TYPE,
)

/**
 * Read contact fields from a **session URI** returned by the Android 17
 * `ContactsPickerSessionContract.ACTION_PICK_CONTACTS` picker. No
 * `READ_CONTACTS` permission is needed: the picker grants field-scoped read
 * access (for the MIME types requested) on this URI.
 *
 * Call off the main thread. Returns null if nothing can be read.
 */
fun readSessionContact(context: Context, sessionUri: Uri): ContactInfo? = try {
    context.contentResolver.query(sessionUri, DATA_PROJECTION, null, null, null)?.use(::parseContactCursor)
} catch (_: Exception) {
    null
}

/**
 * Shared projection over the generic Data columns. These column names are the
 * same on the session URI and in the Data table, so one projection + one parser
 * serves the source.
 */
private val DATA_PROJECTION = arrayOf(
    ContactsContract.Data.MIMETYPE,
    ContactsContract.Data.DATA1,
    ContactsContract.Data.DATA2,
    ContactsContract.Data.DATA3,
    ContactsContract.Data.DISPLAY_NAME,
)

private fun parseContactCursor(c: Cursor): ContactInfo? {
    val mimeIdx = c.getColumnIndex(ContactsContract.Data.MIMETYPE)
    // Email.ADDRESS / Phone.NUMBER / Event.START_DATE map to DATA1;
    // StructuredName given/family map to DATA2/DATA3; Event.TYPE is DATA2.
    val data1 = c.getColumnIndex(ContactsContract.Data.DATA1)
    val data2 = c.getColumnIndex(ContactsContract.Data.DATA2)
    val data3 = c.getColumnIndex(ContactsContract.Data.DATA3)
    val nameIdx = c.getColumnIndex(ContactsContract.Data.DISPLAY_NAME)
    if (mimeIdx < 0) return null

    var given = ""
    var family = ""
    var email = ""
    var phone = ""
    var birthday = ""
    var display = ""

    while (c.moveToNext()) {
        if (display.isBlank() && nameIdx >= 0) display = c.getString(nameIdx).orEmpty()
        when (c.getString(mimeIdx)) {
            StructuredName.CONTENT_ITEM_TYPE -> {
                given = c.getString(data2).orEmpty()
                family = c.getString(data3).orEmpty()
            }
            Email.CONTENT_ITEM_TYPE -> if (email.isBlank()) email = c.getString(data1).orEmpty()
            Phone.CONTENT_ITEM_TYPE -> if (phone.isBlank()) phone = c.getString(data1).orEmpty()
            Event.CONTENT_ITEM_TYPE -> {
                if (birthday.isBlank() && data2 >= 0 && c.getInt(data2) == Event.TYPE_BIRTHDAY) {
                    birthday = isoBirthday(c.getString(data1))
                }
            }
        }
    }

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
