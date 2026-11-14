package com.vayunmathur.communicate.data.whatsapp.registration

import android.util.Base64
import com.vayunmathur.communicate.data.whatsapp.WhatsAppAuthData
import com.vayunmathur.communicate.data.whatsapp.e2e.RustWhatsAppCrypto
import com.vayunmathur.communicate.data.whatsapp.e2e.WhatsAppE2E
import java.security.SecureRandom

/**
 * Generates and holds the client key material for a fresh primary registration: the Signal identity
 * keypair, the Noise-static keypair, and a signed pre-key, plus `registrationId`/`signedPreKeyId`.
 * Produces a [WhatsAppAuthData] scaffold to persist and the E2E-bundle wire fields.
 *
 * The E2E bundle fields (`authkey`, `e_ident`, `e_keytype`, `e_regid`, `e_skey_id`, `e_skey_val`,
 * `e_skey_sig`) are **URL-safe base64, no padding** — matching the APK's `C34040Euf`→`A04`→`DIj.A0w`
 * (flag 11). AuthData stores the same keys as STANDARD base64 for internal use, so [bundleFields]
 * decodes and re-encodes to URL-safe base64 for the wire.
 */
class RegistrationKeys private constructor(
    val authScaffold: WhatsAppAuthData,
) {
    /** URL-safe-base64 E2E bundle fields for `/v2/ endpoints` (from the freshly-generated scaffold). */
    fun registerBundleFields(): Map<String, String> = bundleFields(authScaffold)

    /** Registration key-bundle constants (whether the PQ bundle is attached). */
    companion object {
        /**
         * DEFAULT **false** — do not attach the post-quantum `e_pq_last_resort_*` bundle to the
         * `/v2` registration request. Restores the last live-verified param set (base commit
         * `33dd602`, which returned `status:ok` with only the classic 7-field bundle); the PQ bundle
         * added in `e7e120` was never validated live and its server acceptance is User-Agent-gated.
         * Flip to `true` to generate + attach it (bundleFields still enforces all-or-none).
         */
        const val EMIT_PQ_LAST_RESORT = false
        private const val MAX_REGISTRATION_ID = 0x3FFF
        private const val BYTE_BITS = 8
        private const val INT32_LEN = 4
        private const val INT24_LEN = 3

        fun generate(phoneNumber: String): RegistrationKeys {
            val rng = SecureRandom()

            val identity = RustWhatsAppCrypto.generateKeyPairSplit()
            val noise = RustWhatsAppCrypto.generateKeyPairSplit()
            val signedPreKey = RustWhatsAppCrypto.generateKeyPairSplit()

            val registrationId = rng.nextInt(MAX_REGISTRATION_ID) + 1
            val signedPreKeyId = 1

            val signature = WhatsAppE2E.signSignedPreKey(identity.privateKey, signedPreKey.publicKey)

            // Post-quantum last-resort prekey (Phase B 2d). DEFAULT OFF ([EMIT_PQ_LAST_RESORT]):
            // this e_pq_* bundle was added in `e7e120` AFTER the last live-verified registration
            // (whatsapp-documentation.md §2 sent only the classic 7-field bundle) and was never
            // validated live; server PQ expectation is User-Agent-gated, so an unexpected/mismatched
            // e_pq_* can draw bad_param. When off, the fields stay empty and bundleFields omits
            // e_pq_* entirely (all-or-none), matching the verified param set. Best-effort even when
            // enabled: a KEM/native-signer failure also leaves the fields empty.
            val pq = if (EMIT_PQ_LAST_RESORT) {
                try {
                    WhatsAppPqPreKey.generate(identity.privateKey, WhatsAppRegistrationConstants.PQ_LAST_RESORT_KEY_ID)
                } catch (ignored: Throwable) {
                    null
                }
            } else {
                null
            }

            val scaffold = WhatsAppAuthData(
                phoneNumber = phoneNumber,
                pushName = "",
                wid = "", // filled from `new_jid` after /v2/register
                noisePrivateKey = b64(noise.privateKey),
                noisePublicKey = b64(noise.publicKey),
                identityPrivateKey = b64(identity.privateKey),
                identityPublicKey = b64(identity.publicKey),
                registrationId = registrationId,
                signedPreKeyId = signedPreKeyId,
                signedPreKeyPublic = b64(signedPreKey.publicKey),
                signedPreKeyPrivate = b64(signedPreKey.privateKey),
                signedPreKeySignature = b64(signature),
                deviceId = 0,
                pqLastResortKeyId = pq?.keyId ?: 0,
                pqLastResortPublic = pq?.let { b64(it.publicKey) } ?: "",
                pqLastResortSecret = pq?.let { b64(it.secretKey) } ?: "",
                pqLastResortSignature = pq?.let { b64(it.signature) } ?: "",
            )
            return RegistrationKeys(scaffold)
        }

        private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
        private fun dec(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

        /**
         * The `/v2/ endpoints` E2E key bundle, URL-safe-base64 (A04), rebuilt from persisted [WhatsAppAuthData]
         * so the same values are reused across the code→register calls.
         */
        fun bundleFields(auth: WhatsAppAuthData): Map<String, String> {
            val map = linkedMapOf(
                "e_regid" to RegEncoding.b64Url(intBe(auth.registrationId, INT32_LEN)),
                "e_keytype" to RegEncoding.b64Url(byteArrayOf(WhatsAppRegistrationConstants.KEY_TYPE_CURVE25519)),
                "e_ident" to RegEncoding.b64Url(dec(auth.identityPublicKey)),
                "e_skey_id" to RegEncoding.b64Url(intBe(auth.signedPreKeyId, INT24_LEN)),
                "e_skey_val" to RegEncoding.b64Url(dec(auth.signedPreKeyPublic)),
                "e_skey_sig" to RegEncoding.b64Url(dec(auth.signedPreKeySignature)),
                "authkey" to RegEncoding.b64Url(dec(auth.noisePublicKey)),
            )
            // Post-quantum last-resort prekey — all-or-none (w2.md §2.1). Only emit when the full
            // triple is present; otherwise the server returns bad_param:e_pq_last_resort_*.
            if (auth.pqLastResortKeyId != 0 &&
                auth.pqLastResortPublic.isNotEmpty() &&
                auth.pqLastResortSignature.isNotEmpty()
            ) {
                map["e_pq_last_resort_id"] = RegEncoding.b64Url(intBe(auth.pqLastResortKeyId, INT24_LEN))
                map["e_pq_last_resort_val"] = RegEncoding.b64Url(dec(auth.pqLastResortPublic))
                map["e_pq_last_resort_sig"] = RegEncoding.b64Url(dec(auth.pqLastResortSignature))
            }
            return map
        }

        /** Big-endian encode [value] into [len] bytes (len ≤ 4). */
        private fun intBe(value: Int, len: Int): ByteArray {
            val out = ByteArray(len)
            for (i in 0 until len) {
                out[len - 1 - i] = (value ushr (BYTE_BITS * i)).toByte()
            }
            return out
        }
    }
}
