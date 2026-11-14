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
        return cursorFor(projection, queryRows(uri, projection, selection))
    }

    private fun queryRows(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
    ): List<Map<String, Any?>> {
        val path = uri.path.orEmpty()
        return when {
            isOwnerNameQuery(path, projection) -> ownerRows()
            path == "/raw_contacts" -> rawIdRows()
            path == "/data" && selection != null -> addressRows()
            path.startsWith("/data/") && uri.pathSegments.size == 2 -> photoRows()
            else -> phoneRows(uri, projection)
        }
    }

    private fun isOwnerNameQuery(path: String, projection: Array<out String>?): Boolean =
        path.startsWith("/raw_contacts/") &&
            projection?.contains(ContactsContract.RawContacts.DISPLAY_NAME_PRIMARY) == true

    private fun ownerRows(): List<Map<String, Any?>> =
        if (ownerName.isEmpty()) {
            emptyList()
        } else {
            listOf(mapOf(ContactsContract.RawContacts.DISPLAY_NAME_PRIMARY to ownerName))
        }

    private fun rawIdRows(): List<Map<String, Any?>> =
        rawIdForContact?.let { listOf(mapOf(ContactsContract.RawContacts._ID to it)) } ?: emptyList()

    private fun addressRows(): List<Map<String, Any?>> {
        val cols = listOf(
            ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS,
            ContactsContract.CommonDataKinds.StructuredPostal.STREET,
            ContactsContract.CommonDataKinds.StructuredPostal.CITY,
            ContactsContract.CommonDataKinds.StructuredPostal.REGION,
            ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE,
            ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY,
        )
        return addresses.map { row -> cols.zip(row.toList()).toMap() }
    }

    private fun photoRows(): List<Map<String, Any?>> {
        val bytes = photoBytes
        return if (bytes == null) {
            emptyList()
        } else {
            listOf(mapOf(ContactsContract.Contacts.Photo.PHOTO to bytes))
        }
    }

    private fun phoneRows(uri: Uri, projection: Array<out String>?): List<Map<String, Any?>> {
        val row = phones[uri.toString()]
        if (projection == null) {
            return if (row == null) emptyList() else listOf(mapOf(ContactsContract.Contacts._ID to 1L))
        }
        return if (row == null) {
            emptyList()
        } else {
            listOf(
                mapOf<String, Any?>(
                    ContactsContract.Contacts.DISPLAY_NAME to row.name,
                    ContactsContract.CommonDataKinds.Phone.NUMBER to row.number,
                    ContactsContract.CommonDataKinds.Phone.TYPE to row.type,
                    ContactsContract.CommonDataKinds.Phone.LABEL to row.label,
                    ContactsContract.Contacts.Photo.PHOTO_ID to row.photoId,
                ),
            )
        }
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/phone_v2"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
    ): Int = 0
}
