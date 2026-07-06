package com.vayunmathur.e2ee

import org.bouncycastle.jcajce.SecretKeyWithEncapsulation
import org.bouncycastle.jcajce.spec.KEMExtractSpec
import org.bouncycastle.jcajce.spec.KEMGenerateSpec
import org.bouncycastle.jcajce.spec.MLDSAParameterSpec
import org.bouncycastle.jcajce.spec.MLKEMParameterSpec
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Security
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyGenerator

/**
 * Post-quantum crypto primitives (BouncyCastle), used by the Office app:
 *   - **ML-KEM-768** for encryption (key encapsulation + AES-256-GCM = hybrid PKE, any length), and
 *   - **ML-DSA-65** for signatures (authenticating edits and owner roster changes).
 *
 * A device's public identity is a [bundle] of its ML-KEM and ML-DSA public keys. FindFamily keeps
 * using RSA (see [E2ee]); only Office uses this.
 */
object Pqc {
    private const val PROVIDER = "BC"

    @Volatile private var registered = false

    /** Ensures the full BouncyCastle provider (with PQC) is installed, replacing Android's stub BC. */
    fun ensureProvider() {
        if (registered) return
        synchronized(this) {
            if (registered) return
            val existing = Security.getProvider(PROVIDER)
            if (existing == null || existing !is BouncyCastleProvider) {
                Security.removeProvider(PROVIDER)
                Security.addProvider(BouncyCastleProvider())
            }
            registered = true
        }
    }

    fun generateKem(): KeyPair {
        ensureProvider()
        val kpg = KeyPairGenerator.getInstance("ML-KEM", PROVIDER)
        kpg.initialize(MLKEMParameterSpec.ml_kem_768)
        return kpg.generateKeyPair()
    }

    fun generateDsa(): KeyPair {
        ensureProvider()
        val kpg = KeyPairGenerator.getInstance("ML-DSA", PROVIDER)
        kpg.initialize(MLDSAParameterSpec.ml_dsa_65)
        return kpg.generateKeyPair()
    }

    fun kemPublic(der: ByteArray): PublicKey =
        KeyFactory.getInstance("ML-KEM", PROVIDER).generatePublic(X509EncodedKeySpec(der))
    fun kemPrivate(der: ByteArray): PrivateKey =
        KeyFactory.getInstance("ML-KEM", PROVIDER).generatePrivate(PKCS8EncodedKeySpec(der))
    fun dsaPublic(der: ByteArray): PublicKey =
        KeyFactory.getInstance("ML-DSA", PROVIDER).generatePublic(X509EncodedKeySpec(der))
    fun dsaPrivate(der: ByteArray): PrivateKey =
        KeyFactory.getInstance("ML-DSA", PROVIDER).generatePrivate(PKCS8EncodedKeySpec(der))

    // --- Public-key bundle = [4B kemLen][kemPub][dsaPub] ---

    fun bundle(kemPub: ByteArray, dsaPub: ByteArray): ByteArray = lenPrefix(kemPub, dsaPub)

    private fun splitBundle(b: ByteArray): Pair<ByteArray, ByteArray> = unLenPrefix(b)

    /** Encrypts to a recipient bundle: ML-KEM encapsulate → AES-256-GCM. Layout `[4B encapLen][encap][aes]`. */
    fun encryptTo(recipientBundle: ByteArray, plaintext: ByteArray): ByteArray {
        ensureProvider()
        val (kemPub, _) = splitBundle(recipientBundle)
        val kg = KeyGenerator.getInstance("ML-KEM", PROVIDER)
        kg.init(KEMGenerateSpec(kemPublic(kemPub), "AES"), SecureRandom())
        val enc = kg.generateKey() as SecretKeyWithEncapsulation
        val ct = E2ee.aesEncrypt(enc.encoded, plaintext)
        return lenPrefix(enc.encapsulation, ct)
    }

    /** Decrypts data from [encryptTo] with this identity's ML-KEM private key. */
    fun decrypt(kemPrivate: PrivateKey, data: ByteArray): ByteArray {
        ensureProvider()
        val (encap, ct) = unLenPrefix(data)
        val kg = KeyGenerator.getInstance("ML-KEM", PROVIDER)
        kg.init(KEMExtractSpec(kemPrivate, encap, "AES"))
        val sk = kg.generateKey() as SecretKeyWithEncapsulation
        return E2ee.aesDecrypt(sk.encoded, ct)
    }

    fun signWith(dsaPrivate: PrivateKey, data: ByteArray): ByteArray {
        ensureProvider()
        return Signature.getInstance("ML-DSA", PROVIDER).run { initSign(dsaPrivate); update(data); sign() }
    }

    fun verify(bundle: ByteArray, data: ByteArray, signature: ByteArray): Boolean = runCatching {
        ensureProvider()
        val (_, dsaPub) = splitBundle(bundle)
        Signature.getInstance("ML-DSA", PROVIDER).run { initVerify(dsaPublic(dsaPub)); update(data); verify(signature) }
    }.getOrDefault(false)

    /** Verification security code from two public bundles (identical on both devices when unmodified). */
    fun securityCode(myBundle: ByteArray, peerBundle: ByteArray): String =
        SecurityCode.compute(myBundle, peerBundle)

    // --- helpers ---

    private fun lenPrefix(a: ByteArray, b: ByteArray): ByteArray {
        val out = ByteArray(4 + a.size + b.size)
        out[0] = (a.size ushr 24).toByte(); out[1] = (a.size ushr 16).toByte()
        out[2] = (a.size ushr 8).toByte(); out[3] = a.size.toByte()
        a.copyInto(out, 4); b.copyInto(out, 4 + a.size)
        return out
    }

    private fun unLenPrefix(x: ByteArray): Pair<ByteArray, ByteArray> {
        val len = ((x[0].toInt() and 0xFF) shl 24) or ((x[1].toInt() and 0xFF) shl 16) or
            ((x[2].toInt() and 0xFF) shl 8) or (x[3].toInt() and 0xFF)
        return x.copyOfRange(4, 4 + len) to x.copyOfRange(4 + len, x.size)
    }
}
