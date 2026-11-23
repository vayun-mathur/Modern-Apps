package com.vayunmathur.passwords.platform

import android.content.Context
import com.vayunmathur.library.log.Log
import androidx.credentials.provider.CallingAppInfo
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Singleton holding passkey utils state and operations. */
@OptIn(ExperimentalEncodingApi::class)
object PasskeyUtils {

    private const val TAG = "PasskeyUtils"

    private const val FLAG_USER_PRESENT = 0x01
    private const val FLAG_USER_VERIFIED = 0x04
    private const val FLAG_BACKUP_ELIGIBLE = 0x08
    private const val FLAG_BACKUP_STATE = 0x10
    private const val FLAG_ATTESTED_CREDENTIAL = 0x40
    private const val SIGN_COUNT_SHIFT_3 = 24
    private const val SIGN_COUNT_SHIFT_2 = 16
    private const val SIGN_COUNT_SHIFT_1 = 8

    private val secureRandom = SecureRandom()

    /**
     * AAGUID for credentials created on this device through Credential Manager. The caBLE
     * authenticator instead uses [com.vayunmathur.passwords.platform.cable.Ctap.ZERO_AAGUID],
     * because the getInfo response it sends the browser advertises an all-zero AAGUID.
     */
    val SAME_DEVICE_AAGUID: ByteArray = byteArrayOf(
        0xA1.toByte(), 0xB2.toByte(), 0xC3.toByte(), 0xD4.toByte(),
        0xE5.toByte(), 0xF6.toByte(), 0x78, 0x90.toByte(),
        0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte(), 0x12,
        0x34, 0x56, 0x78, 0x90.toByte(),
    )

    // -- base64url --
    //
    // Credential ids and user handles are stored on [com.vayunmathur.passwords.data.Passkey] as
    // base64url strings. Both transports must use the same encoding or credential lookup silently
    // fails across them, so everything goes through these two.

    private val urlEncoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    private val urlDecoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

    fun encodeB64Url(data: ByteArray): String = urlEncoder.encode(data)

    fun decodeB64Url(value: String): ByteArray = urlDecoder.decode(value)

    fun generateCredentialId(): ByteArray {
        val id = ByteArray(16)
        secureRandom.nextBytes(id)
        return id
    }

    // -- Request-descriptor helpers --
    //
    // Credential ids cross the Credential Manager boundary as base64url strings. A relying
    // party may spell the same id with padding, so compare decoded bytes, not raw strings.

    /** True when [storedId] and [requestedId] name the same credential bytes. */
    fun credentialIdsMatch(storedId: String, requestedId: String): Boolean {
        if (storedId == requestedId) return true
        if (storedId.isEmpty() || requestedId.isEmpty()) return false
        return runCatching {
            decodeB64Url(storedId).contentEquals(decodeB64Url(requestedId))
        }.getOrDefault(false)
    }

    /**
     * The `public-key` credential ids named by a `allowCredentials` / `excludeCredentials`
     * array in a request JSON object; empty when the array is absent.
     */
    fun credentialDescriptorIds(request: JSONObject, key: String): List<String> {
        val descriptors = request.optJSONArray(key) ?: return emptyList()
        return buildList {
            for (i in 0 until descriptors.length()) {
                val item = descriptors.optJSONObject(i) ?: continue
                val type = item.optString("type", "")
                if (type.isNotEmpty() && type != "public-key") continue
                val id = item.optString("id", "")
                if (id.isNotEmpty()) add(id)
            }
        }
    }

    /**
     * True when this authenticator can satisfy a creation request: it only makes ES256
     * (alg -7) keys, so a `pubKeyCredParams` list that does not offer -7 cannot be served.
     * An absent or empty list imposes no constraint.
     */
    fun supportsEs256(request: JSONObject): Boolean {
        val params = request.optJSONArray("pubKeyCredParams") ?: return true
        if (params.length() == 0) return true
        for (i in 0 until params.length()) {
            val item = params.optJSONObject(i) ?: continue
            val type = item.optString("type", "")
            if (type.isNotEmpty() && type != "public-key") continue
            if (item.has("alg") && item.optInt("alg") == -7) return true
        }
        return false
    }

    // -- Origin resolution --

    fun getPrivilegedOrigin(callingAppInfo: CallingAppInfo, context: Context): String? {
        return try {
            val allowList = context.assets.open("passkeys_privileged_browsers.json")
                .bufferedReader().use { it.readText() }
            val origin = callingAppInfo.getOrigin(allowList)
            if (!origin.isNullOrEmpty()) {
                Log.dev(TAG, "Resolved privileged browser origin: $origin")
                origin.removeSuffix("/")
            } else null
        } catch (expected: Exception) {
            // CallingAppInfo.getOrigin() throws IllegalStateException (not
            // IllegalArgumentException) when the system populated an origin but
            // the caller is not on the allow-list, e.g. a browser this app does
            // not list; the asset read can also fail with IOException. Every
            // such caller is simply not privileged: fall back to its android:
            // origin instead of aborting (or crashing) the whole ceremony.
            // This runs inside a provider flow, so it must never throw.
            Log.dev(TAG, "Caller is not a privileged browser: ${expected.message}")
            null
        }
    }

    fun getAndroidOrigin(callingAppInfo: CallingAppInfo): String {
        // signingInfoCompat can throw for a malformed/system-built
        // CallingAppInfo; the unknown facet is a safe, RP-rejectable fallback
        // and is always preferable to throwing inside a provider flow.
        val signer = runCatching {
            callingAppInfo.signingInfoCompat.apkContentsSigners.firstOrNull()?.toByteArray()
        }.getOrNull()
        return if (signer != null) androidOriginFromSigner(signer) else "android:apk-key-hash:unknown"
    }

    /**
     * WebAuthn's android facet origin for a signing certificate:
     * `android:apk-key-hash:` + base64url(SHA-256 of the certificate). The
     * colon-separated hex form is the assetlinks / privileged-allow-list
     * fingerprint, and a relying party expecting the facet origin rejects it.
     */
    fun androidOriginFromSigner(signerBytes: ByteArray): String =
        "android:apk-key-hash:" + encodeB64Url(MessageDigest.getInstance("SHA-256").digest(signerBytes))

    // -- AuthenticatorData builder --

    fun buildAuthenticatorData(
        rpId: String,
        userPresent: Boolean = true,
        userVerified: Boolean = true,
        backupEligible: Boolean = true,
        backupState: Boolean = true,
        attestedCredentialData: Boolean = false,
        signCount: Int = 0,
    ): ByteArray {
        var flags = 0
        if (userPresent) flags = flags or FLAG_USER_PRESENT
        if (userVerified) flags = flags or FLAG_USER_VERIFIED
        if (backupEligible) flags = flags or FLAG_BACKUP_ELIGIBLE
        if (backupState) flags = flags or FLAG_BACKUP_STATE
        if (attestedCredentialData) flags = flags or FLAG_ATTESTED_CREDENTIAL

        val rpIdHash = MessageDigest.getInstance("SHA-256").digest(rpId.toByteArray())
        return rpIdHash +
            byteArrayOf(flags.toByte()) +
            byteArrayOf(
                (signCount shr SIGN_COUNT_SHIFT_3).toByte(),
                (signCount shr SIGN_COUNT_SHIFT_2).toByte(),
                (signCount shr SIGN_COUNT_SHIFT_1).toByte(),
                signCount.toByte()
            )
    }
}
