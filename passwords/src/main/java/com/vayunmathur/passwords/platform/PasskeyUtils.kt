package com.vayunmathur.passwords.platform

import android.content.Context
import com.vayunmathur.library.log.Log
import androidx.credentials.provider.CallingAppInfo
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
        } catch (expected: IllegalArgumentException) {
            Log.dev(TAG, "No privileged browser match: ${expected.message}")
            null
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun getAndroidOrigin(callingAppInfo: CallingAppInfo): String {
        val fingerprint = callingAppInfo.signingInfo
            .apkContentsSigners
            .firstOrNull()
            ?.toByteArray()
            ?.let { MessageDigest.getInstance("SHA-256").digest(it) }
            ?.toHexString(HexFormat.UpperCase)
            ?: "unknown"
        return "android:apk-key-hash:$fingerprint"
    }

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
