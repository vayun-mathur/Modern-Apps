package com.vayunmathur.updater.platform

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.security.Principal
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

/** An in-memory [HttpsURLConnection] so download/check tests never touch the network. */
class FakeHttpsConnection(
    url: URL,
    private val code: Int,
    private val body: ByteArray = ByteArray(0),
    private val declaredLength: Long = body.size.toLong(),
    private val failOnInput: Boolean = false,
) : HttpsURLConnection(url) {

    var disconnected: Boolean = false
        private set

    override fun getResponseCode(): Int = code

    override fun getInputStream(): InputStream {
        if (failOnInput) throw IOException("fake input failure")
        return ByteArrayInputStream(body)
    }

    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun getContentLengthLong(): Long = declaredLength

    override fun connect() = Unit

    override fun disconnect() {
        disconnected = true
    }

    override fun usingProxy(): Boolean = false

    override fun getCipherSuite(): String = "TLS_FAKE_SUITE"

    override fun getLocalCertificates(): Array<Certificate> = emptyArray()

    override fun getServerCertificates(): Array<Certificate> = emptyArray()

    override fun getPeerPrincipal(): Principal = Principal { "fake-peer" }
}

/** Sets a hidden `android.os.SystemProperties` value the way a real build would report it. */
object TestSysProps {
    private val managedKeys = listOf(
        "ro.build.version.incremental",
        "ro.build.date.utc",
        "ro.product.device",
        "ro.build.fingerprint",
        "ro.maos.ota.server",
    )
    private const val backupPrefix = "updater.testsysprops.orig."
    private const val backupDone = "updater.testsysprops.backupDone"

    fun set(key: String, value: String) {
        backupOnce()
        sysPropsSet(key, value)
    }

    /**
     * Restores every managed key to the value it held before the first [set] call in this
     * JVM. Robolectric's `android.os.SystemProperties` state is JVM-global, so without this
     * a fake `ro.build.fingerprint` leaks into later test classes' sandboxes, where it
     * changes `Build.FINGERPRINT` and breaks Compose's Robolectric fingerprint check.
     * Call from `@AfterTest` in every test that mutates system properties.
     */
    fun reset() {
        for (key in managedKeys) {
            sysPropsSet(key, System.getProperty(backupPrefix + key) ?: "")
        }
    }

    private fun backupOnce() {
        if (System.getProperty(backupDone) == "true") return
        for (key in managedKeys) {
            System.setProperty(backupPrefix + key, sysPropsGet(key) ?: "")
        }
        System.setProperty(backupDone, "true")
    }

    private fun sysPropsSet(key: String, value: String) {
        val cls = Class.forName("android.os.SystemProperties")
        cls.getMethod("set", String::class.java, String::class.java).invoke(null, key, value)
    }

    private fun sysPropsGet(key: String): String? {
        val cls = Class.forName("android.os.SystemProperties")
        return cls.getMethod("get", String::class.java).invoke(null, key) as String?
    }

    fun setRunningBuild(build: String = "2026090500", dateUtcSeconds: Long = 1000L, device: String = "shiba") {
        set("ro.build.version.incremental", build)
        set("ro.build.date.utc", dateUtcSeconds.toString())
        set("ro.product.device", device)
        set("ro.build.fingerprint", "Modern/$device/$device:16/$build/user/release-keys")
    }
}
