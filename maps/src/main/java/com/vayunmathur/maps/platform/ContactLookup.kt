package com.vayunmathur.maps.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsPickerSessionContract
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.RequiresApi

/**
 * Reading postal addresses out of the contacts provider, and launching the pickers that
 * produce the URIs to read.
 *
 * Split out of `ui/ContactAddressButton.kt`, which was one small composable on top of this:
 * cursor projections, three provider query shapes, and the picker-intent fallback chain. None
 * of it is UI, none of it needs a composition, and all of it is the part with edge cases worth
 * reading on its own — Android 17's session URIs, the legacy postal-row picker, and OEM images
 * that ship neither.
 *
 * Everything here is main-thread-unsafe (it queries a ContentProvider); callers dispatch to IO.
 */

/**
 * Build the Android 17 system Contact Picker intent: request the postal-address
 * field only (so the user picks an ADDRESS), single-select. All extras below are
 * compile-time String constants and are therefore inlined, so referencing the
 * new [ContactsPickerSessionContract] symbols is safe even on older runtimes;
 * the call is additionally gated on [Build.VERSION_CODES.CINNAMON_BUN].
 */
@RequiresApi(Build.VERSION_CODES.CINNAMON_BUN)
fun newSystemContactPickerIntent(): Intent =
    Intent(ContactsPickerSessionContract.ACTION_PICK_CONTACTS).apply {
        // Also engage the system picker when targeting a lower SDK on Android 17.
        putExtra(Intent.EXTRA_USE_SYSTEM_CONTACTS_PICKER, true)
        // Ask only for postal-address rows.
        putStringArrayListExtra(
            ContactsPickerSessionContract.EXTRA_PICK_CONTACTS_REQUESTED_DATA_FIELDS,
            arrayListOf(StructuredPostal.CONTENT_ITEM_TYPE),
        )
        putExtra(ContactsPickerSessionContract.EXTRA_PICK_CONTACTS_SELECTION_LIMIT, 1)
    }

/**
 * Legacy postal-picker path: try the postal-row picker, then the whole-contact picker, then
 * give up via [onNoPicker]. Never throws.
 *
 * [onNoPicker] rather than a `Toast` from in here: telling the user is the UI layer's job, and
 * a toast ignores the app theme, cannot carry an action, and may be dropped outright while
 * backgrounded.
 */
fun launchPostalPicker(
    postalPicker: ActivityResultLauncher<Intent>,
    contactPicker: ActivityResultLauncher<Void?>,
    onNoPicker: () -> Unit,
) {
    val postalIntent = Intent(Intent.ACTION_PICK).apply {
        type = StructuredPostal.CONTENT_TYPE
    }
    try {
        postalPicker.launch(postalIntent)
    } catch (_: ActivityNotFoundException) {
        // No postal picker on this device — try the universal contact picker.
        launchContactPicker(contactPicker, onNoPicker)
    }
}

/** Launch the whole-contact picker, reporting through [onNoPicker] if even that has no
 *  handler (some stripped OEM images). Never throws. */
fun launchContactPicker(
    contactPicker: ActivityResultLauncher<Void?>,
    onNoPicker: () -> Unit,
) {
    try {
        contactPicker.launch(null)
    } catch (_: ActivityNotFoundException) {
        onNoPicker()
    }
}

/**
 * Read every postal address from the Android 17 picker's [sessionUri]. The session
 * URI is a pre-scoped view of exactly what the user chose, so it does NOT support
 * selection / selectionArgs (passing them throws) — query with nulls. We iterate
 * the returned rows and collect each StructuredPostal address (de-duplicated,
 * order preserved). With SELECTION_LIMIT=1 this is normally one address; if an OEM
 * returns the whole chosen contact's several addresses, the caller shows a chooser.
 */
fun readSessionAddresses(context: Context, sessionUri: Uri): List<String> {
    val out = LinkedHashSet<String>()
    context.contentResolver.query(sessionUri, SESSION_PROJECTION, null, null, null)?.use { cursor ->
        val mimeIdx = cursor.getColumnIndex(ContactsContract.Data.MIMETYPE)
        while (cursor.moveToNext()) {
            val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx) else null
            if (mime == null || mime == StructuredPostal.CONTENT_ITEM_TYPE) {
                cursor.postalAddress()?.let { out.add(it) }
            }
        }
    }
    return out.toList()
}

/**
 * Read the postal address at [rowUri] (a StructuredPostal data row). Prefers the
 * pre-formatted [StructuredPostal.FORMATTED_ADDRESS]; if that's blank it
 * composes the address from the individual components. Returns null when the
 * row is gone or holds no address. May throw [SecurityException] when the
 * caller lacks read access to the row.
 */
fun readAddress(context: Context, rowUri: Uri): String? {
    context.contentResolver.query(rowUri, POSTAL_PROJECTION, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return null
        return cursor.postalAddress()
    }
    return null
}

/**
 * Resolve all postal addresses of the whole contact at [contactUri] (as returned
 * by `ActivityResultContracts.PickContact`). Looks up the contact id, then reads
 * its StructuredPostal data rows (de-duplicated, order preserved). Returns empty
 * when the contact has no postal address. Requires READ_CONTACTS (may throw
 * [SecurityException]).
 */
fun readContactAddresses(context: Context, contactUri: Uri): List<String> {
    val contactId = context.contentResolver.query(
        contactUri,
        arrayOf(ContactsContract.Contacts._ID),
        null,
        null,
        null,
    )?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return emptyList()

    val selection =
        "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?"
    val args = arrayOf(
        contactId,
        StructuredPostal.CONTENT_ITEM_TYPE,
    )
    val out = LinkedHashSet<String>()
    context.contentResolver.query(
        ContactsContract.Data.CONTENT_URI,
        POSTAL_PROJECTION,
        selection,
        args,
        null,
    )?.use { cursor ->
        while (cursor.moveToNext()) {
            cursor.postalAddress()?.let { out.add(it) }
        }
    }
    return out.toList()
}

/** Pull a display address out of a StructuredPostal cursor row: the formatted
 *  address if present, else the composed components. Null when the row is empty. */
private fun Cursor.postalAddress(): String? {
    fun col(name: String): String? = getColumnIndex(name)
        .takeIf { it >= 0 }
        ?.let { getString(it) }
        ?.takeIf { it.isNotBlank() }

    col(StructuredPostal.FORMATTED_ADDRESS)
        ?.normalizeAddress()
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }

    return listOfNotNull(
        col(StructuredPostal.STREET),
        col(StructuredPostal.CITY),
        col(StructuredPostal.REGION),
        col(StructuredPostal.POSTCODE),
        col(StructuredPostal.COUNTRY),
    ).joinToString(", ").ifBlank { null }
}

/** Collapse a multi-line postal address to one comma-separated line so it makes
 *  a valid single-line search query (FORMATTED_ADDRESS embeds newlines). Runs of
 *  whitespace within a line collapse to a single space; blank lines are dropped. */
internal fun String.normalizeAddress(): String =
    lines()
        .map { it.replace(Regex("\\s+"), " ").trim() }
        .filter { it.isNotEmpty() }
        .joinToString(", ")

/** Projection for the Android 17 session URI: MIMETYPE (to spot the postal row)
 *  plus the postal columns. */
private val SESSION_PROJECTION = arrayOf(
    ContactsContract.Data.MIMETYPE,
    StructuredPostal.FORMATTED_ADDRESS,
    StructuredPostal.STREET,
    StructuredPostal.CITY,
    StructuredPostal.REGION,
    StructuredPostal.POSTCODE,
    StructuredPostal.COUNTRY,
)

private val POSTAL_PROJECTION = arrayOf(
    StructuredPostal.FORMATTED_ADDRESS,
    StructuredPostal.STREET,
    StructuredPostal.CITY,
    StructuredPostal.REGION,
    StructuredPostal.POSTCODE,
    StructuredPostal.COUNTRY,
)
