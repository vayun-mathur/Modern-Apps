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

    fun setRunningBuild(build: String = "2026090500", dateUtcSeconds: Long = 1000L, device: String = "shiba") {
        set("ro.build.version.incremental", build)
        set("ro.build.date.utc", dateUtcSeconds.toString())
        set("ro.product.device", device)
        set("ro.build.fingerprint", "Modern/$device/$device:16/$build/user/release-keys")
    }
}
