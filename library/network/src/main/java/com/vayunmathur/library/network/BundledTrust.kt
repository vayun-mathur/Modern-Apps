package com.vayunmathur.library.network

import android.content.Context
import com.vayunmathur.library.log.Log
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Builds SSLSocketFactory from bundled DER roots in assets/ca/.
 * Reuses CertPinning pattern (CertificateFactory -> KeyStore -> TMF -> SSLContext).
 * Caches per-bundle to avoid repeated asset I/O.
 */
object BundledTrust {
    private const val TAG = "BundledTrust"

    @Volatile
    private var cache: Map<TrustBundle, Pair<SSLSocketFactory, X509TrustManager>> = emptyMap()

    @Synchronized
    fun createFactory(
        context: Context,
        bundle: TrustBundle,
    ): Pair<SSLSocketFactory, X509TrustManager>? {
        if (bundle == TrustBundle.SYSTEM) return null

        cache[bundle]?.let { return it }

        val assetPaths = bundle.assetPaths()
        if (assetPaths.isEmpty()) {
            Log.status(TAG, "Bundle $bundle has no asset paths")
            return null
        }

        return try {
            buildFactory(context, bundle, assetPaths)
        } catch (e: GeneralSecurityException) {
            Log.error(TAG, "Failed to create factory for bundle $bundle", e)
            null
        } catch (e: IOException) {
            Log.error(TAG, "Failed to create factory for bundle $bundle", e)
            null
        }
    }

    private fun buildFactory(
        context: Context,
        bundle: TrustBundle,
        assetPaths: List<String>,
    ): Pair<SSLSocketFactory, X509TrustManager>? {
        val cf = CertificateFactory.getInstance("X.509")
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }

        val systemLoaded = seedSystemIssuers(ks, bundle)
        val bundledLoaded = loadBundledAssets(context, cf, ks, assetPaths)
        val loaded = systemLoaded + bundledLoaded

        if (bundledLoaded == 0) {
            // Even for ESIM (system-seeded), a bundle whose own roots all failed is
            // not the bundle that was asked for; NetworkClient fails closed on null.
            Log.error(
                TAG,
                "No bundled CAs loaded for bundle $bundle (checked ${assetPaths.size} assets)",
            )
            return null
        }

        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(ks)
        }
        val trustManager = tmf.trustManagers.firstOrNull { it is X509TrustManager } as? X509TrustManager
        if (trustManager == null) {
            Log.error(TAG, "No X509TrustManager found for bundle $bundle")
            return null
        }

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, tmf.trustManagers, null)
        }

        val pair = sslContext.socketFactory to trustManager
        cache = cache + (bundle to pair)
        Log.status(TAG, "Bundle $bundle loaded $loaded roots")
        return pair
    }

    private fun seedSystemIssuers(ks: KeyStore, bundle: TrustBundle): Int {
        if (!bundle.includeSystemIssuers()) return 0
        // Seed from the platform store so every public-CA host keeps
        // validating; the bundled assets below only ADD ecosystem roots.
        val systemTmf = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm(),
        ).apply { init(null as KeyStore?) }
        val systemIssuers = systemTmf.trustManagers
            .filterIsInstance<X509TrustManager>()
            .flatMap { it.acceptedIssuers.toList() }
        systemIssuers.forEachIndexed { idx, cert ->
            ks.setCertificateEntry("system-$idx", cert)
        }
        return systemIssuers.size
    }

    private fun loadBundledAssets(
        context: Context,
        cf: CertificateFactory,
        ks: KeyStore,
        assetPaths: List<String>,
    ): Int {
        var loaded = 0
        assetPaths.forEachIndexed { idx, path ->
            if (loadOneAsset(context, cf, ks, idx, path)) loaded++
        }
        return loaded
    }

    private fun loadOneAsset(
        context: Context,
        cf: CertificateFactory,
        ks: KeyStore,
        idx: Int,
        path: String,
    ): Boolean {
        try {
            context.assets.open(path).use { ins ->
                val cert = cf.generateCertificate(ins) as? X509Certificate
                if (cert != null) {
                    ks.setCertificateEntry("ca-$idx-${path.hashCode()}", cert)
                    return true
                }
                Log.status(TAG, "Asset $path did not decode to X509Certificate")
            }
        } catch (e: IOException) {
            // Missing asset is expected during early dev before DERs are bundled.
            Log.status(TAG, "Failed to load CA asset $path: ${e.message}")
        } catch (e: GeneralSecurityException) {
            Log.status(TAG, "Failed to load CA asset $path: ${e.message}")
        }
        return false
    }

    /** For tests / debug: clear cache. */
    @Synchronized
    fun clearCache() {
        cache = emptyMap()
    }
}
