package com.vayunmathur.contacts.data

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.SubscriptionManager
import com.vayunmathur.library.log.Log
import androidx.core.content.ContextCompat
import com.vayunmathur.contacts.R

/**
 * SIM ADN (Abbreviated Dialling Numbers) data source.
 *
 * Accessed via content://icc/adn and content://icc/adn/subId/<subId>.
 * Columns: name/tag, number, emails, _id (where present). Insert/delete are
 * supported via ContentResolver on most devices; queries may throw or return
 * null on emulators or devices without a physical SIM.
 */
data class SimContact(
    val id: Long = -1L,
    val name: String,
    val number: String,
    val emails: String? = null,
    val subscriptionId: Int? = null
)

const val SIM_ACCOUNT_TYPE = "com.vayunmathur.contacts.sim"

/**
 * SIM ADN rows store a single name, a single number, and a single email address.
 * The add/edit form therefore offers exactly one phone field and one email field
 * for SIM accounts: no type selectors and no add-more buttons.
 */
const val SIM_MAX_PHONE_NUMBERS = 1
const val SIM_MAX_EMAILS = 1

/** Account type for the app's on-device local accounts (renamable/deletable by the user). */
const val LOCAL_ACCOUNT_TYPE = "com.vayunmathur.contacts.local"

fun isSimAccountType(type: String?): Boolean = type == SIM_ACCOUNT_TYPE

/**
 * True for accounts the app may rename/delete: its own local type, plus the
 * device-local account whose type is null/empty (e.g. contacts created by
 * another local contacts app that has no sync adapter). Synced accounts
 * (Google etc.) and SIM accounts have a real non-empty type and are excluded.
 */
fun isLocalAccountType(type: String?): Boolean = type.isNullOrBlank() || type == LOCAL_ACCOUNT_TYPE

/**
 * True for the implicit device-local account, which the provider identifies by having
 * *neither* an account name nor a type. It cannot be renamed: the provider requires an
 * account to specify both fields or neither, so a name-only rename leaves rows it
 * rejects on the next insert.
 */
fun isDefaultLocalAccount(name: String?, type: String?): Boolean =
    name.isNullOrBlank() && type.isNullOrBlank()

object SimContactsDataSource {

    private const val TAG = "SimContacts"
    private const val BASE_URI = "content://icc/adn"

    /** Base ADN URI observers register on; the base for per-subId URIs. */
    internal const val SIM_ADN_BASE_URI: String = BASE_URI

    // Broad catch is deliberate: OEM ICC providers throw undocumented
    // RuntimeExceptions (not just SecurityException) on emulators and
    // devices without a physical SIM.
    @Suppress("TooGenericExceptionCaught")
    fun getActiveSubscriptionIds(context: Context): List<Int> {
        return try {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_PHONE_STATE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // Still try — some devices allow reading without READ_PHONE_STATE
                // but SubscriptionManager will throw SecurityException otherwise.
            }
            // Prefer SubscriptionManager
            val subMgr =
                context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
            val list = querySubscriptionInfoList(subMgr)
            list?.mapNotNull { it.subscriptionId } ?: emptyList()
        } catch (e: Exception) {
            Log.status(TAG, "getActiveSubscriptionIds failed", e)
            emptyList()
        }
    }

    // Broad catch is deliberate: OEM ICC providers throw undocumented
    // RuntimeExceptions (not just SecurityException) on emulators and
    // devices without a physical SIM.
    @Suppress("TooGenericExceptionCaught")
    private fun querySubscriptionInfoList(
        subMgr: SubscriptionManager
    ): List<android.telephony.SubscriptionInfo>? {
        return try {
            subMgr.activeSubscriptionInfoList
        } catch (e: SecurityException) {
            Log.status(TAG, "No permission for activeSubscriptionInfoList", e)
            null
        } catch (e: Exception) {
            Log.status(TAG, "Failed to get active subscriptions", e)
            null
        }
    }

    // Broad catch is deliberate: OEM ICC providers throw undocumented
    // RuntimeExceptions (not just SecurityException) on emulators and
    // devices without a physical SIM.
    @Suppress("TooGenericExceptionCaught")
    fun listSimContacts(context: Context): List<SimContact> {
        val result = mutableListOf<SimContact>()
        try {
            val subIds = getActiveSubscriptionIds(context)
            val uris: List<Pair<Uri, Int?>> = if (subIds.isNotEmpty()) {
                subIds.map { subId -> Uri.parse("$BASE_URI/subId/$subId") to subId }
            } else {
                listOf(Uri.parse(BASE_URI) to null)
            }
            for ((uri, subId) in uris) {
                collectSimContactsFromUri(context, uri, subId, result)
            }
        } catch (e: Exception) {
            Log.status(TAG, "listSimContacts failed", e)
        }
        // Deduplicate by (subId, name, number, emails)
        return result.distinctBy { "${it.subscriptionId}|${it.name}|${it.number}|${it.emails}" }
    }

    // Broad catch is deliberate: OEM ICC providers throw undocumented
    // RuntimeExceptions (not just SecurityException) on emulators and
    // devices without a physical SIM.
    @Suppress("TooGenericExceptionCaught")
    private fun collectSimContactsFromUri(
        context: Context,
        uri: Uri,
        subId: Int?,
        result: MutableList<SimContact>,
    ) {
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.count == 0) return
                val indexes = SimColumnIndexes(cursor)
                while (cursor.moveToNext()) {
                    readSimRow(cursor, indexes, subId)?.let { result.add(it) }
                }
            }
        } catch (e: SecurityException) {
            Log.status(TAG, "SecurityException querying $uri", e)
        } catch (e: Exception) {
            Log.status(TAG, "Failed querying $uri", e)
        }
    }

    private class SimColumnIndexes(cursor: android.database.Cursor) {
        val nameIdx = pickColumn(cursor, "name", "tag")
        val numberIdx = pickColumn(cursor, "number", "newNumber")
        val emailsIdx = pickColumn(cursor, "emails", "email")
        val idIdx = cursor.getColumnIndex("_id")

        private fun pickColumn(
            cursor: android.database.Cursor,
            primary: String,
            fallback: String,
        ): Int = when {
            cursor.getColumnIndex(primary) != -1 -> cursor.getColumnIndex(primary)
            cursor.getColumnIndex(fallback) != -1 -> cursor.getColumnIndex(fallback)
            else -> -1
        }
    }

    // Broad catch is deliberate: OEM ICC providers throw undocumented
    // RuntimeExceptions (not just SecurityException) on emulators and
    // devices without a physical SIM.
    @Suppress("TooGenericExceptionCaught")
    private fun readSimRow(
        cursor: android.database.Cursor,
        indexes: SimColumnIndexes,
        subId: Int?,
    ): SimContact? {
        return try {
            val rawName = if (indexes.nameIdx != -1) cursor.getString(indexes.nameIdx) else null
            val rawNumber =
                if (indexes.numberIdx != -1) cursor.getString(indexes.numberIdx) else null
            val rawEmails =
                if (indexes.emailsIdx != -1) cursor.getString(indexes.emailsIdx) else null
            val name = rawName?.trim().orEmpty()
            val number = rawNumber?.trim().orEmpty()
            if (name.isEmpty() && number.isEmpty()) return null
            val id = readSimRowId(cursor, indexes.idIdx)
            SimContact(
                id = id,
                name = name,
                number = number,
                emails = rawEmails?.trim()?.takeIf { it.isNotEmpty() },
                subscriptionId = subId
            )
        } catch (e: Exception) {
            Log.status(TAG, "Skipping SIM row", e)
            null
        }
    }

    private fun readSimRowId(cursor: android.database.Cursor, idIdx: Int): Long {
        if (idIdx == -1) return -1L
        return try {
            cursor.getLong(idIdx)
        } catch (_: Exception) {
            -1L
        }
    }

    // Broad catch is deliberate: OEM ICC providers throw undocumented
    // RuntimeExceptions (not just SecurityException) on emulators and
    // devices without a physical SIM.
    @Suppress("TooGenericExceptionCaught")
    fun insertSimContact(
        context: Context,
        name: String,
        number: String,
        email: String? = null,
        subscriptionId: Int? = null
    ): Boolean {
        if (name.isBlank() && number.isBlank()) return false
        return try {
            val subIds = if (subscriptionId != null) {
                listOf(subscriptionId)
            } else {
                getActiveSubscriptionIds(context)
            }
            val targetSubId = subIds.firstOrNull() ?: subscriptionId
            val uri = if (targetSubId != null) {
                Uri.parse("$BASE_URI/subId/$targetSubId")
            } else {
                Uri.parse(BASE_URI)
            }
            val values = ContentValues().apply {
                // Most devices accept "tag" for name
                put("tag", name)
                put("number", number)
                // Some accept "name" alias
                if (!containsKey("tag")) put("name", name)
                if (!email.isNullOrBlank()) {
                    put("emails", email)
                    put("email", email)
                }
            }
            val inserted = context.contentResolver.insert(uri, values)
            if (inserted != null) notifySimChanged(context, uri)
            inserted != null
        } catch (e: SecurityException) {
            Log.error(TAG, "SecurityException inserting SIM contact", e)
            false
        } catch (e: Exception) {
            Log.error(TAG, "insertSimContact failed", e)
            false
        }
    }

    /**
     * Edits a row in place. The provider identifies the existing row by `tag`/`number` in the
     * values and replaces it with `newTag`/`newNumber`.
     *
     * Preferred over delete-then-insert, which cannot be made reliable: the ICC provider parses
     * the selection string itself and ignores selectionArgs (see [deleteSingle]).
     */
    // Broad catch is deliberate: OEM ICC providers throw undocumented
    // RuntimeExceptions (not just SecurityException) on emulators and
    // devices without a physical SIM.
    @Suppress("TooGenericExceptionCaught")
    fun updateSimContact(
        context: Context,
        old: SimContact,
        name: String,
        number: String,
        email: String? = null,
        subscriptionId: Int? = null
    ): Boolean {
        if (name.isBlank() && number.isBlank()) return false
        return try {
            val targetSubId = subscriptionId ?: old.subscriptionId
            val uri = if (targetSubId != null) {
                Uri.parse("$BASE_URI/subId/$targetSubId")
            } else {
                Uri.parse(BASE_URI)
            }
            val values = ContentValues().apply {
                put("tag", old.name)
                put("number", old.number)
                put("newTag", name)
                put("newNumber", number)
                // Unconditional: an omitted key leaves the old address in place, so clearing an
                // email would report success without changing anything.
                put("newEmails", email.orEmpty())
            }
            val updated = context.contentResolver.update(uri, values, null, null) > 0
            if (updated) notifySimChanged(context, uri)
            updated
        } catch (e: SecurityException) {
            Log.error(TAG, "SecurityException updating SIM contact", e)
            false
        } catch (e: Exception) {
            Log.error(TAG, "updateSimContact failed", e)
            false
        }
    }

    // Broad catch is deliberate: OEM ICC providers throw undocumented
    // RuntimeExceptions (not just SecurityException) on emulators and
    // devices without a physical SIM.
    @Suppress("TooGenericExceptionCaught")
    fun deleteSimContact(context: Context, simContact: SimContact): Boolean {
        return try {
            deleteSimContactInternal(context, simContact)
        } catch (e: Exception) {
            Log.error(TAG, "deleteSimContact failed", e)
            false
        }
    }

    private fun deleteSimContactInternal(context: Context, simContact: SimContact): Boolean {
            val uri = if (simContact.subscriptionId != null) {
                Uri.parse("$BASE_URI/subId/${simContact.subscriptionId}")
            } else {
                // Try all known URIs if subId unknown
                val subIds = getActiveSubscriptionIds(context)
                if (subIds.isNotEmpty()) {
                    var deletedAny = false
                    for (sid in subIds) {
                        if (deleteSingle(context, Uri.parse("$BASE_URI/subId/$sid"), simContact)) deletedAny = true
                    }
                    // Also try base
                    if (deleteSingle(context, Uri.parse(BASE_URI), simContact)) deletedAny = true
                    return deletedAny
                }
                Uri.parse(BASE_URI)
            }
            return deleteSingle(context, uri, simContact)
    }

    private fun deleteSingle(context: Context, uri: Uri, simContact: SimContact): Boolean {
        // The ICC provider parses this selection itself - splitting on "AND" then "=" and stripping
        // surrounding quotes - and never looks at selectionArgs, so a bound "?" is compared
        // literally and matches nothing. The values have to be inlined.
        val tag = simContact.name.asIccLiteral()
        val number = simContact.number.asIccLiteral()
        val variants = listOfNotNull(
            "tag=$tag AND number=$number",
            "name=$tag AND number=$number",
            // Fallback: number only. Skipped for a contact with no number, where it would become
            // number='' and match every other numberless record on the SIM.
            "number=$number".takeIf { simContact.number.isNotBlank() },
        )
        for (where in variants) {
            try {
                val count = context.contentResolver.delete(uri, where, null)
                if (count > 0) {
                    notifySimChanged(context, uri)
                    return true
                }
            } catch (_: Exception) { }
        }
        // Last resort: query then delete by encoding? Some providers expect content://icc/adn/#
        // Not supported uniformly — return false
        return false
    }

    /**
     * Quotes a value for the ICC provider's own selection parser, which strips one leading and one
     * trailing quote and leaves the rest alone - so an apostrophe inside the value survives and
     * must not be escaped.
     */
    private fun String.asIccLiteral(): String = "'$this'"

    /**
     * Broadcasts our own ADN write to any observer of the SIM provider. The
     * platform IccProvider does not reliably notify these URIs itself, and the
     * app's contacts observer watches ContactsContract only, so without this a
     * successful write can sit invisible until the next unrelated re-sync.
     * Notifying both the written URI and the base URI covers subId-scoped and
     * base-URI observers. Best effort: notification must never fail a write.
     */
    private fun notifySimChanged(context: Context, uri: Uri) {
        try {
            context.contentResolver.notifyChange(uri, null)
            val base = Uri.parse(BASE_URI)
            if (uri != base) context.contentResolver.notifyChange(base, null)
        } catch (_: Exception) { }
    }

    fun hasSim(context: Context): Boolean {
        return try {
            getActiveSubscriptionIds(context).isNotEmpty()
        } catch (_: Exception) { false }
    }

    // --- Virtual SIM accounts ---

    data class SimSubscriptionInfo(
        val subscriptionId: Int,
        val slotIndex: Int,
        val displayName: String?,
        val carrierName: CharSequence?
    )

    /**
     * Returns one entry per active SIM subscription, with slot/carrier info.
     * Falls back to [getActiveSubscriptionIds]-based entries when SubscriptionInfo
     * read needs unavailable permission.
     */
    // Broad catch is deliberate: OEM ICC providers throw undocumented
    // RuntimeExceptions (not just SecurityException) on emulators and
    // devices without a physical SIM.
    @Suppress("TooGenericExceptionCaught")
    fun getSimSubscriptionInfos(context: Context): List<SimSubscriptionInfo> {
        return try {
            val subMgr =
                context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
            val list = querySubscriptionInfoList(subMgr)
                ?: return getActiveSubscriptionIds(context).mapIndexed { idx, sid ->
                    SimSubscriptionInfo(sid, idx, null, null)
                }
            list.mapNotNull { info ->
                readSubscriptionInfo(info)
            }
        } catch (e: Exception) {
            Log.status(TAG, "getSimSubscriptionInfos failed", e)
            emptyList()
        }
    }

    private fun readSubscriptionInfo(
        info: android.telephony.SubscriptionInfo,
    ): SimSubscriptionInfo {
        return try {
            SimSubscriptionInfo(
                subscriptionId = info.subscriptionId,
                slotIndex = info.simSlotIndex,
                displayName = info.displayName?.toString(),
                carrierName = info.carrierName
            )
        } catch (_: Exception) {
            SimSubscriptionInfo(info.subscriptionId, 0, null, null)
        }
    }

    /** 1-based SIM slot number, for display. */
    fun slotNumberFor(info: SimSubscriptionInfo): Int =
        if (info.slotIndex >= 0) info.slotIndex + 1 else 1

    fun getSimAccountDisplayLabel(context: Context, info: SimSubscriptionInfo): String {
        val base = context.getString(R.string.sim_slot_label, slotNumberFor(info))
        val carrier = info.carrierName?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: info.displayName?.trim()
                ?.takeIf { it.isNotEmpty() && !it.equals(base, ignoreCase = true) }
        return if (carrier != null) "$base — $carrier" else base
    }

    /**
     * Stable virtual account name for a subscription — the subId string.
     * The display label is [getSimAccountDisplayLabel]; the stored account name
     * must be opaque and survive carrier renames, so we key on subId.
     */
    fun accountNameFor(info: SimSubscriptionInfo): String = info.subscriptionId.toString()

    /**
     * Account name for a raw SimContact. Falls back to subId string or empty.
     */
    fun accountNameForSimContact(sc: SimContact): String = sc.subscriptionId?.toString() ?: ""

    // ---- Projection into unified Contact model ----

    private const val SIM_SYNTHETIC_ID_BASE: Long = -1_000_000_000L
    private const val ID_HASH_MASK: Long = 0xFFFFFFFFL

    /**
     * Deterministic stable negative id for a SIM-backed Contact so it never
     * collides with real RawContacts ids and survives remove+reinsert of
     * a different row before it in the cursor. Hash is on (subId|name|number|email).
     */
    fun syntheticIdFor(sc: SimContact): Long {
        val h = "${sc.subscriptionId}|${sc.name}|${sc.number}|${sc.emails}".hashCode()
        // Map signed int hash into a negative Long away from -1..-N used by AutoIncrement edge.
        return SIM_SYNTHETIC_ID_BASE - (h.toLong() and ID_HASH_MASK)
    }

    /**
     * Projects ICC ADN rows into synthetic [com.vayunmathur.contacts.data.Contact]
     * objects carrying accountType=SIM_ACCOUNT_TYPE and accountName=subId string.
     * Only name+phone+email survive on SIM; other details are defaulted/empty.
     */
    fun simContactsAsContacts(context: Context): List<com.vayunmathur.contacts.data.Contact> {
        val sims = listSimContacts(context)
        if (sims.isEmpty()) return emptyList()
        return sims.map { sc -> simContactToContact(sc) }
    }

    fun simContactToContact(sc: SimContact): com.vayunmathur.contacts.data.Contact {
        val syntheticId = syntheticIdFor(sc)
        val display = sc.name.trim()
        // Split on first space for given/family fallback; full value preserved via Name.value join.
        val first = display.substringBefore(" ").ifEmpty { display }
        val last = if (" " in display) display.substringAfter(" ") else ""
        // Even an unnamed SIM still has firstName/lastName split; Name.value is blank if both empty.
        val nameObj = com.vayunmathur.contacts.data.Name(0, "", first, "", last, "")
        val phones = if (sc.number.isNotBlank()) {
            val mobileType = android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
            listOf(com.vayunmathur.contacts.data.PhoneNumber(0, sc.number, mobileType))
        } else {
            emptyList()
        }
        val emails = sc.emails?.takeIf { it.isNotBlank() }?.let {
            val homeType = android.provider.ContactsContract.CommonDataKinds.Email.TYPE_HOME
            listOf(com.vayunmathur.contacts.data.Email(0, it, homeType))
        } ?: emptyList()
        val defaultNicknameType =
            android.provider.ContactsContract.CommonDataKinds.Nickname.TYPE_DEFAULT
        val details = com.vayunmathur.contacts.data.ContactDetails(
            phoneNumbers = phones,
            emails = emails,
            addresses = emptyList(),
            dates = emptyList(),
            photos = emptyList(),
            names = listOf(nameObj),
            orgs = listOf(com.vayunmathur.contacts.data.Organization(0, "")),
            notes = listOf(com.vayunmathur.contacts.data.Note(0, "")),
            nicknames = listOf(
                com.vayunmathur.contacts.data.Nickname(0, "", defaultNicknameType)
            ),
            groups = emptyList()
        )
        return com.vayunmathur.contacts.data.Contact(
            id = syntheticId,
            accountType = SIM_ACCOUNT_TYPE,
            accountName = accountNameForSimContact(sc),
            isFavorite = false,
            details = details
        )
    }

    /**
     * Finds the SimContact backing a synthetic Contact (by match on synthetic id),
     * or by (name|number) scan when id was not produced by [syntheticIdFor].
     */
    fun findBackingSimContact(context: Context, contact: com.vayunmathur.contacts.data.Contact): SimContact? {
        if (!isSimAccountType(contact.accountType)) return null
        val all = listSimContacts(context)
        val byId = all.firstOrNull { syntheticIdFor(it) == contact.id }
        if (byId != null) return byId
        // Fallback: match on accountName (subId) + first phone + display name
        val subId = contact.accountName?.toIntOrNull()
        val phone = contact.details.phoneNumbers.firstOrNull()?.number?.trim() ?: ""
        val disp = contact.name.value.trim()
        return all.firstOrNull {
            (subId == null || it.subscriptionId == subId) &&
                (phone.isEmpty() || it.number.trim() == phone) &&
                (disp.isEmpty() || it.name.trim() == disp)
        } ?: all.firstOrNull { it.subscriptionId?.toString() == contact.accountName }
    }
}
