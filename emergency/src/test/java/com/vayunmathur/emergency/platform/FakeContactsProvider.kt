package com.vayunmathur.emergency.platform

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.provider.ContactsContract
import java.io.ByteArrayOutputStream

/**
 * Serves contacts-provider rows from in-memory fixtures so [ContactReader],
 * [OwnerIdentityReader] and the repository's prune-on-load can be exercised
 * without a device address book.
 */
class FakeContactsProvider : ContentProvider() {

    data class PhoneRow(
        val name: String,
        val number: String,
        val type: Int = ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
        val label: String? = null,
        val photoId: Long = 0,
    )

    companion object {
        /** uri string -> phone row; absent means the contact was deleted. */
        val phones = mutableMapOf<String, PhoneRow>()
        var ownerName: String = "Jamie Owner"
        var rawIdForContact: Long? = 7L
        /** Rows of (formatted, street, city, region, postcode, country). */
        var addresses = listOf<Array<Any?>>()
        var photoBytes: ByteArray? = makePhotoBytes()
        /** URIs whose query throws SecurityException (permission revoked mid-read). */
        val failUris = mutableSetOf<String>()
        /** URIs whose query returns a null cursor. */
        val nullUris = mutableSetOf<String>()

        fun reset() {
            phones.clear()
            failUris.clear()
            nullUris.clear()
            ownerName = "Jamie Owner"
            rawIdForContact = 7L
            addresses = listOf(
                arrayOf("1 Main St, Springfield", null, null, null, null, null),
                arrayOf(null, "2 Oak Ave", "Shelbyville", "NT", "12345", "US"),
                arrayOf("1 Main St, Springfield", null, null, null, null, null),
                arrayOf(null, " ", "", null, null, null),
            )
            photoBytes = makePhotoBytes()
        }

        private fun makePhotoBytes(): ByteArray {
            val bmp = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            return out.toByteArray()
        }
    }

    override fun onCreate(): Boolean = true

    private fun cursorFor(projection: Array<out String>?, rows: List<Map<String, Any?>>): Cursor {
        val columns = projection?.map { it }?.toTypedArray()
            ?: rows.firstOrNull()?.keys?.toTypedArray()
            ?: arrayOf(ContactsContract.Contacts._ID)
        val cursor = MatrixCursor(columns)
        for (row in rows) {
            cursor.addRow(columns.map { row[it] })
        }
        return cursor
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        if (uri.toString() in failUris) {
            throw SecurityException("READ_CONTACTS revoked for $uri")
        }
        if (uri.toString() in nullUris) return null
        val path = uri.path.orEmpty()
        // Owner name: RawContacts.CONTENT_URI/<id> with DISPLAY_NAME_PRIMARY
        if (path.startsWith("/raw_contacts/") && projection?.contains(
                ContactsContract.RawContacts.DISPLAY_NAME_PRIMARY,
            ) == true
        ) {
            val rows = if (ownerName.isEmpty()) emptyList()
            else listOf(mapOf(ContactsContract.RawContacts.DISPLAY_NAME_PRIMARY to ownerName))
            return cursorFor(projection, rows)
        }
        // Aggregate -> raw id resolution: query on RawContacts.CONTENT_URI with CONTACT_ID
        if (path == "/raw_contacts") {
            val rows = rawIdForContact?.let {
                listOf(mapOf(ContactsContract.RawContacts._ID to it))
            } ?: emptyList()
            return cursorFor(projection, rows)
        }
        // Address rows: query on Data.CONTENT_URI with a selection
        if (path == "/data" && selection != null) {
            val cols = listOf(
                ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS,
                ContactsContract.CommonDataKinds.StructuredPostal.STREET,
                ContactsContract.CommonDataKinds.StructuredPostal.CITY,
                ContactsContract.CommonDataKinds.StructuredPostal.REGION,
                ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE,
                ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY,
            )
            val rows = addresses.map { row -> cols.zip(row.toList()).toMap() }
            return cursorFor(projection, rows)
        }
        // Photo blob: Data.CONTENT_URI/<photoId> (exactly two segments;
        // phone URIs are /data/phones/<id> and must fall through to below)
        if (path.startsWith("/data/") && uri.pathSegments.size == 2) {
            val bytes = photoBytes
            val rows = if (bytes == null) emptyList()
            else listOf(mapOf(ContactsContract.Contacts.Photo.PHOTO to bytes))
            return cursorFor(projection, rows)
        }
        // Phone rows (including validation queries with a null projection)
        val row = phones[uri.toString()]
        if (projection == null) {
            val rows = if (row == null) emptyList()
            else listOf(mapOf(ContactsContract.Contacts._ID to 1L))
            return cursorFor(null, rows)
        }
        val rows = if (row == null) emptyList() else listOf(
            mapOf<String, Any?>(
                ContactsContract.Contacts.DISPLAY_NAME to row.name,
                ContactsContract.CommonDataKinds.Phone.NUMBER to row.number,
                ContactsContract.CommonDataKinds.Phone.TYPE to row.type,
                ContactsContract.CommonDataKinds.Phone.LABEL to row.label,
                ContactsContract.Contacts.Photo.PHOTO_ID to row.photoId,
            ),
        )
        return cursorFor(projection, rows)
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/phone_v2"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
    ): Int = 0
}
