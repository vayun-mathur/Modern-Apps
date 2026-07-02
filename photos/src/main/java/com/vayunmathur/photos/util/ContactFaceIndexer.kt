package com.vayunmathur.photos.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import androidx.core.net.toUri
import com.vayunmathur.photos.data.ContactFace

/**
 * Reads the user's contacts (requires the READ_CONTACTS permission, only ever
 * requested when the feature is enabled) and builds a face template for each
 * contact that has a photo. Everything stays on-device.
 */
class ContactFaceIndexer(private val context: Context) {

    /**
     * Build a [ContactFace] for each contact that has a photo and is not already
     * in [existingKeys]. Returns the new faces; the caller persists them.
     */
    fun index(existingKeys: Set<String>): List<ContactFace> {
        val faces = mutableListOf<ContactFace>()
        val projection = arrayOf(
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.PHOTO_URI,
        )
        try {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                projection,
                "${ContactsContract.Contacts.PHOTO_URI} IS NOT NULL",
                null,
                null,
            )?.use { cursor ->
                val keyCol = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.LOOKUP_KEY)
                val nameCol = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                val photoCol = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.PHOTO_URI)
                while (cursor.moveToNext()) {
                    val key = cursor.getString(keyCol) ?: continue
                    if (key in existingKeys) continue
                    val name = cursor.getString(nameCol) ?: continue
                    val photoUri = cursor.getString(photoCol) ?: continue

                    val bitmap = loadBitmap(photoUri.toUri()) ?: continue
                    val templates = FaceRecognizer.detectFaces(bitmap)
                    bitmap.recycle()
                    val template = templates.firstOrNull() ?: continue

                    faces += ContactFace(
                        contactKey = key,
                        name = name,
                        embedding = FaceRecognizer.floatsToBytes(template),
                        photoUri = photoUri,
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to index contact faces", e)
        }
        return faces
    }

    private fun loadBitmap(uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load contact photo $uri", e)
            null
        }
    }

    companion object {
        private const val TAG = "ContactFaceIndexer"
    }
}
