package com.vayunmathur.communicate.data.signal.transport

import com.vayunmathur.library.log.Log
import com.vayunmathur.communicate.data.signal.e2e.SignalE2E
import com.vayunmathur.library.network.NetworkClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import javax.net.ssl.SSLSocketFactory
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Pre-key fetching (`GET /v2/keys/...`), which is what lets a first message to a new recipient
 * establish a session.
 *
 * Response shape per the official client's `PreKeyResponse`/`PreKeyResponseItem`: a top-level
 * `identityKey` plus a `devices` array of `{deviceId, registrationId, signedPreKey, preKey,
 * pqPreKey}`. Note the Kyber key is named **`pqPreKey`** on the wire, and keys are base64 without
 * padding.
 */
@OptIn(ExperimentalEncodingApi::class)
object SignalKeysApi {
    /** Test seam: localhost base replacing the fixed chat host. */
    internal var chatBaseForTest: String? = null
    private const val TAG = "SignalKeysApi"

    private val json = Json { ignoreUnknownKeys = true }

    /** Thrown when the service has no pre-key bundle for an account (not registered). */
    class UnregisteredUserException(aci: String) : Exception("$aci is not registered")

    /** A pre-key bundle fetched for one device of an account. */
    data class DeviceBundle(val deviceId: Int, val bundle: SignalE2E.ParsedPreKeyBundle)

    /**
     * Fetch pre-key bundles for [aci]. Requesting device 1 fetches `*` instead, which returns every
     * device the recipient has — device discovery is a side effect of the fetch.
     *
     * @throws UnregisteredUserException on 404.
     */
    /**
     * Fetch pre-key bundles for [aci].
     *
     * [allDevices] uses the `*` specifier, which is what you want for a peer — but note the server does not
     * return the **requesting** device in a `*` lookup, so checking our own keys must ask for the explicit
     * device id instead.
     */
    suspend fun fetchPreKeys(
        aci: String,
        deviceId: Int,
        authHeader: String,
        sslSocketFactory: SSLSocketFactory?,
        allDevices: Boolean = true,
    ): List<DeviceBundle> {
        val specifier = if (allDevices && deviceId == 1) "*" else deviceId.toString()
        val resp = NetworkClient.execute(
            "${chatBaseForTest ?: "https://chat.signal.org"}/v2/keys/$aci/$specifier",
            method = "GET",
            headers = mapOf("Authorization" to "Basic $authHeader"),
            sslSocketFactory = sslSocketFactory,
        )
        if (resp.status == HTTP_NOT_FOUND) throw UnregisteredUserException(aci)
        if (!resp.isSuccess) {
            Log.status(TAG, "prekey fetch for $aci failed: ${resp.status} ${resp.statusMessage}")
            return emptyList()
        }
        return parse(resp.text, aci)
    }

    /**
     * Devices without a Kyber pre-key are skipped: this protocol version requires PQXDH, and building
     * a non-post-quantum session instead would be a silent downgrade.
     *
     * [warn] is injectable so this stays testable off-device.
     */
    internal fun parse(
        body: String,
        aci: String,
        warn: (String) -> Unit = { Log.status(TAG, it) },
    ): List<DeviceBundle> {
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (expected: Exception) {
            warn("unparseable prekey response for $aci: ${expected.message}")
            return emptyList()
        }
        val identityKey = decode(root.str("identityKey")) ?: run {
            warn("prekey response for $aci has no identityKey")
            return emptyList()
        }
        val devices = try {
            root["devices"]?.jsonArray ?: return emptyList()
        } catch (_: Exception) {
            return emptyList()
        }

        val result = ArrayList<DeviceBundle>(devices.size)
        for (element in devices) {
            parseDeviceBundle(aci, element, identityKey, warn)?.let { result.add(it) }
        }
        return result
    }

    /** One device's pre-key bundle, or null (warned) when unusable. */
    private const val HTTP_NOT_FOUND = 404
    private const val BASE64_SINGLE_PAD = 3

    private fun parseDeviceBundle(
        aci: String,
        element: JsonElement,
        identityKey: ByteArray,
        warn: (String) -> Unit = { Log.status(TAG, it) },
    ): DeviceBundle? {
        val device = try {
            element.jsonObject
        } catch (_: Exception) {
            return null
        }
        val deviceId = device.int("deviceId") ?: return null
        if (deviceId < 1) return null

        val signed = device.obj("signedPreKey")
        val signedPublic = decode(signed?.str("publicKey"))
        val signedSignature = decode(signed?.str("signature"))
        if (signed == null || signedPublic == null || signedSignature == null) {
            warn("$aci device $deviceId has no signed pre-key, skipping")
            return null
        }

        val pq = device.obj("pqPreKey")
        val pqPublic = decode(pq?.str("publicKey"))
        val pqSignature = decode(pq?.str("signature"))
        if (pq == null || pqPublic == null || pqSignature == null) {
            warn("$aci device $deviceId has no Kyber pre-key, skipping")
            return null
        }

        // The one-time EC pre-key is the only optional part; the server runs out of them.
        val oneTime = device.obj("preKey")
        val oneTimePublic = decode(oneTime?.str("publicKey"))

        return DeviceBundle(
            deviceId = deviceId,
            bundle = SignalE2E.ParsedPreKeyBundle(
                registrationId = device.int("registrationId") ?: 0,
                preKeyId = if (oneTimePublic != null) oneTime?.int("keyId") else null,
                preKeyPublic = oneTimePublic,
                signedPreKeyId = signed.int("keyId") ?: 0,
                signedPreKeyPublic = signedPublic,
                signedPreKeySignature = signedSignature,
                identityKey = identityKey,
                kyberPreKeyId = pq.int("keyId") ?: 0,
                kyberPreKeyPublic = pqPublic,
                kyberPreKeySignature = pqSignature,
            ),
        )
    }

    private fun JsonObject.str(key: String): String? =
        try { this[key]?.jsonPrimitive?.content } catch (_: Exception) { null }

    private fun JsonObject.int(key: String): Int? =
        try { this[key]?.jsonPrimitive?.int } catch (_: Exception) { null }

    private fun JsonObject.obj(key: String): JsonObject? =
        try { this[key]?.jsonObject } catch (_: Exception) { null }

    /**
     * Register pre-keys with the server (`PUT /v2/keys?identity=aci`).
     *
     * Only needed for keys the server does not already have — a Kyber pre-key that had to be regenerated,
     * or a fresh batch of one-time keys. Keys go up base64 **without** padding, matching the fetch format.
     */
    suspend fun uploadPreKeys(
        signedPreKey: SignalE2E.PreKeyUpload.KeyEntity?,
        lastResortKyber: SignalE2E.PreKeyUpload.KeyEntity?,
        oneTimeEcPreKeys: List<SignalE2E.PreKeyUpload.KeyEntity>,
        authHeader: String,
        sslSocketFactory: SSLSocketFactory?,
    ): Boolean {
        val body = buildJsonObject {
            if (signedPreKey != null) {
                putJsonObject("signedPreKey") {
                    put("keyId", signedPreKey.id)
                    put("publicKey", encodeUnpadded(signedPreKey.publicKey))
                    put("signature", encodeUnpadded(signedPreKey.signature ?: ByteArray(0)))
                }
            }
            if (lastResortKyber != null) {
                putJsonObject("pqLastResortPreKey") {
                    put("keyId", lastResortKyber.id)
                    put("publicKey", encodeUnpadded(lastResortKyber.publicKey))
                    put("signature", encodeUnpadded(lastResortKyber.signature ?: ByteArray(0)))
                }
            }
            if (oneTimeEcPreKeys.isNotEmpty()) {
                putJsonArray("preKeys") {
                    oneTimeEcPreKeys.forEach { key ->
                        add(
                            buildJsonObject {
                                put("keyId", key.id)
                                put("publicKey", encodeUnpadded(key.publicKey))
                            },
                        )
                    }
                }
            }
        }.toString().toByteArray(Charsets.UTF_8)

        val resp = try {
            NetworkClient.execute(
                "${chatBaseForTest ?: "https://chat.signal.org"}/v2/keys?identity=aci",
                method = "PUT",
                headers = mapOf(
                    "Authorization" to "Basic $authHeader",
                    "Content-Type" to "application/json",
                ),
                body = body,
                sslSocketFactory = sslSocketFactory,
            )
        } catch (expected: Throwable) {
            Log.status(TAG, "pre-key registration failed", expected)
            return false
        }
        if (!resp.isSuccess) {
            Log.status(TAG, "pre-key registration rejected: ${resp.status} ${resp.statusMessage}")
            return false
        }
        Log.status(TAG, "registered pre-keys: signed=${signedPreKey != null}" +
            "kyber=${lastResortKyber != null} oneTime=${oneTimeEcPreKeys.size}")
        return true
    }

    /** Keys go on the wire base64 without padding. */
    private fun encodeUnpadded(bytes: ByteArray): String =
        Base64.Default.encode(bytes).trimEnd('=')

    /** Keys arrive base64 without padding, so restore it before decoding. */
    private fun decode(value: String?): ByteArray? {
        if (value.isNullOrEmpty()) return null
        val padded = when (value.length % 4) {
            2 -> "$value=="
            BASE64_SINGLE_PAD -> "$value="
            0 -> value
            else -> return null
        }
        return try { Base64.Default.decode(padded) } catch (_: Exception) { null }
    }
}
