package com.vayunmathur.communicate.data.signal.registration

import android.util.Base64
import com.vayunmathur.communicate.data.signal.SignalAuthData
import java.security.SecureRandom
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECKeyPair

/**
 * Generates the client key material for a fresh Signal registration.
 *
 * Real Signal (PushServiceSocket REGISTRATION_PATH, RegistrationSessionRequestBody,
 * AccountAttributes, PreKeyUtil.java:157, libsignal state/kyber_prekey.rs):
 * - Two IdentityKeyPairs (ACI + PNI) via IdentityKeyPair.generate()
 * - Two registrationIds 1..16383 (KeyHelper.generateRegistrationId)
 * - Two signed EC prekeys (ECKeyPair.generate() + identityPriv.calculateSignature(pub.serialize()))
 * - Two Kyber-1024 last-resort prekeys (KEMKeyPair KYBER_1024, pub.serialize() 1569B = 0x08||1568, sig over full 1569B)
 * - Account password (random for Basic e164:password) and UAK 32B
 *
 * Serialization contract (coordinate with crypto teammate who owns SignalPqPreKey):
 * - aci/pniIdentityKey wire = Base64-nopad(IdentityKey.serialize()) 33B (0x05||32) via
 * RegistrationSessionRequestBody.aciIdentityKey
 * - SignedPreKeyEntity wire = {keyId, publicKey: Base64-nopad(ECPublicKey.serialize() 33B), signature}
 * - KyberPreKeyEntity wire = {keyId, publicKey: Base64-nopad(KEMPublicKey.serialize() 1569B with 0x08 tag), signature}
 *   Signature for Kyber is over the FULL 1569B serialize (including 0x08) — stripping tag breaks server verification.
 *   Secret key (3168B raw + tag) is persisted locally only, never uploaded.
 */
class SignalRegistrationKeys private constructor(val authScaffold: SignalAuthData) {
    /** Registration-id bounds and factories for [SignalRegistrationKeys]. */
    companion object {
        private const val MAX_REGISTRATION_ID = 0x3FFF

        fun generate(phoneNumber: String): SignalRegistrationKeys {
            val rng = SecureRandom()
            fun regId() = rng.nextInt(MAX_REGISTRATION_ID) + 1

            val aciIdentity = IdentityKeyPair.generate()
            val pniIdentity = IdentityKeyPair.generate()
            val aciRegId = regId()
            val pniRegId = regId()

            val aciSignedKp = ECKeyPair.generate()
            val pniSignedKp = ECKeyPair.generate()
            val aciSignedId = 1
            val pniSignedId = 1
            // libsignal: identityPriv.calculateSignature(pub.serialize()) — EC pub serialize is 33B (0x05||32)
            val aciSignedSig = aciIdentity.privateKey.calculateSignature(aciSignedKp.publicKey.serialize())
            val pniSignedSig = pniIdentity.privateKey.calculateSignature(pniSignedKp.publicKey.serialize())

            // Kyber-1024 last-resort — use SignalPqPreKey which keeps 0x08 tag intact (coordinate with crypto)
            val aciPq = SignalPqPreKey.generateWithECPrivate(aciIdentity.privateKey, 1)
            val pniPq = SignalPqPreKey.generateWithECPrivate(pniIdentity.privateKey, 1)

            // Account password for Basic e164:password (POST /v1/registration + WS Authorization)
            val password = generatePassword(rng)
            // unidentifiedAccessKey is EXACTLY 16 bytes (UnidentifiedAccess.deriveAccessKeyFrom trims to 16);
            // the server enforces a 16-byte length, so a 32-byte value is rejected with HTTP 422.
            val uak = ByteArray(16).also { rng.nextBytes(it) }

            val scaffold = buildAuthScaffold(
                phoneNumber, aciIdentity, pniIdentity, aciRegId, pniRegId,
                aciSignedKp, pniSignedKp, aciSignedId, pniSignedId, aciSignedSig, pniSignedSig,
                aciPq, pniPq, password, uak,
            )
            return SignalRegistrationKeys(scaffold)
        }

        /** Build the auth scaffold from generated key material. */
        @Suppress("LongParameterList")
        private fun buildAuthScaffold(
            phoneNumber: String,
            aciIdentity: IdentityKeyPair,
            pniIdentity: IdentityKeyPair,
            aciRegId: Int,
            pniRegId: Int,
            aciSignedKp: ECKeyPair,
            pniSignedKp: ECKeyPair,
            aciSignedId: Int,
            pniSignedId: Int,
            aciSignedSig: ByteArray,
            pniSignedSig: ByteArray,
            aciPq: SignalPqPreKey.Generated,
            pniPq: SignalPqPreKey.Generated,
            password: String,
            uak: ByteArray,
        ): SignalAuthData {
            // Identity serialization: IdentityKey.serialize() = 33B (0x05||32), private = 32B raw EC
            val aciPubB64 = b64(aciIdentity.publicKey.serialize())
            val pniPubB64 = b64(pniIdentity.publicKey.serialize())
            // Private key bytes for storage — ECPrivateKey.serialize() is 32B
            val aciPrivB64 = b64(aciIdentity.privateKey.serialize())
            val pniPrivB64 = b64(pniIdentity.privateKey.serialize())

            return SignalAuthData(
                phoneNumber = phoneNumber,
                aci = "",
                pni = "",
                deviceId = 1,
                // Legacy single-identity mirror (ACI) for SignalE2E/RustSignalCrypto compat
                identityPrivateKey = aciPrivB64,
                identityPublicKey = aciPubB64,
                registrationId = aciRegId,
                signedPreKeyId = aciSignedId,
                signedPreKeyPublic = b64(aciSignedKp.publicKey.serialize()),
                signedPreKeyPrivate = b64(aciSignedKp.privateKey.serialize()),
                signedPreKeySignature = b64(aciSignedSig),
                pqLastResortKeyId = aciPq.keyId,
                pqLastResortPublic = b64(aciPq.publicKey),
                pqLastResortSecret = b64(aciPq.secretKey),
                pqLastResortSignature = b64(aciPq.signature),
                kyberPreKeyId = aciPq.keyId,
                kyberPreKeyPublic = b64(aciPq.publicKey),
                kyberPreKeySecret = b64(aciPq.secretKey),
                kyberPreKeySignature = b64(aciPq.signature),
                // Dual-identity (preferred)
                aciIdentityPrivateKey = aciPrivB64,
                aciIdentityPublicKey = aciPubB64,
                pniIdentityPrivateKey = pniPrivB64,
                pniIdentityPublicKey = pniPubB64,
                aciRegistrationId = aciRegId,
                pniRegistrationId = pniRegId,
                aciSignedPreKeyId = aciSignedId,
                aciSignedPreKeyPublic = b64(aciSignedKp.publicKey.serialize()),
                aciSignedPreKeyPrivate = b64(aciSignedKp.privateKey.serialize()),
                aciSignedPreKeySignature = b64(aciSignedSig),
                pniSignedPreKeyId = pniSignedId,
                pniSignedPreKeyPublic = b64(pniSignedKp.publicKey.serialize()),
                pniSignedPreKeyPrivate = b64(pniSignedKp.privateKey.serialize()),
                pniSignedPreKeySignature = b64(pniSignedSig),
                aciPqLastResortKeyId = aciPq.keyId,
                aciPqLastResortPublic = b64(aciPq.publicKey),
                aciPqLastResortSecret = b64(aciPq.secretKey),
                aciPqLastResortSignature = b64(aciPq.signature),
                pniPqLastResortKeyId = pniPq.keyId,
                pniPqLastResortPublic = b64(pniPq.publicKey),
                pniPqLastResortSecret = b64(pniPq.secretKey),
                pniPqLastResortSignature = b64(pniPq.signature),
                password = password,
                unidentifiedAccessKey = b64(uak),
                // live-only SVR2 derivation (MasterKey.deriveRegistrationLock);
                // keep wire-correct null for offline
                registrationLock = null,
                verificationSessionId = null,
                registered = false,
            )
        }

        private fun generatePassword(rng: SecureRandom): String {
            // Signal password is a random client secret for Basic auth; 16 bytes base64 is sufficient and
            // server-agnostic.
            val b = ByteArray(16).also { rng.nextBytes(it) }
            // Use URL-safe-ish alphanumeric: base64 without padding, strip '=' — server accepts any non-empty string.
            return Base64.encodeToString(b, Base64.NO_WRAP or Base64.NO_PADDING)
        }

        private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    }
}
