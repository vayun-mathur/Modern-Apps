package com.vayunmathur.communicate.data.signal

import android.content.Context
import androidx.core.content.edit
import com.vayunmathur.communicate.data.KeystoreSecretSealer
import com.vayunmathur.communicate.data.SecretSealer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json as KotlinJson

/**
 * Persistent auth data for the Signal primary client.
 *
 * Real Signal registration (see PushServiceSocket verification-session and registration paths,
 * RegistrationApiV2, RegistrationSessionRequestBody, AccountAttributes):
 * - Two identity keypairs (ACI and PNI) each IdentityKeyPair, uploaded as base64 of serialized 33B.
 * - Two registrationIds in AccountAttributes.
 * - Account password for Basic auth on registration and WebSocket.
 * - Unidentified-access key 32B.
 * - Per-identity signed EC prekeys and Kyber-1024 last-resort prekeys (1569B with tag).
 *
 * Persisted under communicate_signal_auth. Keeps legacy single-identity fields for compat.
 */
@Serializable
data class SignalAuthData(
    val phoneNumber: String,
    val aci: String = "",
    val pni: String = "",
    val deviceId: Int = 1,
    val identityPrivateKey: String = "",
    val identityPublicKey: String = "",
    val registrationId: Int = 0,
    val signedPreKeyId: Int = 0,
    val signedPreKeyPublic: String = "",
    val signedPreKeyPrivate: String = "",
    val signedPreKeySignature: String = "",
    val pqLastResortKeyId: Int = 0,
    val pqLastResortPublic: String = "",
    val pqLastResortSecret: String = "",
    val pqLastResortSignature: String = "",
    val kyberPreKeyId: Int = 0,
    val kyberPreKeyPublic: String = "",
    val kyberPreKeySecret: String = "",
    val kyberPreKeySignature: String = "",
    val aciIdentityPrivateKey: String = "",
    val aciIdentityPublicKey: String = "",
    val pniIdentityPrivateKey: String = "",
    val pniIdentityPublicKey: String = "",
    val aciRegistrationId: Int = 0,
    val pniRegistrationId: Int = 0,
    val aciSignedPreKeyId: Int = 0,
    val aciSignedPreKeyPublic: String = "",
    val aciSignedPreKeyPrivate: String = "",
    val aciSignedPreKeySignature: String = "",
    val pniSignedPreKeyId: Int = 0,
    val pniSignedPreKeyPublic: String = "",
    val pniSignedPreKeyPrivate: String = "",
    val pniSignedPreKeySignature: String = "",
    val aciPqLastResortKeyId: Int = 0,
    val aciPqLastResortPublic: String = "",
    val aciPqLastResortSecret: String = "",
    val aciPqLastResortSignature: String = "",
    val pniPqLastResortKeyId: Int = 0,
    val pniPqLastResortPublic: String = "",
    val pniPqLastResortSecret: String = "",
    val pniPqLastResortSignature: String = "",
    val password: String = "",
    val unidentifiedAccessKey: String = "",
    val registrationLock: String? = null,
    val verificationSessionId: String? = null,
    val registered: Boolean = false,
    val profileName: String = "",
) {
    fun effectiveAciPrivate(): String = aciIdentityPrivateKey.ifEmpty { identityPrivateKey }
    fun effectiveAciPublic(): String = aciIdentityPublicKey.ifEmpty { identityPublicKey }
    fun effectiveAciRegId(): Int = if (aciRegistrationId != 0) aciRegistrationId else registrationId
    fun effectiveAciSignedId(): Int = if (aciSignedPreKeyId != 0) aciSignedPreKeyId else signedPreKeyId
    fun effectiveAciSignedPub(): String = aciSignedPreKeyPublic.ifEmpty { signedPreKeyPublic }
    fun effectiveAciSignedPriv(): String = aciSignedPreKeyPrivate.ifEmpty { signedPreKeyPrivate }
    fun effectiveAciSignedSig(): String = aciSignedPreKeySignature.ifEmpty { signedPreKeySignature }
    fun effectiveAciPqId(): Int = if (aciPqLastResortKeyId != 0) aciPqLastResortKeyId else pqLastResortKeyId
    fun effectiveAciPqPub(): String = aciPqLastResortPublic.ifEmpty { pqLastResortPublic }
    fun effectiveAciPqSec(): String = aciPqLastResortSecret.ifEmpty { pqLastResortSecret }
    fun effectiveAciPqSig(): String = aciPqLastResortSignature.ifEmpty { pqLastResortSignature }

    /** Preference keys and factories for [SignalAuthData]. */
    companion object {
        private const val PREFS_NAME = "communicate_signal_auth"
        private const val KEY_AUTH_DATA = "auth_data"

        /**
         * Load the sealed (or legacy plaintext) auth JSON. This store holds
         * identity-key material, so failure handling is conservative:
         *
         * Sealed: unseal then parse; unseal/parse failure returns null
         * (behaves as 'not registered') and the stored blob is left intact
         * for retry on the next launch — never overwritten or deleted.
         * Legacy plaintext: parse first and rewrite sealed ONLY after a
         * successful parse (verify-before-overwrite). Idempotent.
         */
        fun load(context: Context, sealer: SecretSealer = KeystoreSecretSealer.shared): SignalAuthData? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_AUTH_DATA, null) ?: return null
            if (sealer.isSealed(raw)) {
                val json = sealer.unseal(raw) ?: return null
                return try {
                    KotlinJson.decodeFromString<SignalAuthData>(json)
                } catch (_: Exception) {
                    null
                }
            }
            val parsed = try {
                KotlinJson.decodeFromString<SignalAuthData>(raw)
            } catch (_: Exception) {
                return null
            }
            sealer.seal(raw)?.let { sealed ->
                prefs.edit { putString(KEY_AUTH_DATA, sealed) }
            }
            return parsed
        }

        /** Save the whole auth JSON sealed; on seal failure nothing is written (never plaintext). */
        fun save(context: Context, authData: SignalAuthData, sealer: SecretSealer = KeystoreSecretSealer.shared) {
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
