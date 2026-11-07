package com.vayunmathur.passwords

import android.security.keystore.KeyGenParameterSpec
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStore
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import javax.crypto.KeyGenerator
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Robolectric has no AndroidKeyStore provider, which otherwise blocks every
 * code path that opens the SQLCipher vault (PasswordRepository,
 * DatabaseHelper, KdbxPasswordHelper). Installing this JCA provider gives
 * tests a plain in-memory AES keystore with the same alias semantics; keys
 * never leave the test process.
 */
object FakeAndroidKeyStore {
    private val keys = mutableMapOf<String, SecretKey>()

    fun install() {
        if (Security.getProvider("AndroidKeyStore") == null) {
            Security.addProvider(FakeProvider())
        }
    }

    fun put(alias: String, key: SecretKey) {
        keys[alias] = key
    }

    fun get(alias: String): SecretKey? = keys[alias]

    fun remove(alias: String) {
        keys.remove(alias)
    }

    fun contains(alias: String): Boolean = keys.containsKey(alias)

    fun aliases(): List<String> = keys.keys.toList()

    class FakeProvider : Provider("AndroidKeyStore", 1.0, "in-memory test keystore") {
        init {
            put("KeyStore.AndroidKeyStore", FakeKeyStoreSpi::class.java.name)
            put("KeyGenerator.AES", FakeKeyGeneratorSpi::class.java.name)
        }
    }

    class FakeKeyStoreSpi : KeyStoreSpi() {
        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
        override fun engineContainsAlias(alias: String) = contains(alias)
        override fun engineDeleteEntry(alias: String) = remove(alias)
        override fun engineGetKey(alias: String, password: CharArray?): Key? = get(alias)
        override fun engineIsKeyEntry(alias: String) = contains(alias)
        override fun engineAliases(): Enumeration<String> = Collections.enumeration(aliases())
        override fun engineSize() = aliases().size
        override fun engineGetCertificate(alias: String): Certificate? = null
        override fun engineGetCertificateAlias(cert: Certificate) = null
        override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null
        override fun engineGetCreationDate(alias: String) = Date()
        override fun engineIsCertificateEntry(alias: String) = false
        override fun engineSetCertificateEntry(alias: String, cert: Certificate) = Unit
        override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) {
            if (key is SecretKey) put(alias, key)
        }
        override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<out Certificate>?) = Unit
    }

    class FakeKeyGeneratorSpi : KeyGeneratorSpi() {
        private var alias: String = "test-key"

        override fun engineInit(random: SecureRandom) = Unit
        override fun engineInit(keysize: Int, random: SecureRandom) = Unit
        override fun engineInit(params: java.security.spec.AlgorithmParameterSpec?, random: SecureRandom) {
            if (params is KeyGenParameterSpec) alias = params.keystoreAlias
        }

        override fun engineGenerateKey(): SecretKey {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return SecretKeySpec(bytes, "AES").also { put(alias, it) }
        }
    }

    /** Convenience for tests that obtain a generator through JCA. */
    fun generator(): KeyGenerator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
}
