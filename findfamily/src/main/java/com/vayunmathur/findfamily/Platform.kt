package com.vayunmathur.findfamily

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.content.PermissionChecker
import androidx.core.database.getBlobOrNull
import androidx.core.database.getStringOrNull
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64

class Platform(private val context: Context) {
    @SuppressLint("Range")
    @Composable
    fun requestPickContact(callback: (String, String?)->Unit): ()->Unit {
        val coroutine = rememberCoroutineScope()
        if(Build.VERSION.SDK_INT >= 37) {
//            // Launcher for the Contact Picker intent
//            val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
//                if (it.resultCode == Activity.RESULT_OK) {
//                    val resultUri = it.data?.data ?: return@rememberLauncherForActivityResult
//
//                    data class Contact(
//                        val lookupKey: String,
//                        val name: String,
//                        val photos: List<String>
//                    )
//                    // Process the result URI in a background thread to fetch all selected contacts
//                    coroutine.launch {
//                        val projection = arrayOf(
//                            ContactsContract.Contacts.LOOKUP_KEY,
//                            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
//                            ContactsContract.Data.MIMETYPE, // Type of data (e.g., email or phone)
//                            ContactsContract.Contacts.Photo.PHOTO, // The actual data (Phone number / Email string)
//                        )
//
//                        // We use `LOOKUP_KEY` as a unique ID to aggregate all contact info related to a same person
//                        val contactsMap = mutableMapOf<String, Contact>()
//
//                        // Note: The Contact Picker Session Uri doesn't support custom selection & selectionArgs.
//                        // We query the URI directly to get the results chosen by the user.
//                        context.contentResolver.query(resultUri, projection, null, null, null)?.use { cursor ->
//                            // Get the column indices for our requested projection
//                            val lookupKeyIdx = cursor.getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY)
//                            val mimeTypeIdx = cursor.getColumnIndex(ContactsContract.Data.MIMETYPE)
//                            val nameIdx = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
//                            val data1Idx = cursor.getColumnIndex(ContactsContract.Contacts.Photo.PHOTO)
//
//                            while (cursor.moveToNext()) {
//                                val lookupKey = cursor.getString(lookupKeyIdx)
//                                val mimeType = cursor.getString(mimeTypeIdx)
//                                val name = cursor.getString(nameIdx) ?: ""
//                                val data1 = cursor.getBlobOrNull(data1Idx)
//
//                                val photo = if (mimeType == ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE && data1 != null) {
//                                    val base64String = Base64.UrlSafe.encode(data1)
//                                    "data:image/jpeg;base64,$base64String"
//                                } else null
//
//                                val existingContact = contactsMap[lookupKey]
//                                if (existingContact != null) {
//                                    contactsMap[lookupKey] = existingContact.copy(
//                                        photos = if (photo != null) existingContact.photos + photo else existingContact.photos
//                                    )
//                                } else {
//                                    contactsMap[lookupKey] = Contact(
//                                        lookupKey = lookupKey,
//                                        name = name,
//                                        photos = if (photo != null) listOf(photo) else emptyList(),
//                                    )
//                                }
//                            }
//                        }
//
//                        // Invoke callback for each contact found
//                        contactsMap.values.firstOrNull()?.let { contact ->
//                            val photoUri = contact.photos.firstOrNull()
//                            callback(contact.name, photoUri)
//                        }
//                    }
//                }
//            }
//
//            return {
//                // Define the specific contact data fields you need
//                val requestedFields = arrayListOf(
//                    ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE,
//                )
//
//                // Set up the intent for the Contact Picker
//                val pickContactIntent = Intent(ACTION_PICK_CONTACTS).apply {
//                    putStringArrayListExtra(
//                        EXTRA_PICK_CONTACTS_REQUESTED_DATA_FIELDS,
//                        requestedFields
//                    )
//                }
//
//                // Launch the picker
//                pickContact.launch(pickContactIntent)
//            }
            return{}
        } else {
            val launcher =
                rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    val cur = context.contentResolver.query(uri, null, null, null)!!
                    if (cur.moveToFirst()) {
                        val name =
                            cur.getString(cur.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME))
                        val photo =
                            cur.getStringOrNull(cur.getColumnIndex(ContactsContract.Contacts.PHOTO_URI))
                        callback(name, photo)
                    }
                    cur.close()
                }
            val permissionLauncher =
                rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    if (it) {
                        launcher.launch()
                    }
                }
            return {
                if (PermissionChecker.checkSelfPermission(
                        context,
                        Manifest.permission.READ_CONTACTS
                    ) == PermissionChecker.PERMISSION_GRANTED
                ) {
                    launcher.launch()
                } else {
                    permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                }
            }
        }
    }

    fun copy(content: String) {
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clipData = android.content.ClipData.newPlainText("text", content)
        clipboardManager.setPrimaryClip(clipData)
    }
}