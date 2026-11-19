package com.vayunmathur.passwords.platform

import android.content.pm.Signature
import android.content.pm.SigningInfo
import androidx.credentials.provider.CallingAppInfo
import java.io.File
import java.security.KeyStore

/**
 * Builds a [CallingAppInfo] with a real signing certificate. Neither
 * Robolectric nor the SDK stubs can mint a [SigningInfo] with contents
 * through public APIs, so the framework's `SigningDetails` constructor is
 * reached reflectively — the same objects the system builds for an installed
 * app. The certificate is a real X.509 cert (from the JDK trust store, purely
 * as well-formed DER) because `SigningDetails` validates its signatures.
 */
object CallingAppInfoFactory {

    val certBytes: ByteArray by lazy {
        val store = KeyStore.getInstance(KeyStore.getDefaultType())
        File(System.getProperty("java.home"), "lib/security/cacerts").inputStream().use {
            store.load(it, "changeit".toCharArray())
        }
        val alias = store.aliases().nextElement()
        store.getCertificate(alias).encoded
    }

    fun signingInfo(): SigningInfo {
        val detailsClass = Class.forName("android.content.pm.SigningDetails")
        val detailsCtor = detailsClass.getDeclaredConstructor(
            Array<Signature>::class.java,
            Int::class.javaPrimitiveType,
        )
        detailsCtor.isAccessible = true
        val details = detailsCtor.newInstance(arrayOf(Signature(certBytes)), 3)
        val infoCtor = SigningInfo::class.java.getDeclaredConstructor(detailsClass)
        infoCtor.isAccessible = true
        return infoCtor.newInstance(details) as SigningInfo
    }

    fun callingAppInfo(packageName: String): CallingAppInfo =
        CallingAppInfo(packageName, signingInfo())

    fun callingAppInfo(packageName: String, origin: String): CallingAppInfo =
        CallingAppInfo(packageName, signingInfo(), origin)
}
