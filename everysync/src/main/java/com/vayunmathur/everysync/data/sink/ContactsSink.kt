package com.vayunmathur.everysync.data.sink

import android.content.ContentProviderOperation
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import com.vayunmathur.library.log.Log
import androidx.core.database.getStringOrNull
import com.vayunmathur.everysync.auth.AccountStore
import com.vayunmathur.everysync.data.RemoteContact
import com.vayunmathur.everysync.data.TypedValue

/** A locally-edited contact detected via the platform DIRTY/DELETED flags. */
data class LocalContactChange(
    val rawContactId: Long,
    val sourceId: String?,
    val etag: String?,
    val deleted: Boolean,
    val contact: RemoteContact?,
)

/**
 * Writes [RemoteContact]s into ContactsContract under the EverySync account.
 * Writes go through URIs carrying the `CALLER_IS_SYNCADAPTER` + account query
 * params (so our writes don't get flagged DIRTY). The relay UID is stored in
 * RawContacts.SOURCE_ID, the relay updatedAt stamp in SYNC1 and the vault id
 * in SYNC2.
 */
object ContactsSink {
    private const val TAG = "ContactsSink"

    /**
     * Health of the last [listLocal] read: false when the query failed or
     * returned a null cursor. Consulted by `ContactRelayStore.listLocalOk`.
     */
    @Volatile var lastListLocalOk: Boolean = true
        private set
    private val ACCOUNT_TYPE = AccountStore.ACCOUNT_TYPE
    private const val CURSOR_UID = 0
    private const val CURSOR_ETAG = 1
    private const val CURSOR_DATA2 = 2
    private const val CURSOR_DATA3 = 3
    private const val CURSOR_DATA4 = 4
    private const val CURSOR_DATA5 = 5
    private const val CURSOR_DATA6 = 6
    private const val CURSOR_RAW_ID = 0
    private const val CURSOR_SOURCE_ID = 1
    private const val CURSOR_SYNC1 = 2
    private const val CURSOR_DELETED = 3


    private fun Uri.asSyncAdapter(accountName: String): Uri =
        buildUpon()
            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_NAME, accountName)
            .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_TYPE, ACCOUNT_TYPE)
            .build()

    /** Map of remote UID -> ETag currently present locally for this account. */
    fun localUidToEtag(context: Context, accountName: String): Map<String, String?> {
        val result = mutableMapOf<String, String?>()
        try {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts.SOURCE_ID, ContactsContract.RawContacts.SYNC1),
                "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND " +
                    "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND " +
                    "${ContactsContract.RawContacts.DELETED} = 0",
                arrayOf(accountName, ACCOUNT_TYPE),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val uid = c.getStringOrNull(CURSOR_UID) ?: continue
                    result[uid] = c.getStringOrNull(CURSOR_ETAG)
                }
            }
        } catch (expected: Exception) {
            Log.error(TAG, "localUidToEtag failed", expected)
        }
        return result
    }

    /**
     * Every contact for this account in relay form, for the merge's local
     * side — including DELETED rows as tombstones. Filtering DELETED rows
     * out (the old behaviour) made a local delete indistinguishable from a
     * missed read, so the merge resurrected the relay copy and the delete
     * never propagated. `updatedAt` comes from SYNC1 (the relay stamp
     * mirrors into `etag` on every relay write); rows that predate relay
     * sync carry no stamp and merge as fresh local creates via the
     * adoption path.
     */
    fun listLocal(context: Context, accountName: String): List<RemoteContact> {
        val out = mutableListOf<RemoteContact>()
        var failed = false
        try {
            val cursor = context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(
                    ContactsContract.RawContacts._ID,
                    ContactsContract.RawContacts.SOURCE_ID,
                    ContactsContract.RawContacts.SYNC1,
                    ContactsContract.RawContacts.DELETED,
                ),
                "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND " +
                    "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ?",
                arrayOf(accountName, ACCOUNT_TYPE),
                null,
            )
            if (cursor == null) {
                // Null cursor = provider cannot serve: failure, not empty.
                failed = true
            } else {
                cursor.use { c ->
                    while (c.moveToNext()) {
                        val rawId = c.getLong(CURSOR_RAW_ID)
                        val uid = c.getStringOrNull(CURSOR_SOURCE_ID).orEmpty()
                        val stamp = c.getStringOrNull(CURSOR_SYNC1)?.toLongOrNull() ?: 0L
                        val contact = readContact(context, rawId, uid.ifBlank { null })
                        out += contact.copy(
                            uid = uid,
                            updatedAt = stamp,
                            deleted = c.getInt(CURSOR_DELETED) == 1,
                        )
                    }
                }
            }
        } catch (expected: Exception) {
            Log.error(TAG, "listLocal failed", expected)
            failed = true
        }
        lastListLocalOk = !failed
        return out
    }

    private fun rawContactId(context: Context, accountName: String, uid: String): Long? {
        return try {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND " +
                    "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND " +
                    "${ContactsContract.RawContacts.SOURCE_ID} = ?",
                arrayOf(accountName, ACCOUNT_TYPE, uid),
                null,
            )?.use { if (it.moveToFirst()) it.getLong(CURSOR_UID) else null }
        } catch (expected: Exception) {
            Log.error(TAG, "rawContactId failed", expected)
            null
        }
    }

    /** Idempotent upsert keyed on [RemoteContact.uid]. */
    fun upsert(context: Context, accountName: String, contact: RemoteContact) {
        val existing = rawContactId(context, accountName, contact.uid)
        val ops = ArrayList<ContentProviderOperation>()
        val rawUri = ContactsContract.RawContacts.CONTENT_URI.asSyncAdapter(accountName)
        val dataUri = ContactsContract.Data.CONTENT_URI.asSyncAdapter(accountName)

        if (existing != null) {
            // Replace: clear existing data rows, keep the raw contact + its id.
            ops += ContentProviderOperation.newUpdate(rawUri)
                .withSelection("${ContactsContract.RawContacts._ID} = ?", arrayOf(existing.toString()))
                .withValue(ContactsContract.RawContacts.SYNC1, contact.etag)
                .withValue(ContactsContract.RawContacts.SYNC2, contact.href)
                .withValue(ContactsContract.RawContacts.DIRTY, 0)
                .build()
            ops += ContentProviderOperation.newDelete(dataUri)
                .withSelection("${ContactsContract.Data.RAW_CONTACT_ID} = ?", arrayOf(existing.toString()))
                .build()
            dataOps(contact).forEach { builder ->
                ops += builder.withValue(ContactsContract.Data.RAW_CONTACT_ID, existing).build()
            }
        } else {
            ops += ContentProviderOperation.newInsert(rawUri)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, accountName)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, ACCOUNT_TYPE)
                .withValue(ContactsContract.RawContacts.SOURCE_ID, contact.uid)
                .withValue(ContactsContract.RawContacts.SYNC1, contact.etag)
                .withValue(ContactsContract.RawContacts.SYNC2, contact.href)
                .build()
            dataOps(contact).forEach { builder ->
                ops += builder.withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0).build()
            }
        }
        applyBatch(context, ops)
    }

    fun delete(context: Context, accountName: String, uid: String) {
        val id = rawContactId(context, accountName, uid) ?: return
        try {
            context.contentResolver.delete(
                ContactsContract.RawContacts.CONTENT_URI.asSyncAdapter(accountName),
                "${ContactsContract.RawContacts._ID} = ?",
                arrayOf(id.toString()),
            )
        } catch (expected: Exception) {
            Log.error(TAG, "delete failed", expected)
        }
    }

    /**
     * Removes every raw contact for this account from the on-device provider.
     * Used when the user turns contact sync off; the account itself stays, so a
     * later re-enable can repopulate it from a fresh pull. Deleting via the
     * caller-is-syncadapter URI hard-removes the rows rather than marking them
     * DELETED.
     */
    fun purge(context: Context, accountName: String) {
        try {
            context.contentResolver.delete(
                ContactsContract.RawContacts.CONTENT_URI.asSyncAdapter(accountName),
                "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE} = ?",
                arrayOf(accountName, ACCOUNT_TYPE),
            )
        } catch (expected: Exception) {
            Log.error(TAG, "purge failed", expected)
        }
    }

    /** Contacts the user edited/deleted locally (DIRTY/DELETED set by other apps). */
    fun getLocalChanges(context: Context, accountName: String): List<LocalContactChange> {
        val changes = mutableListOf<LocalContactChange>()
        try {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(
                    ContactsContract.RawContacts._ID,
                    ContactsContract.RawContacts.SOURCE_ID,
                    ContactsContract.RawContacts.SYNC1,
                    ContactsContract.RawContacts.DELETED,
                ),
                "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND " +
                    "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND " +
                    "(${ContactsContract.RawContacts.DIRTY} = 1 OR ${ContactsContract.RawContacts.DELETED} = 1)",
                arrayOf(accountName, ACCOUNT_TYPE),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val rawId = c.getLong(CURSOR_RAW_ID)
                    val deleted = c.getInt(CURSOR_DELETED) == 1
                    val sourceId = c.getStringOrNull(CURSOR_SOURCE_ID)
                    changes += LocalContactChange(
                        rawContactId = rawId,
                        sourceId = sourceId,
                        etag = c.getStringOrNull(CURSOR_SYNC1),
                        deleted = deleted,
                        contact = if (deleted) null else readContact(context, rawId, sourceId),
                    )
                }
            }
        } catch (expected: Exception) {
            Log.error(TAG, "getLocalChanges failed", expected)
        }
        return changes
    }

    fun clearDirty(context: Context, accountName: String, rawContactId: Long) {
        try {
            context.contentResolver.update(
                ContactsContract.RawContacts.CONTENT_URI.asSyncAdapter(accountName),
                android.content.ContentValues().apply { put(ContactsContract.RawContacts.DIRTY, 0) },
                "${ContactsContract.RawContacts._ID} = ?",
                arrayOf(rawContactId.toString()),
            )
        } catch (expected: Exception) {
            Log.error(TAG, "clearDirty failed", expected)
        }
    }

    /** Attach a remote UID/ETag to a locally-created raw contact after pushing it. */
    fun setSourceId(
        context: Context,
        accountName: String,
        rawContactId: Long,
        uid: String,
        etag: String?,
    ) {
        try {
            context.contentResolver.update(
                ContactsContract.RawContacts.CONTENT_URI.asSyncAdapter(accountName),
                android.content.ContentValues().apply {
                    put(ContactsContract.RawContacts.SOURCE_ID, uid)
                    put(ContactsContract.RawContacts.SYNC1, etag)
                    put(ContactsContract.RawContacts.DIRTY, 0)
                },
                "${ContactsContract.RawContacts._ID} = ?",
                arrayOf(rawContactId.toString()),
            )
        } catch (expected: Exception) {
            Log.error(TAG, "setSourceId failed", expected)
        }
    }

    private fun readContact(context: Context, rawId: Long, uid: String?): RemoteContact {
        val builder = ContactBuilder()
        try {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.Data.DATA1,
                    ContactsContract.Data.DATA2,
                    ContactsContract.Data.DATA3,
                    ContactsContract.Data.DATA4,
                    ContactsContract.Data.DATA5,
                    ContactsContract.Data.DATA6,
                ),
                "${ContactsContract.Data.RAW_CONTACT_ID} = ?",
                arrayOf(rawId.toString()),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    builder.applyRow(c)
                }
            }
        } catch (expected: Exception) {
            Log.error(TAG, "readContact failed", expected)
        }
        return builder.build(uid ?: "")
    }

    private class ContactBuilder {
        var display = ""
        var prefix = ""
        var first = ""
        var middle = ""
        var last = ""
        var suffix = ""
        var org = ""
        var note = ""
        var bday: String? = null
        val phones = mutableListOf<TypedValue>()
        val emails = mutableListOf<TypedValue>()
        val addresses = mutableListOf<TypedValue>()

        fun applyRow(c: android.database.Cursor) {
            when (c.getStringOrNull(CURSOR_UID)) {
                StructuredName.CONTENT_ITEM_TYPE -> readName(c)
                Phone.CONTENT_ITEM_TYPE -> phones += typedValue(c)
                Email.CONTENT_ITEM_TYPE -> emails += typedValue(c)
                StructuredPostal.CONTENT_ITEM_TYPE -> addresses += typedValue(c)
                Organization.CONTENT_ITEM_TYPE -> org = c.getStringOrNull(CURSOR_ETAG) ?: ""
                Note.CONTENT_ITEM_TYPE -> note = c.getStringOrNull(CURSOR_ETAG) ?: ""
                Event.CONTENT_ITEM_TYPE -> readBirthday(c)
            }
        }

        private fun readName(c: android.database.Cursor) {
            display = c.getStringOrNull(CURSOR_ETAG) ?: ""
            first = c.getStringOrNull(CURSOR_DATA2) ?: ""
            last = c.getStringOrNull(CURSOR_DATA3) ?: ""
            prefix = c.getStringOrNull(CURSOR_DATA4) ?: ""
            middle = c.getStringOrNull(CURSOR_DATA5) ?: ""
            suffix = c.getStringOrNull(CURSOR_DATA6) ?: ""
        }

        private fun typedValue(c: android.database.Cursor) =
            TypedValue(c.getStringOrNull(CURSOR_ETAG) ?: "", c.getInt(CURSOR_DATA2))

        private fun readBirthday(c: android.database.Cursor) {
            if (c.getInt(CURSOR_DATA2) == Event.TYPE_BIRTHDAY) {
                bday = c.getStringOrNull(CURSOR_ETAG)
            }
        }

        fun build(uid: String) = RemoteContact(
            uid = uid,
            displayName = display,
            prefix = prefix,
            firstName = first,
            middleName = middle,
            lastName = last,
            suffix = suffix,
            organization = org,
            note = note,
            phones = phones,
            emails = emails,
            addresses = addresses,
            birthday = bday,
        )
    }

    private fun dataOps(c: RemoteContact): List<ContentProviderOperation.Builder> {
        val ops = mutableListOf<ContentProviderOperation.Builder>()
        ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValue(ContactsContract.Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
            .withValue(StructuredName.DISPLAY_NAME, c.displayName)
            .withValue(StructuredName.GIVEN_NAME, c.firstName)
            .withValue(StructuredName.FAMILY_NAME, c.lastName)
            .withValue(StructuredName.MIDDLE_NAME, c.middleName)
            .withValue(StructuredName.PREFIX, c.prefix)
            .withValue(StructuredName.SUFFIX, c.suffix)
        if (c.organization.isNotBlank()) {
            ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.MIMETYPE, Organization.CONTENT_ITEM_TYPE)
                .withValue(Organization.COMPANY, c.organization)
        }
        for (p in c.phones) {
            ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                .withValue(Phone.NUMBER, p.value)
                .withValue(Phone.TYPE, p.type)
        }
        for (email in c.emails) {
            ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.MIMETYPE, Email.CONTENT_ITEM_TYPE)
                .withValue(Email.ADDRESS, email.value)
                .withValue(Email.TYPE, email.type)
        }
        for (a in c.addresses) {
            ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.MIMETYPE, StructuredPostal.CONTENT_ITEM_TYPE)
                .withValue(StructuredPostal.FORMATTED_ADDRESS, a.value)
                .withValue(StructuredPostal.TYPE, a.type)
        }
        if (c.note.isNotBlank()) {
            ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.MIMETYPE, Note.CONTENT_ITEM_TYPE)
                .withValue(Note.NOTE, c.note)
        }
        c.birthday?.let { bday ->
            ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.MIMETYPE, Event.CONTENT_ITEM_TYPE)
                .withValue(Event.START_DATE, bday)
                .withValue(Event.TYPE, Event.TYPE_BIRTHDAY)
        }
        return ops
    }

    private fun applyBatch(context: Context, ops: ArrayList<ContentProviderOperation>) {
        if (ops.isEmpty()) return
        try {
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        } catch (expected: Exception) {
            Log.error(TAG, "applyBatch failed", expected)
        }
    }
}
