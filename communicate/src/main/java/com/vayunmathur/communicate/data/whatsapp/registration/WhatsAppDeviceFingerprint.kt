package com.vayunmathur.communicate.data.whatsapp.registration

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import java.security.SecureRandom
import java.util.UUID

/**
 * Stable per-install device identifiers required by the `/v2/ endpoints. Generated ONCE and
 * persisted, then replayed on every registration call so retries/verification line up server-side.
 *
 * Encodings mirror the APK (`C34244EyE`, see [RegEncoding]):
 *  - [fdid]  → `fdid`  : the UUID STRING as-is (A01 plain).
 *  - [expid] → `expid` : a UUID whose 16 bytes are URL-safe-base64 encoded (A03).
 *  - [recoveryToken] → `id` : raw bytes, percent-encoded (A05).
 *  - [backupToken]   → `backup_token` : raw bytes, percent-encoded (A05).
 *  - [attestationKey] keys the optional `H` HMAC.
 */
class WhatsAppDeviceFingerprint private constructor(
    val fdid: String,
    val expid: String,
    val recoveryToken: ByteArray,
    val backupToken: ByteArray,
    val attestationKey: ByteArray,
) {
    /** Preference keys and factories for the WhatsApp device fingerprint. */
    companion object {
        private const val PREFS = "communicate_whatsapp_fingerprint"
        private const val K_FDID = "fdid"
        private const val K_EXPID = "expid_uuid"
        private const val K_ID = "recovery_id"
        private const val K_BACKUP = "backup_token"
        private const val K_ATTEST = "attest_key"
        private const val RECOVERY_TOKEN_BYTES = 16
        private const val BACKUP_TOKEN_BYTES = 20
        private const val ATTESTATION_KEY_BYTES = 32

        fun getOrCreate(context: Context): WhatsAppDeviceFingerprint {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val rng = SecureRandom()

            fun bytes(key: String, len: Int): ByteArray {
                val existing = prefs.getString(key, null)
                if (existing != null) return Base64.decode(existing, Base64.NO_WRAP)
                val b = ByteArray(len).also { rng.nextBytes(it) }
                prefs.edit { putString(key, Base64.encodeToString(b, Base64.NO_WRAP)) }
                return b
            }

            fun uuid(key: String): String =
                prefs.getString(key, null) ?: UUID.randomUUID().toString().also {
                    prefs.edit { putString(key, it) }
                }

            return WhatsAppDeviceFingerprint(
                fdid = uuid(K_FDID),
                expid = uuid(K_EXPID),
                recoveryToken = bytes(K_ID, RECOVERY_TOKEN_BYTES),
                backupToken = bytes(K_BACKUP, BACKUP_TOKEN_BYTES),
                attestationKey = bytes(K_ATTEST, ATTESTATION_KEY_BYTES),
            )
        }

        fun clear(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { clear() }
        }
    }
}
