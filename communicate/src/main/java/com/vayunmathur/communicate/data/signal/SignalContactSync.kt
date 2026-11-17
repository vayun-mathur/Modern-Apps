package com.vayunmathur.communicate.data.signal

import androidx.core.content.edit
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import android.util.Base64 as AndroidBase64
import com.vayunmathur.library.log.Log
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.vayunmathur.communicate.data.ContactPlatformRows
import com.vayunmathur.communicate.data.signal.transport.SignalCdsi
import com.vayunmathur.communicate.data.signal.transport.SignalTrust
import com.vayunmathur.library.network.NetworkClient
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer

/**
 * Device address-book → Signal contact sync via CDSI.
 *
 * This is what makes a phone number usable as a message destination: Signal addresses by ACI, and
 * modern envelopes carry no E164, so an address-book number is unaddressable until discovery maps it.
 *
 * The enclave interaction — SGX remote attestation and the Noise handshake — is delegated to libsignal
 * through [SignalCdsi]; the enclave will not answer without it.
 */
object SignalContactSync {
    private const val TAG = "SignalContactSync"
    private const val LONG_BYTES = 8

    /** The outcome of a Signal contact sync: how many numbers were checked and discovered. */
    data class SyncResult(
        val deviceCount: Int,
        val e164Count: Int,
        val onSignalCount: Int,
        val transportError: String? = null,
    )

    fun normalizeE164(raw: String, region: String): String? = runCatching {
        val util = PhoneNumberUtil.getInstance()
        val parsed = util.parse(raw, region)
        if (!util.isValidNumber(parsed)) return null
        util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
    }.getOrNull()

    fun defaultRegion(context: Context): String = runCatching {
        val tm = context.getSystemService(TelephonyManager::class.java)
        (tm?.simCountryIso?.takeIf { it.isNotBlank() } ?: tm?.networkCountryIso)?.uppercase()
    }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: context.resources.configuration.locales[0].country.ifEmpty { "US" }

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

    /** Encode E.164 string as 8-byte BE uint64 (digits only, no '+'). Matches cds2.proto new_e164s encoding. */
    fun e164ToUint64Bytes(e164: String): ByteArray {
        val digits = e164.filter { it.isDigit() }
        val num = digits.toLongOrNull() ?: 0L
        return ByteBuffer.allocate(LONG_BYTES).putLong(num).array()
    }

    fun uint64ToE164(value: Long): String = "+$value"

    /** Parse stored CDSI token continuation if present; persisted as base64 opaque bytes. */
    private fun loadCdsiToken(context: Context): ByteArray? = try {
        val prefs = context.getSharedPreferences("communicate_signal_cdsi", Context.MODE_PRIVATE)
        val b64 = prefs.getString("cdsi_token", null) ?: return null
        AndroidBase64.decode(b64, AndroidBase64.NO_WRAP)
    } catch (_: Exception) { null }

    private fun saveCdsiToken(context: Context, token: ByteArray?) {
        try {
            val prefs = context.getSharedPreferences("communicate_signal_cdsi", Context.MODE_PRIVATE)
            if (token == null || token.isEmpty()) {
                prefs.edit { remove("cdsi_token") }
            } else {
                prefs.edit { putString("cdsi_token", AndroidBase64.encodeToString(token, AndroidBase64.NO_WRAP)) }
            }
        } catch (_: Exception) {}
    }

    /** CDSI lookup bundle with the partition it was built from. */
    private data class CdsiLookup(
        val result: SignalCdsi.LookupResult,
        val previous: Set<String>,
        val new: Set<String>,
    )

    /** Fetch credentials + run the CDSI lookup; null on failure. */
    private suspend fun fetchLookup(
        context: Context,
        byE164: Map<String, String>,
    ): CdsiLookup? {
        val db = SignalDatabase.getDatabase(context)
        val auth = SignalAuthData.load(context) ?: return null

        val credentials = SignalCdsi.fetchCredentials(
            authHeader = SignalGroups.basicAuth(auth),
            sslSocketFactory = SignalTrust.sslSocketFactory(context),
        ) ?: return null

        // Numbers we have already asked about pair with the stored token for a cheaper incremental
        // lookup; anything else is new. A stale token is handled inside lookup().
        val known = try {
            db.contactDao().getAll().mapNotNull { it.phoneE164.takeIf { p -> p.isNotEmpty() } }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
        val previous = byE164.keys.intersect(known)
        val new = byE164.keys - previous

        val result = SignalCdsi.lookup(
            credentials = credentials,
            previousE164s = previous,
            newE164s = new,
            token = loadCdsiToken(context),
        ) ?: return null

        saveCdsiToken(context, result.token)
        return CdsiLookup(result, previous, new)
    }

    suspend fun sync(context: Context): SyncResult {
        if (!SignalFeature.enabled) return SyncResult(0, 0, 0, "disabled")
        val region = defaultRegion(context)
        val device = readDeviceContacts(context)
        val byE164 = LinkedHashMap<String, String>()
        for ((name, number) in device) {
            val e164 = normalizeE164(number, region) ?: continue
            byE164.putIfAbsent(e164, name)
        }
        if (byE164.isEmpty()) return SyncResult(device.size, 0, 0)

        val lookup = fetchLookup(context, byE164)
            ?: return SyncResult(device.size, byE164.size, 0, "CDSI lookup failed")
        val now = System.currentTimeMillis()
        val db = SignalDatabase.getDatabase(context)
        val discoveredByE164 = lookup.result.discovered.associateBy { entry -> entry.e164 }
        var onSignal = 0
        val toUpsert = byE164.map { (e164, name) ->
            val hit = discoveredByE164[e164]
            // Registered means discovery returned an identity at all. The ACI is usually absent — CDSI
            // only returns one when we already hold the contact's profile key — so the PNI is what makes
            // a contact addressable.
            val registered = hit != null && (hit.aci != null || hit.pni != null)
            if (registered) onSignal++
            SignalContact(
                aci = hit?.aci ?: e164,
                phoneE164 = e164,
                displayName = name,
                onSignal = registered,
                updatedAt = now,
                pni = hit?.pni ?: "",
            )
        }
        db.contactDao().upsertAll(toUpsert)
        Log.status(
            TAG,
            "CDSI sync: device=${device.size} e164=${byE164.size} previous=${lookup.previous.size} " +
                "new=${lookup.new.size} registered=$onSignal " +
                "withAci=${lookup.result.discovered.count { it.aci != null }}",
        )
        // Publish reachability into the contacts provider, so the contacts app can offer "message on Signal"
        // for these numbers using the vendor's own mimetypes.
        try {
            ContactPlatformRows.publish(
                context,
                toUpsert.filter { it.onSignal }.map {
                    ContactPlatformRows.Reachability(e164 = it.phoneE164, whatsApp = false, signal = true)
                },
            )
        } catch (expected: Throwable) {
            Log.status(TAG, "could not publish Signal reachability to contacts", expected)
        }
        return SyncResult(device.size, byE164.size, onSignal)
    }
}
