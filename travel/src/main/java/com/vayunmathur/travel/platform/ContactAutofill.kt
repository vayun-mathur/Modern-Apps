package com.vayunmathur.travel.platform

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

/** Length of an ISO `YYYY-MM-DD` birthday. */
private const val ISO_DATE_LENGTH = 10

/** Index of the year/month/day separators in an ISO date. */
private const val ISO_FIRST_DASH = 4
private const val ISO_SECOND_DASH = 7

/** Column indices for the contact Data row being parsed. */
private data class ContactColumns(
    val mime: Int,
    val data1: Int,
    val data2: Int,
    val data3: Int,
    val displayName: Int,
)

/** Mutable accumulation of contact fields across cursor rows. */
private class ContactAccumulator {
    var given: String = ""
    var family: String = ""
    var email: String = ""
    var phone: String = ""
    var birthday: String = ""
    var display: String = ""

    fun toContactInfo(): ContactInfo {
        applyDisplayNameFallback()
        return ContactInfo(
            givenName = given,
            familyName = family,
            email = email,
            phone = normalizePhone(phone),
            bornOn = birthday,
        )
    }

    /** Fall back to splitting the display name if there's no structured name. */
    private fun applyDisplayNameFallback() {
        if (given.isNotBlank() || family.isNotBlank() || display.isBlank()) return
        val parts = display.trim().split(" ").filter { it.isNotBlank() }
        given = parts.firstOrNull().orEmpty()
        family = if (parts.size > 1) parts.drop(1).joinToString(" ") else ""
    }
}

private fun parseContactCursor(c: Cursor): ContactInfo? {
    val cols = ContactColumns(
        mime = c.getColumnIndex(ContactsContract.Data.MIMETYPE),
        // Email.ADDRESS / Phone.NUMBER / Event.START_DATE map to DATA1;
        // StructuredName given/family map to DATA2/DATA3; Event.TYPE is DATA2.
        data1 = c.getColumnIndex(ContactsContract.Data.DATA1),
        data2 = c.getColumnIndex(ContactsContract.Data.DATA2),
        data3 = c.getColumnIndex(ContactsContract.Data.DATA3),
        displayName = c.getColumnIndex(ContactsContract.Data.DISPLAY_NAME),
    )
    // A provider that answers the session URI without every projected
    // column reports -1 for the missing ones; getString(-1) throws
    // CursorIndexOutOfBounds. Only the MIME column is load-bearing.
    if (cols.mime < 0) return null
    val acc = ContactAccumulator()
    while (c.moveToNext()) {
        acc.readDisplayName(c, cols)
        acc.readRow(c, cols)
    }
    return acc.toContactInfo()
}

private fun ContactAccumulator.readDisplayName(c: Cursor, cols: ContactColumns) {
    if (display.isBlank() && cols.displayName >= 0) {
        display = c.getString(cols.displayName).orEmpty()
    }
}

private fun ContactAccumulator.readRow(c: Cursor, cols: ContactColumns) {
    when (c.getString(cols.mime)) {
        StructuredName.CONTENT_ITEM_TYPE -> readStructuredName(c, cols)
        Email.CONTENT_ITEM_TYPE -> if (email.isBlank() && cols.data1 >= 0) email = c.getString(cols.data1).orEmpty()
        Phone.CONTENT_ITEM_TYPE -> if (phone.isBlank() && cols.data1 >= 0) phone = c.getString(cols.data1).orEmpty()
        Event.CONTENT_ITEM_TYPE -> readBirthday(c, cols)
    }
}

private fun ContactAccumulator.readStructuredName(c: Cursor, cols: ContactColumns) {
    if (cols.data2 >= 0) given = c.getString(cols.data2).orEmpty()
    if (cols.data3 >= 0) family = c.getString(cols.data3).orEmpty()
}

private fun ContactAccumulator.readBirthday(c: Cursor, cols: ContactColumns) {
    if (birthday.isNotBlank() || cols.data1 < 0 || !isBirthdayRow(c, cols)) return
    birthday = isoBirthday(c.getString(cols.data1))
}

private fun isBirthdayRow(c: Cursor, cols: ContactColumns): Boolean =
    cols.data2 >= 0 && c.getInt(cols.data2) == Event.TYPE_BIRTHDAY

/** Strip spaces/dashes/parens so the number is closer to the E.164 form wanted. */
private fun normalizePhone(raw: String): String =
    raw.filter { it.isDigit() || it == '+' }

/**
 * Normalize a contact birthday to ISO `YYYY-MM-DD`. Contacts store birthdays as
 * `yyyy-MM-dd` or, when the year is unknown, `--MM-dd`. We only accept a full
 * date (a year is required for the booking `born_on`), returning blank otherwise.
 */
private fun isoBirthday(raw: String?): String {
    val s = raw?.trim().orEmpty()
    if (s.length != ISO_DATE_LENGTH) return ""
    if (s[ISO_FIRST_DASH] != '-' || s[ISO_SECOND_DASH] != '-') return ""
    if (!s.take(ISO_FIRST_DASH).all { it.isDigit() }) return ""
    return s
}
