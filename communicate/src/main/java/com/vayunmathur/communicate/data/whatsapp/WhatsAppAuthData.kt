package com.vayunmathur.communicate.data.whatsapp

import android.content.Context
import androidx.core.content.edit
import com.vayunmathur.communicate.data.KeystoreSecretSealer
import com.vayunmathur.communicate.data.SecretSealer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json as KotlinJson

/**
 * Persistent auth data for the WhatsApp **primary client** (own phone-number registration).
 *
 * Pruned vs. the messages companion port: dropped `advSecretKey`, `accountSignedDeviceIdentity`
 * and `lid` (QR/linked-device only). Extended with the `/v2/register` outputs (`new_jid`,
 * `server_time`, `login` token) needed to bring up the primary Noise session.
 *
 * Persisted under the `communicate_whatsapp_auth` namespace so it never collides with the
 * companion's `whatsapp_auth` in the messages module.
 */
@Serializable
data class WhatsAppAuthData(
    val phoneNumber: String,
    val pushName: String,
    // Primary JID assigned by /v2/register (`new_jid`), e.g. "15551234567@s.whatsapp.net".
    val wid: String,
    // Noise key pair (X25519) — used in every handshake.
    val noisePrivateKey: String, // Base64
    val noisePublicKey: String, // Base64
    // Signal identity key pair (Curve25519) — used for E2E encryption.
    val identityPrivateKey: String, // Base64
    val identityPublicKey: String, // Base64
    // Signal Protocol registration ID.
    val registrationId: Int,
    // Signal signed pre-key.
    val signedPreKeyId: Int,
    val signedPreKeyPublic: String, // Base64
    val signedPreKeyPrivate: String, // Base64
    val signedPreKeySignature: String, // Base64
    // Primary device id is always 0 (the phone itself, not a companion).
    val deviceId: Int = 0,
    // ---- /v2/register outputs ----
    // Server-side login token (`login`) returned by register/security; replayed on subsequent calls.
    val loginToken: String = "",
    // Server clock at registration (`server_time`), seconds.
    val serverTime: Long = 0L,
    // Whether /v2/register returned ok (primary line is live).
    val registered: Boolean = false,
    // Timezone reported to the server on login.
    val timezone: String = "",
    val loggedInAt: Long = 0L,
    val platformType: String = "ANDROID",
    // Vestigial for the primary client (populated only by companion/QR pairing, which we don't use).
    // Kept so the ported message engine compiles; both remain empty for a primary line.
    val lid: String = "",
    val accountSignedDeviceIdentity: String = "",
    // ---- Post-quantum (Kyber1024 / ML-KEM) last-resort prekey (e_pq_* bundle, Phase B 2d) ----
    // All-or-none: emitted together in RegistrationKeys.bundleFields when the id is non-zero.
    val pqLastResortKeyId: Int = 0,
    val pqLastResortPublic: String = "", // Base64 (raw 1568B Kyber1024 pub)
    val pqLastResortSecret: String = "", // Base64 (serialized KEM secret key)
    val pqLastResortSignature: String = "", // Base64 (64B XEdDSA sig over 0x08||pub)
    // Profile name sent to /v2/* for new clients (profile_name, w2.md §2.2).
    val profileName: String = "",
) {
    /** Preference keys and factories for [WhatsAppAuthData]. */
    companion object {
        // Distinct from the companion's "whatsapp_auth" in the messages module.
        private const val PREFS_NAME = "communicate_whatsapp_auth"
        private const val KEY_AUTH_DATA = "auth_data"

        /**
         * Load the sealed (or legacy plaintext) auth JSON.
         *
         * Sealed: unseal then parse; unseal/parse failure returns null
         * (signed-out) and leaves the blob intact. Legacy plaintext: parse
         * first and rewrite sealed ONLY after a successful parse
         * (verify-before-overwrite — a botched rewrite would force
         * re-registration). Idempotent: the second load sees sealed form.
         */
        fun load(context: Context, sealer: SecretSealer = KeystoreSecretSealer.shared): WhatsAppAuthData? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_AUTH_DATA, null) ?: return null
            if (sealer.isSealed(raw)) {
                val json = sealer.unseal(raw) ?: return null
                return try {
                    KotlinJson.decodeFromString<WhatsAppAuthData>(json)
                } catch (ignored: Exception) {
                    null
                }
            }
            val parsed = try {
                KotlinJson.decodeFromString<WhatsAppAuthData>(raw)
            } catch (ignored: Exception) {
                return null
            }
            sealer.seal(raw)?.let { sealed ->
                prefs.edit { putString(KEY_AUTH_DATA, sealed) }
            }
            return parsed
        }

        /** Save the whole auth JSON sealed; on seal failure nothing is written (never plaintext). */
        fun save(context: Context, authData: WhatsAppAuthData, sealer: SecretSealer = KeystoreSecretSealer.shared) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = KotlinJson.encodeToString(authData)
            val sealed = sealer.seal(json) ?: return
            prefs.edit { putString(KEY_AUTH_DATA, sealed) }
        }

        fun clear(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit { remove(KEY_AUTH_DATA) }
        }
    }
}
