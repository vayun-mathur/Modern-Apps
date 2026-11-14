package com.vayunmathur.communicate.data.whatsapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import com.vayunmathur.library.log.Log
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.vayunmathur.communicate.data.whatsapp.mex.MexResult
import com.vayunmathur.communicate.data.whatsapp.mex.WhatsAppMexOps
import java.util.UUID
import org.json.JSONObject

/**
 * Device address-book → WhatsApp contact sync (w2.md §8.3, Phase C 2f).
 *
 * Reads the device contacts (behind a `READ_CONTACTS` check — the permission is already declared in
 * the module manifest), normalizes numbers to E.164 via libphonenumber, de-dups, chunks, and drives
 * [WhatsAppMexOps.primaryContactsFullSync] (paged: one `session_id` UUID across pages, `last=true`
 * on the final page) followed by [WhatsAppMexOps.contactDiscovery]. Returned LID/phone mappings are
 * persisted to the [WhatsAppContact] Room table so 1:1 threads can be enriched.
 *
 * Dev-only scaffolding: the MEX contact ops resolve their `doc_id` from the bundled persist-id map,
 * which is currently empty, so the sync gracefully no-ops (persisting the local numbers with
 * `onWhatsApp=false`) until real ids are captured. Live validation is impossible (test number is
 * banned).
 */
object WhatsAppContactSync {

    private const val TAG = "WAContactSync"
    private const val PAGE_SIZE = 500

    /** One device address-book contact to check against WhatsApp. */
    data class DeviceContact(val e164: String, val name: String)

    /** The outcome of a contact sync: how many numbers were checked and how many are on WhatsApp. */
    data class SyncResult(
        val deviceCount: Int,
        val e164Count: Int,
        val onWhatsAppCount: Int,
        val transportError: String? = null,
    )

    /**
     * Normalize a raw phone string to E.164 using [region] (ISO-3166 alpha-2). Pure/JVM-testable
     * (libphonenumber only). Returns null when the number can't be parsed.
     */
    fun normalizeE164(raw: String, region: String): String? = runCatching {
        val util = PhoneNumberUtil.getInstance()
        val parsed = util.parse(raw, region)
        if (!util.isValidNumber(parsed)) return null
        util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
    }.getOrNull()

    /** The device's default region (SIM/network country, falling back to the locale). */
    fun defaultRegion(context: Context): String = runCatching {
        val tm = context.getSystemService(TelephonyManager::class.java)
        (tm?.simCountryIso?.takeIf { it.isNotBlank() } ?: tm?.networkCountryIso)?.uppercase()
    }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: context.resources.configuration.locales[0].country.ifEmpty { "US" }

    /** Read (name, number) pairs from the device address book. Empty when permission is missing. */
    fun readDeviceContacts(context: Context): List<Pair<String, String>> {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        return runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null, null,
            )?.use { cursor ->
                buildList {
                    val nameIdx =
                        cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
                    val numIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (cursor.moveToNext()) {
                        val number = cursor.getString(numIdx).orEmpty().trim()
                        if (number.isEmpty()) continue
                        add(cursor.getString(nameIdx).orEmpty() to number)
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    /**
     * Run a full contact sync: read → normalize → dedup → paged full-sync → discovery → persist.
     * Best-effort; never throws (returns a [SyncResult] with a transportError on MEX failure).
     */
    suspend fun sync(context: Context, syncContext: String = "PERIODIC_SYNC"): SyncResult {
        if (!WhatsAppFeature.enabled) return SyncResult(0, 0, 0, "disabled")
        val region = defaultRegion(context)
        val device = readDeviceContacts(context)

        // Normalize + de-dup by E.164, keeping the first display name seen.
        val byE164 = LinkedHashMap<String, String>()
        for ((name, number) in device) {
            val e164 = normalizeE164(number, region) ?: continue
            byE164.putIfAbsent(e164, name)
        }
        val e164s = byE164.keys.toList()
        if (e164s.isEmpty()) {
            return SyncResult(deviceCount = device.size, e164Count = 0, onWhatsAppCount = 0)
        }

        // Persist the local numbers first (onWhatsApp defaults false) so the mapping table is
        // populated even when the MEX ops can't resolve a doc_id.
        val now = System.currentTimeMillis()
        val db = WhatsAppDatabase.getDatabase(context)
        db.contactDao().upsertAll(
            byE164.map { (e164, name) -> WhatsAppContact(phoneE164 = e164, displayName = name, updatedAt = now) },
        )

        // Paged primary-contacts full sync: one session id, last=true on the final page.
        val sessionId = UUID.randomUUID().toString()
        val pages = e164s.chunked(PAGE_SIZE)
        var transportError: String? = null
        pages.forEachIndexed { index, page ->
            val res = WhatsAppMexOps.primaryContactsFullSync(
                context = context,
                rawPhoneNumbers = page,
                sessionId = sessionId,
                pageIndex = index,
                last = index == pages.lastIndex,
                syncContext = syncContext,
            )
            if (res.transportError != null) transportError = res.transportError
        }

        // Discovery resolves per-number LID + on-WhatsApp status; parse and persist mappings.
        val discovery = WhatsAppMexOps.contactDiscovery(context, e164s, "SEARCH")
        val mappings = parseDiscovery(discovery)
        if (mappings.isNotEmpty()) {
            db.contactDao().upsertAll(
                mappings.map { (e164, m) ->
                    WhatsAppContact(
                        phoneE164 = e164,
                        lid = m.lid,
                        displayName = byE164[e164] ?: "",
                        onWhatsApp = m.onWhatsApp,
                        updatedAt = now,
                    )
                },
            )
        }
        if (discovery.transportError != null) transportError = discovery.transportError

        val onWa = mappings.count { it.value.onWhatsApp }
        Log.status(TAG, "sync: device=${device.size} e164=${e164s.size} onWA=$onWa err=$transportError")
        return SyncResult(device.size, e164s.size, onWa, transportError)
    }

    private data class Mapping(val lid: String, val onWhatsApp: Boolean)

    /**
     * Parse `xwa2_contact_discovery` results into E.164 → (lid, onWhatsApp). Tolerant of missing
     * fields (the exact wire shape depends on captured doc_ids). Ref w2.md §8.3
     * XWA2ContactDiscoveryResult / XWA2PhoneDiscoveryOutput (status IN|OUT|INVALID).
     */
    private fun parseDiscovery(result: MexResult): Map<String, Mapping> {
        val data = result.data ?: return emptyMap()
        val payload = data.optJSONObject("xwa2_contact_discovery") ?: return emptyMap()
        val results = payload.optJSONArray("results") ?: return emptyMap()
        val out = LinkedHashMap<String, Mapping>()
        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            val lid = r.optString("lid", "")
            val detail: JSONObject? = r.optJSONObject("detail")
            val rawPn = detail?.optString("normalized_phone").orEmpty()
                .ifEmpty { detail?.optString("raw_pn").orEmpty() }
            val status = detail?.optString("status").orEmpty()
            val onWa = status.equals("IN", ignoreCase = true) || (lid.isNotEmpty() && !r.optBoolean("failed", false))
            if (rawPn.isNotEmpty()) out[rawPn] = Mapping(lid, onWa)
        }
        return out
    }
}
