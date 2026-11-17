package com.vayunmathur.findfamily.platform
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.ContactsContract
import android.provider.ContactsPickerSessionContract
import android.provider.ContactsPickerSessionContract.EXTRA_PICK_CONTACTS_REQUESTED_DATA_FIELDS
import android.provider.ContactsPickerSessionContract.EXTRA_PICK_CONTACTS_SELECTION_LIMIT
import com.vayunmathur.library.log.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.database.getBlobOrNull
import androidx.core.database.getStringOrNull
import com.vayunmathur.findfamily.R
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.ui.rememberPermissionRequest
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64

/** API level of the system contact-picker session (Android 16 QPR / API 37). */
private const val CONTACT_PICKER_SESSION_SDK = 37

/** A contact row picked from either picker, reduced to what the callback needs. */
private data class PickedContact(
    val name: String,
    val photo: String? = null,
    val lookupKey: String = "",
    val photos: List<String> = emptyList(),
)

/** Append [photo] to [photos] when the row carried one. */
private fun withPhoto(photos: List<String>, photo: String?): List<String> =
    if (photo != null) photos + photo else photos

/** Android platform helpers for contact picking, clipboard, and last known device location. */
class Platform(private val context: Context) {
    @SuppressLint("Range")
    @Composable
    fun requestPickContact(callback: (String, String?) -> Unit): () -> Unit {
        return if (Build.VERSION.SDK_INT >= CONTACT_PICKER_SESSION_SDK) {
            sessionPickerLauncher(callback)
        } else {
            legacyPickerLauncher(callback)
        }
    }

    /**
     * Launcher for the Contact Picker session intent (API 37+). The result URI
     * is a session that must be queried directly — no custom selection args.
     */
    @RequiresApi(CONTACT_PICKER_SESSION_SDK)
    @Composable
    private fun sessionPickerLauncher(callback: (String, String?) -> Unit): () -> Unit {
        val coroutine = rememberCoroutineScope()
        val pickContact =
            rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                if (it.resultCode == Activity.RESULT_OK) {
                    val resultUri = it.data?.data ?: return@rememberLauncherForActivityResult
                    // Process the result URI in a background thread to fetch the single selected contact
                    coroutine.launch { readSessionContact(resultUri)?.let { c -> callback(c.name, c.photo) } }
                }
            }
        return {
            // Define the specific contact data fields you need
            val requestedFields = arrayListOf(
                ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE,
            )
            // Set up the intent for the Contact Picker, limited to a single contact
            val pickContactIntent = Intent(ContactsPickerSessionContract.ACTION_PICK_CONTACTS).apply {
                putStringArrayListExtra(
                    EXTRA_PICK_CONTACTS_REQUESTED_DATA_FIELDS,
                    requestedFields
                )
                putExtra(EXTRA_PICK_CONTACTS_SELECTION_LIMIT, 1)
            }
            // Launch the picker
            pickContact.launch(pickContactIntent)
        }
    }

    /** Pre-session-API fallback: the platform PickContact contract behind a permission gate. */
    @Composable
    private fun legacyPickerLauncher(callback: (String, String?) -> Unit): () -> Unit {
        val launcher =
            rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
                if (uri == null) return@rememberLauncherForActivityResult
                readLegacyContact(uri)?.let { (name, photo) -> callback(name, photo) }
            }
        val requestContactsPermission =
            rememberPermissionRequest(Manifest.permission.READ_CONTACTS) { granted ->
                if (granted) launcher.launch()
            }
        return { requestContactsPermission() }
    }

    /**
     * Reads the single selected contact out of a picker-session result URI.
     * We use `LOOKUP_KEY` as a unique ID to aggregate all rows of the same person.
     */
    private fun readSessionContact(resultUri: android.net.Uri): PickedContact? {
        val projection = arrayOf(
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Data.MIMETYPE, // Type of data (e.g., email or phone)
            ContactsContract.Contacts.Photo.PHOTO, // The actual data (Phone number / Email string)
        )
        val contactsMap = mutableMapOf<String, PickedContact>()
        // Note: The Contact Picker Session Uri doesn't support custom selection & selectionArgs.
        // We query the URI directly to get the results chosen by the user.
        context.contentResolver.query(resultUri, projection, null, null, null)?.use { cursor ->
            // Get the column indices for our requested projection
            val lookupKeyIdx = cursor.getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY)
            val mimeTypeIdx = cursor.getColumnIndex(ContactsContract.Data.MIMETYPE)
            val nameIdx = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            val data1Idx = cursor.getColumnIndex(ContactsContract.Contacts.Photo.PHOTO)
            while (cursor.moveToNext()) {
                val lookupKey = cursor.getString(lookupKeyIdx)
                val mimeType = cursor.getString(mimeTypeIdx)
                val name = cursor.getString(nameIdx) ?: ""
                val data1 = cursor.getBlobOrNull(data1Idx)
                val photo = if (
                    mimeType == ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE &&
                    data1 != null
                ) {
                    val base64String = Base64.UrlSafe.encode(data1)
                    "data:image/jpeg;base64,$base64String"
                } else {
                    null
                }
                val existing = contactsMap[lookupKey]
                contactsMap[lookupKey] = if (existing != null) {
                    existing.copy(photos = withPhoto(existing.photos, photo))
                } else {
                    PickedContact(
                        lookupKey = lookupKey,
                        name = name,
                        photos = withPhoto(emptyList(), photo),
                    )
                }
            }
        }
        // Invoke callback for each contact found
        return contactsMap.values.firstOrNull()?.let { contact ->
            PickedContact(contact.name, contact.photos.firstOrNull())
        }
    }

    /**
     * Reads name + photo URI from a legacy PickContact result. A misbehaving
     * contacts provider throws across the IPC boundary, so the broad catch is
     * deliberate: the picker must fail into silence, not crash the activity.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun readLegacyContact(uri: android.net.Uri): Pair<String, String?>? {
        return try {
            val cur = context.contentResolver.query(uri, null, null, null)!!
            if (cur.moveToFirst()) {
                val name =
                    cur.getString(cur.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME))
                val photo =
                    cur.getStringOrNull(cur.getColumnIndexOrThrow(ContactsContract.Contacts.PHOTO_URI))
                cur.close()
                name?.let { it to photo }
            } else {
                cur.close()
                null
            }
        } catch (e: Exception) {
            Log.error("Platform", "Error querying contact from picker URI: $uri", e)
            null
        }
    }

    fun copy(content: String) {
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clipData = android.content.ClipData.newPlainText(context.getString(R.string.clipboard_label), content)
        clipboardManager.setPrimaryClip(clipData)
    }

    /**
     * Best cached device fix, or null if none is available yet. Reads the synchronous
     * [LocationManager.getLastKnownLocation] cache — no fix wait, no DB round-trip — so the
     * map can open centred on the user immediately. GPS is preferred when both providers
     * have a fix; otherwise the newest fix wins.
     */
    @SuppressLint("MissingPermission")
    fun lastKnownDeviceLocation(): GeoPoint? {
        val hasFine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return null
        return try {
            val locationManager =
                context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val gps = runCatching {
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            }.getOrNull()
            val network = runCatching {
                locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            }.getOrNull()
            // GPS first on ties: same timestamp means same fix batch, and GPS is the precise one.
            val best = listOfNotNull(gps, network).maxWithOrNull(
                compareBy({ it.time }, { if (it.provider == LocationManager.GPS_PROVIDER) 1 else 0 })
            )
            best?.let { GeoPoint(it.longitude, it.latitude) }
        } catch (_: SecurityException) {
            null
        }
    }
}
