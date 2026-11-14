package com.vayunmathur.communicate.data.signal.transport

import com.vayunmathur.library.log.Log
import com.vayunmathur.library.network.NetworkClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.signal.libsignal.net.CdsiInvalidTokenException
import org.signal.libsignal.net.CdsiLookupRequest
import org.signal.libsignal.net.Network
import org.signal.libsignal.protocol.ServiceId
import org.signal.libsignal.zkgroup.profiles.ProfileKey
import java.util.Optional
import javax.net.ssl.SSLSocketFactory

/**
 * Contact discovery (CDSI), which is the only way to learn the ACI behind a phone number — Signal
 * addresses messages by ACI, and modern envelopes carry no E164, so without this a number from the
 * address book cannot be messaged at all.
 *
 * The hard part is not implemented here on purpose: CDSI runs inside an SGX enclave and the client must
 * complete remote attestation (DCAP quote verification against Intel's roots, TCB/endorsement checks)
 * and a Noise handshake bound to the attested key before the enclave will answer. libsignal does all of
 * that natively via [Network.cdsiLookup], so this class only handles credentials, request shaping and
 * the response.
 */
object SignalCdsi {
    private const val TAG = "SignalCdsi"

    private val json = Json { ignoreUnknownKeys = true }

    /** Short-lived CDSi credentials (username/password) for one contact-discovery lookup. */
    data class Credentials(val username: String, val password: String)

    /** One discovered account. [aci] is null when the number is registered but its ACI is withheld. */
    data class Discovered(val e164: String, val aci: String?, val pni: String?)

    /** The result of a CDSi lookup: discovered accounts plus a rate-limit continuation token. */
    data class LookupResult(
        val discovered: List<Discovered>,
        /** Continuation token to reuse next time, reducing rate-limit cost. */
        val token: ByteArray?,
    )

    @Volatile private var network: Network? = null

    private fun network(): Network = network ?: synchronized(this) {
        network ?: Network(Network.Environment.PRODUCTION, SignalPayload.userAgent()).also { network = it }
    }

    /** Enclave credentials from `GET /v2/directory/auth`, distinct from the account's own auth. */
    suspend fun fetchCredentials(authHeader: String, sslSocketFactory: SSLSocketFactory?): Credentials? {
        val resp = try {
            NetworkClient.execute(
                "https://chat.signal.org/v2/directory/auth",
                method = "GET",
                headers = mapOf("Authorization" to "Basic $authHeader"),
                sslSocketFactory = sslSocketFactory,
            )
        } catch (expected: Throwable) {
            Log.status(TAG, "CDSI auth fetch failed", expected)
            return null
        }
        if (!resp.isSuccess) {
            Log.status(TAG, "CDSI auth fetch failed: ${resp.status} ${resp.statusMessage}")
            return null
        }
        return parseCredentials(resp.text)
    }

    internal fun parseCredentials(body: String, warn: (String) -> Unit = { Log.status(TAG, it) }): Credentials? {
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (expected: Exception) {
            warn("unparseable CDSI auth response: ${expected.message}")
            return null
        }
        val username = try { root["username"]?.jsonPrimitive?.content } catch (_: Exception) { null }
        val password = try { root["password"]?.jsonPrimitive?.content } catch (_: Exception) { null }
        if (username.isNullOrEmpty() || password.isNullOrEmpty()) {
            warn("CDSI auth response was missing username or password")
            return null
        }
        return Credentials(username, password)
    }

    /**
     * Look up [newE164s] (and re-assert [previousE164s]) against the enclave.
     *
     * [previousE164s] plus [token] is the incremental form, which costs less quota. The token must match
     * the set it was issued for, so a mismatch is retried as a full lookup rather than failing.
     */
    suspend fun lookup(
        credentials: Credentials,
        previousE164s: Set<String>,
        newE164s: Set<String>,
        token: ByteArray?,
    ): LookupResult? {
        if (previousE164s.isEmpty() && newE164s.isEmpty()) return LookupResult(emptyList(), token)
        return try {
            performLookup(credentials, previousE164s, newE164s, token)
        } catch (expected1: Throwable) {
            val cause =
                if (expected1 is java.util.concurrent.ExecutionException) expected1.cause ?: expected1 else expected1
            if (cause is CdsiInvalidTokenException && token != null) {
                Log.status(TAG, "CDSI token no longer valid, retrying as a full lookup")
                try {
                    performLookup(credentials, emptySet(), previousE164s + newE164s, null)
                } catch (expected: Throwable) {
                    Log.status(TAG, "CDSI lookup failed", expected)
                    null
                }
            } else {
                Log.status(TAG, "CDSI lookup failed", cause)
                null
            }
        }
    }

    private fun performLookup(
        credentials: Credentials,
        previousE164s: Set<String>,
        newE164s: Set<String>,
        token: ByteArray?,
    ): LookupResult {
        var issuedToken: ByteArray? = null
        val request = CdsiLookupRequest(
            previousE164s,
            newE164s,
            // Only needed when asserting ownership of service ids; a plain number lookup sends none.
            emptyMap<ServiceId, ProfileKey>(),
            Optional.ofNullable(token),
        )
        // libsignal performs attestation and the Noise handshake inside this call.
        val response = network()
            .cdsiLookup(credentials.username, credentials.password, request) { issued -> issuedToken = issued }
            .get()

        val discovered = response.entries().map { (e164, entry) ->
            Discovered(
                e164 = e164,
                aci = entry.aci?.toServiceIdString(),
                pni = entry.pni?.toServiceIdString(),
            )
        }
        Log.status(TAG, "CDSI returned ${discovered.size} entries (permits used ${response.debugPermitsUsed})")
        return LookupResult(discovered, issuedToken ?: token)
    }
}
