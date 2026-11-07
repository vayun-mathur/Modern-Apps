package com.vayunmathur.uniflix.platform

import android.security.keystore.KeyGenParameterSpec
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * In-memory stand-in for the AndroidKeyStore JCE provider, which Robolectric
 * does not ship. Lets DatabaseHelper generate/store its passphrase key on the
 * JVM so ViewModels backed by the (still unopenable) SQLCipher database can
 * at least be constructed and run their non-database paths.
 */
object FakeAndroidKeyStore {
    private val keys = Collections.synchronizedMap(HashMap<String, Key>())

    fun install() {
        if (Security.getProvider("AndroidKeyStore") == null) {
            Security.addProvider(FakeProvider())
        }
    }

    internal fun put(alias: String, key: Key) {
        keys[alias] = key
    }

    internal fun get(alias: String): Key? = keys[alias]
    internal fun remove(alias: String) {
        keys.remove(alias)
    }

    internal fun contains(alias: String): Boolean = keys.containsKey(alias)
    internal fun aliases(): List<String> = synchronized(keys) { keys.keys.toList() }
}

class FakeProvider : Provider("AndroidKeyStore", 1.0, "fake for tests") {
    init {
        put("KeyStore.AndroidKeyStore", FakeKeyStoreSpi::class.java.name)
        put("KeyGenerator.AES", FakeKeyGeneratorSpi::class.java.name)
    }
}

class FakeKeyStoreSpi : KeyStoreSpi() {
    override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
    override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
    override fun engineGetKey(alias: String, password: CharArray?): Key? = FakeAndroidKeyStore.get(alias)
    override fun engineContainsAlias(alias: String): Boolean = FakeAndroidKeyStore.contains(alias)
    override fun engineDeleteEntry(alias: String) = FakeAndroidKeyStore.remove(alias)
    override fun engineAliases(): Enumeration<String> =
        Collections.enumeration(FakeAndroidKeyStore.aliases())
    override fun engineSize(): Int = FakeAndroidKeyStore.aliases().size
    override fun engineIsKeyEntry(alias: String): Boolean = FakeAndroidKeyStore.contains(alias)
    override fun engineIsCertificateEntry(alias: String): Boolean = false
    override fun engineGetCertificate(alias: String): Certificate? = null
    override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null
    override fun engineGetCertificateAlias(cert: Certificate): String? = null
    override fun engineGetCreationDate(alias: String): Date = Date(0)
    override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) {
        FakeAndroidKeyStore.put(alias, key)
    }
    override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<out Certificate>?) {
        FakeAndroidKeyStore.put(alias, SecretKeySpec(key, "AES"))
    }
    override fun engineSetCertificateEntry(alias: String, cert: Certificate) = Unit
}

class FakeKeyGeneratorSpi : KeyGeneratorSpi() {
    private var alias: String = "db_no_auth_key"

    override fun engineInit(random: SecureRandom?) = Unit
    override fun engineInit(params: java.security.spec.AlgorithmParameterSpec?, random: SecureRandom?) {
        if (params is KeyGenParameterSpec) alias = params.keystoreAlias
    }
    override fun engineInit(keysize: Int, random: SecureRandom?) = Unit

    override fun engineGenerateKey(): SecretKey {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val key = SecretKeySpec(bytes, "AES")
        FakeAndroidKeyStore.put(alias, key)
        return key
    }
}
