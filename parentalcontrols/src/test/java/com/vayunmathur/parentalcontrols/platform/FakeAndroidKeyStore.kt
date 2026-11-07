package com.vayunmathur.parentalcontrols.platform

import android.security.keystore.KeyGenParameterSpec
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.util.Date
import java.util.Enumeration
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * A minimal in-memory `AndroidKeyStore` JCA provider for Robolectric, which has no
 * AndroidKeyStore implementation. It only supports what [com.vayunmathur.library.util.DatabaseHelper]
 * (and therefore the parent PIN) touches: storing one AES key per alias and handing it
 * back for standard SunJCE AES/GCM ciphers. Install once per test class with
 * [installFakeAndroidKeyStore].
 */
object FakeAndroidKeyStore {
    val keys = mutableMapOf<String, SecretKey>()

    @Synchronized
    fun install() {
        if (Security.getProvider("AndroidKeyStore") == null) {
            Security.addProvider(FakeAndroidKeyStoreProvider())
        }
    }
}

class FakeAndroidKeyStoreProvider : Provider("AndroidKeyStore", 1.0, "test-only in-memory AndroidKeyStore") {
    init {
        put("KeyStore.AndroidKeyStore", FakeKeyStoreSpi::class.java.name)
        put("KeyGenerator.AES", FakeKeyGeneratorSpi::class.java.name)
    }
}

class FakeKeyStoreSpi : KeyStoreSpi() {
    override fun engineGetKey(alias: String, password: CharArray?): Key? = FakeAndroidKeyStore.keys[alias]
    override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null
    override fun engineGetCertificate(alias: String): Certificate? = null
    override fun engineGetCreationDate(alias: String): Date = Date()
    override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) {
        FakeAndroidKeyStore.keys[alias] = key as SecretKey
    }
    override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<out Certificate>?) {
        FakeAndroidKeyStore.keys[alias] = SecretKeySpec(key, "AES")
    }
    override fun engineSetCertificateEntry(alias: String, cert: Certificate) = Unit
    override fun engineDeleteEntry(alias: String) { FakeAndroidKeyStore.keys.remove(alias) }
    override fun engineAliases(): Enumeration<String> = java.util.Collections.enumeration(FakeAndroidKeyStore.keys.keys)
    override fun engineContainsAlias(alias: String): Boolean = FakeAndroidKeyStore.keys.containsKey(alias)
    override fun engineSize(): Int = FakeAndroidKeyStore.keys.size
    override fun engineIsKeyEntry(alias: String): Boolean = FakeAndroidKeyStore.keys.containsKey(alias)
    override fun engineIsCertificateEntry(alias: String): Boolean = false
    override fun engineGetCertificateAlias(cert: Certificate): String? = null
    override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
    override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
}

class FakeKeyGeneratorSpi : KeyGeneratorSpi() {
    private var alias: String = "key"
    override fun engineInit(random: SecureRandom) = Unit
    override fun engineInit(keysize: Int, random: SecureRandom) = Unit
    override fun engineInit(params: java.security.spec.AlgorithmParameterSpec?, random: SecureRandom) {
        if (params is KeyGenParameterSpec) alias = params.keystoreAlias
    }
    override fun engineGenerateKey(): SecretKey {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return SecretKeySpec(bytes, "AES").also { FakeAndroidKeyStore.keys[alias] = it }
    }
}
