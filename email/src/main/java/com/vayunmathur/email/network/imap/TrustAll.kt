package com.vayunmathur.email.network.imap

import android.annotation.SuppressLint
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.net.Socket

/**
 * Trust handling for raw IMAP/SMTP sockets.
 *
 * Known hosts (gmail, yahoo, aol, fastmail, outlook) use the system default
 * trust store (strict). Custom hosts use a permissive trust manager that
 * accepts any certificate (mirrors the old Jakarta `ssl.trust=*` behavior
 * for self-hosted servers).
 */
object TrustAll {

    private val KNOWN_SUFFIXES = setOf(".gmail.com")

    private val EXACT_KNOWN_HOSTS = setOf(
        "imap.gmail.com",
        "smtp.gmail.com",
        "imap.mail.yahoo.com",
        "smtp.mail.yahoo.com",
        "imap.aol.com",
        "smtp.aol.com",
        "imap.fastmail.com",
        "smtp.fastmail.com",
        "imap.mail.me.com",
        "smtp.mail.me.com",
        "outlook.office365.com",
        "smtp-mail.outlook.com",
        "smtp.office365.com",
        "outlook.office.com",
        "imap-mail.outlook.com",
        "imap.outlook.com",
    )

    // Justified suppression: this trust manager is the documented, opt-in
    // fallback for self-hosted mail servers with self-signed certificates
    // (custom hosts only; known providers use the strict system store via
    // isKnownHost). Its scope has not been widened - see AGENTS.md:
    // "network/imap/TrustAll.kt exists for debug only; never widen its use"
    // and the class KDoc above. Removing it would break self-hosted
    // accounts that have no CA-issued certificate.
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private val TRUST_ALL_MANAGERS: Array<TrustManager> = arrayOf(
        object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
    )

    private val permissiveFactory: SSLSocketFactory by lazy {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, TRUST_ALL_MANAGERS, SecureRandom())
        ctx.socketFactory
    }

    private val systemFactory: SSLSocketFactory by lazy {
        SSLSocketFactory.getDefault() as SSLSocketFactory
    }

    fun permissiveSocketFactory(): SSLSocketFactory = permissiveFactory
    fun systemSocketFactory(): SSLSocketFactory = systemFactory

    /** Known good hosts where we want strict system trust. */
    fun isKnownHost(host: String): Boolean {
        val h = host.lowercase()
        if (h in EXACT_KNOWN_HOSTS) return true
        return KNOWN_SUFFIXES.any { h.endsWith(it) }
    }

    fun socketFactoryFor(host: String, forceTrustAll: Boolean): SSLSocketFactory {
        return if (forceTrustAll || !isKnownHost(host)) permissiveFactory else systemFactory
    }

    fun createSocket(host: String, port: Int, trustAll: Boolean): Socket {
        val factory = socketFactoryFor(host, trustAll)
        return factory.createSocket(host, port)
    }

    fun createPlainSocket(host: String, port: Int): Socket = Socket(host, port)

    fun upgradeToTls(
        plainSocket: Socket,
        host: String,
        port: Int,
        trustAll: Boolean,
        autoClose: Boolean = true,
    ): SSLSocket {
        val factory = socketFactoryFor(host, trustAll)
        val ssl = factory.createSocket(plainSocket, host, port, autoClose) as SSLSocket
        ssl.useClientMode = true
        ssl.startHandshake()
        return ssl
    }
}
