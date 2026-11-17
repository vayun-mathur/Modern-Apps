package com.vayunmathur.communicate.data.signal.transport

import android.annotation.SuppressLint
import android.content.Context
import com.vayunmathur.library.log.Log
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * TLS trust for Signal service hosts (chat / storage / cdn / cdsi).
 *
 * Signal's servers chain to Signal's PRIVATE service root (CN=Signal Messenger),
 * which is NOT in the Android system trust store — so the default trust manager
 * fails with "trust anchor for certification path not found". These two DER roots
 * are the authoritative libsignal `SIGNAL_ROOT_CERTIFICATES` set (rust/net/src/certs.rs:
 * signal.cer RSA + signal-ed25519.cer), bundled here under assets/ca/.
 *
 * Follows [com.vayunmathur.library.network.BundledTrust]'s pattern
 * (CertificateFactory -> KeyStore -> TrustManagerFactory -> SSLContext) but produces a
 * UNION trust manager: a chain validates if it anchors in EITHER the bundled Signal
 * roots OR the platform system roots. The union is deliberate — the Signal transport
 * mixes hosts on Signal's private CA (chat/cdsi) with hosts that may serve public-CA
 * certs (CDN), so a bundled-only factory would regress the latter. The resulting
 * factory is safe to pass to every Signal-host call and never narrows existing trust.
 */
object SignalTrust {
    private const val TAG = "SignalTrust"

    private val CA_ASSETS = listOf(
        "ca/signal-service-ca.der",          // RSA root, CN=Signal Messenger (2022-2032)
        "ca/signal-service-ca-ed25519.der",  // Ed25519 root, CN=Signal Messenger (2026-2036)
    )

    @Volatile
    private var cached: SSLSocketFactory? = null

    /**
     * Returns an [SSLSocketFactory] that trusts Signal's service roots in addition to the
     * platform defaults. Cached after first build. Returns null only if neither the bundled
     * roots nor a system trust manager could be constructed, in which case callers fall back
     * to the platform default (which fails the Signal handshake — logged loudly).
     */
    fun sslSocketFactory(context: Context): SSLSocketFactory? {
        cached?.let { return it }
        return synchronized(this) {
            cached?.let { return it }
            val factory = build(context.applicationContext)
            cached = factory
            factory
        }
    }

    private fun build(context: Context): SSLSocketFactory? {
        return try {
            val cf = CertificateFactory.getInstance("X.509")
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            val loaded = loadBundledRoots(context, cf, ks)
            if (loaded == 0) {
                Log.error(TAG, "No Signal CAs loaded (checked ${CA_ASSETS.size}" +
                    "assets) — Signal TLS will fail (trust anchor not found)")
                return null
            }

            val bundledTm = trustManagerFor(ks)
            val systemTm = trustManagerFor(null)
            if (bundledTm == null) {
                Log.error(TAG, "No X509TrustManager for bundled Signal roots")
                return null
            }

            val union = if (systemTm != null) UnionTrustManager(bundledTm, systemTm) else bundledTm
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<javax.net.ssl.TrustManager>(union), null)
            }
            Log.status(TAG, "Signal trust ready: $loaded bundled root(s) + system defaults")
            sslContext.socketFactory
        } catch (expected: Exception) {
            Log.error(TAG, "Failed to build Signal SSLSocketFactory", expected)
            null
        }
    }

    /** Load bundled CA assets into [ks]; returns the count loaded. */
    private fun loadBundledRoots(context: Context, cf: CertificateFactory, ks: KeyStore): Int {
        var loaded = 0
        for ((idx, path) in CA_ASSETS.withIndex()) {
            if (loadOneRoot(context, cf, ks, idx, path)) loaded++
        }
        return loaded
    }

    /** Load one CA asset; true when it decoded and stored. */
    private fun loadOneRoot(
        context: Context,
        cf: CertificateFactory,
        ks: KeyStore,
        idx: Int,
        path: String,
    ): Boolean {
        return try {
            context.assets.open(path).use { ins ->
                val cert = cf.generateCertificate(ins) as? X509Certificate
                if (cert != null) {
                    ks.setCertificateEntry("signal-ca-$idx", cert)
                    true
                } else {
                    Log.status(TAG, "Asset $path did not decode to X509Certificate")
                    false
                }
            }
        } catch (expected: Exception) {
            Log.status(TAG, "Failed to load Signal CA asset $path: ${expected.message}")
            false
        }
    }

    private fun trustManagerFor(ks: KeyStore?): X509TrustManager? {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
        return tmf.trustManagers.firstOrNull { it is X509TrustManager } as? X509TrustManager
    }

    /** For tests/debug: drop the cached factory. */
    fun clearCache() {
        synchronized(this) { cached = null }
    }

    /**
     * Accepts a chain trusted by EITHER the bundled Signal roots or the system roots.
     * Client auth and accepted issuers defer to the system manager (plus bundled issuers).
     */
    // This manager does not weaken validation: every chain must pass either
    // the bundled Signal roots or the platform system manager, both of which
    // are default X509TrustManagers doing full chain validation.
    @SuppressLint("CustomX509TrustManager")
    private class UnionTrustManager(
        private val bundled: X509TrustManager,
        private val system: X509TrustManager,
    ) : X509TrustManager {
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            try {
                bundled.checkServerTrusted(chain, authType)
            } catch (_: CertificateException) {
                system.checkServerTrusted(chain, authType)
            }
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            system.checkClientTrusted(chain, authType)
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> =
            bundled.acceptedIssuers + system.acceptedIssuers
    }
}
