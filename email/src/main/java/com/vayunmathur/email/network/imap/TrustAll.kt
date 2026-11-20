package com.vayunmathur.email.network.imap

import android.annotation.SuppressLint
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.net.Socket

/**
 * Trust handling for raw IMAP/SMTP sockets.
 *
 * Default (strict) for ALL hosts — known providers and custom hosts alike:
 * platform trust store plus hostname verification via
 * [SSLParameters.endpointIdentificationAlgorithm] = "HTTPS".
 *
 * The permissive factory is reachable ONLY when the caller passes an
 * explicit per-account opt-in (`trustAll = true`, sourced from
 * `EmailAccount.allowInsecureCertificates`). It must never be selected
 * automatically because a host is unknown/custom. See AGENTS.md:
 * "network/imap/TrustAll.kt exists for debug only; never widen its use".
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

    // Permissive manager retained only for the explicit per-account opt-in
    // (self-hosted servers with self-signed certs) and debug/tests.
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

    /** Informational only — no longer drives trust decisions. */
    fun isKnownHost(host: String): Boolean {
        val h = host.lowercase()
        if (h in EXACT_KNOWN_HOSTS) return true
        return KNOWN_SUFFIXES.any { h.endsWith(it) }
    }

    fun socketFactoryFor(forceTrustAll: Boolean): SSLSocketFactory {
        // Strict by default for every host; permissive only on explicit opt-in.
        return if (forceTrustAll) permissiveFactory else systemFactory
    }

    /** True when the default (no opt-in) factory choice is the strict system one. */
    fun usesStrictFactoryByDefault(): Boolean =
        socketFactoryFor(forceTrustAll = false) === systemFactory

    private fun applyTlsParameters(socket: Socket, trustAll: Boolean) {
        if (socket !is SSLSocket || trustAll) return
        val params: SSLParameters = socket.sslParameters
        params.endpointIdentificationAlgorithm = "HTTPS"
        socket.sslParameters = params
    }

    fun createSocket(host: String, port: Int, trustAll: Boolean): Socket {
        val factory = socketFactoryFor(trustAll)
        val socket = factory.createSocket(host, port)
        applyTlsParameters(socket, trustAll)
        return socket
    }

    fun createPlainSocket(host: String, port: Int): Socket = Socket(host, port)

    fun upgradeToTls(
        plainSocket: Socket,
        host: String,
        port: Int,
        trustAll: Boolean,
        autoClose: Boolean = true,
    ): SSLSocket {
        val factory = socketFactoryFor(trustAll)
        val ssl = factory.createSocket(plainSocket, host, port, autoClose) as SSLSocket
        ssl.useClientMode = true
        applyTlsParameters(ssl, trustAll)
        ssl.startHandshake()
        return ssl
    }
}
