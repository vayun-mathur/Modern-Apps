package com.vayunmathur.emergency.platform

import android.content.ContentResolver
import android.content.ContentUris
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import com.vayunmathur.library.log.Log
import com.vayunmathur.emergency.data.EmergencyContact
import java.io.ByteArrayInputStream

private const val TAG = "EmergencyContactReader"

/**
 * Reads emergency contacts from the contacts provider.
 *
 * Mirrors GrapheneOS's `EmergencyContactManager`: name, number, type label and photo are read
 * from the stored `CommonDataKinds.Phone.CONTENT_URI`; `isValid` re-checks that the URI still
 * resolves so contacts deleted from the address book can be pruned. Needs `READ_CONTACTS`
 * (a normal runtime grant); without it every read returns null and the UI must prompt.
 */
class ContactReader(
    private val resolver: ContentResolver,
    private val resources: Resources,
) {

    /** Reads one contact, or null when the URI no longer resolves or access is denied. */
    fun read(phoneUri: Uri): EmergencyContact? {
        val cursor = runCatching {
            resolver.query(
                phoneUri,
                arrayOf(
                    ContactsContract.Contacts.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.LABEL,
                    ContactsContract.Contacts.Photo.PHOTO_ID,
                ),
                null, null, null,
            )
        }.getOrElse {
            Log.status(TAG, "unable to read contact $phoneUri", it)
            return null
        } ?: return null
        cursor.use {
            if (!it.moveToNext()) return null
            val name = it.getString(0).orEmpty()
            val number = it.getString(1).orEmpty()
            if (number.isBlank()) return null
            val type = ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                resources, it.getInt(2), it.getString(3),
            ).toString()
            val photo = readPhoto(it.getLong(4))
            return EmergencyContact(phoneUri, name, number, type, photo)
        }
    }

    /** Reads all URIs, silently dropping ones that no longer resolve. */
    fun readAll(uris: List<Uri>): List<EmergencyContact> = uris.mapNotNull { read(it) }

    /** True when [phoneUri] is non-null and still resolves to a number. */
    fun isValid(phoneUri: Uri?): Boolean {
        if (phoneUri == null) return false
        val cursor = runCatching {
            resolver.query(phoneUri, null, null, null, null)
        }.getOrElse {
            Log.status(TAG, "unable to validate contact $phoneUri", it)
            return false
        } ?: return false
        cursor.use { return it.moveToFirst() }
    }

    private fun readPhoto(photoId: Long): Bitmap? {
        if (photoId <= 0) return null
        val photoUri = ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, photoId)
        val cursor = runCatching {
            resolver.query(
                photoUri, arrayOf(ContactsContract.Contacts.Photo.PHOTO), null, null, null,
            )
        }.getOrNull() ?: return null
        cursor.use {
            if (!it.moveToNext()) return null
            val data = it.getBlob(0) ?: return null
            return runCatching { BitmapFactory.decodeStream(ByteArrayInputStream(data)) }.getOrNull()
        }
    }
}
