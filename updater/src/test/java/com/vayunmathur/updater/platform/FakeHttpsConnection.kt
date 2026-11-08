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
    fun set(key: String, value: String) {
        val cls = Class.forName("android.os.SystemProperties")
        cls.getMethod("set", String::class.java, String::class.java).invoke(null, key, value)
    }

    fun get(key: String): String {
        val cls = Class.forName("android.os.SystemProperties")
        return cls.getMethod("get", String::class.java).invoke(null, key) as? String ?: ""
    }

    /**
     * Every property a test can write through [set]/[setRunningBuild]. Snapshot in `@BeforeTest`
     * and hand back to [restore] in `@AfterTest`: Robolectric keeps `SystemProperties` (and the
     * `ro.build.fingerprint` it seeds as `robolectric`) alive across test classes sharing a worker
     * JVM, so a leaked `Modern/...` fingerprint makes compose rule setup in later classes take the
     * Espresso path and throw "cannot be run from the main thread".
     */
    private val snapshotKeys = listOf(
        "ro.build.version.incremental",
        "ro.build.date.utc",
        "ro.product.device",
        "ro.build.fingerprint",
        "ro.maos.ota.server",
    )

    fun snapshot(): Map<String, String> = snapshotKeys.associateWith(::get)

    fun restore(snapshot: Map<String, String>) {
        snapshot.forEach { (key, value) -> set(key, value) }
    }

    fun setRunningBuild(build: String = "2026090500", dateUtcSeconds: Long = 1000L, device: String = "shiba") {
        set("ro.build.version.incremental", build)
        set("ro.build.date.utc", dateUtcSeconds.toString())
        set("ro.product.device", device)
        set("ro.build.fingerprint", "Modern/$device/$device:16/$build/user/release-keys")
    }
}
