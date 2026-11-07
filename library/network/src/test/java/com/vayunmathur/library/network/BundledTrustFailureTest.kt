package com.vayunmathur.library.network

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers [BundledTrust]'s failure paths: a context whose assets cannot be
 * opened yields no factory (callers fall back to system trust), and a bundle
 * changed through [NetworkClient.init] keeps that fallback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BundledTrustFailureTest {

    private val base: Context = ApplicationProvider.getApplicationContext()

    /** A context whose asset opens always fail, as when DERs are missing. */
    private class BrokenAssetsContext(base: Context) : ContextWrapper(base) {
        override fun getAssets(): AssetManager = throw IOException("assets unavailable")
        // NetworkClient.init unwraps to the application context before
        // loading assets, so the wrapper must survive that unwrap.
        override fun getApplicationContext(): Context = this
    }

    @Test
    fun unreadableAssetsYieldNoFactory() {
        BundledTrust.clearCache()
        assertNull(BundledTrust.createFactory(BrokenAssetsContext(base), TrustBundle.FIRST_PARTY))
        assertNull(BundledTrust.createFactory(BrokenAssetsContext(base), TrustBundle.EXTENDED))
    }

    @Test
    fun initWithBrokenAssetsFallsBackToSystemTrust() {
        // Reset any bundle a sibling test class installed in this JVM, so
        // init() below cannot take its already-initialized no-op path.
        NetworkClient.initWithFactory(null)
        BundledTrust.clearCache()
        NetworkClient.init(BrokenAssetsContext(base), TrustBundle.FIRST_PARTY)
        assertNull(NetworkClient.defaultSslSocketFactory)
        assertNull(NetworkClient.defaultTrustManager)
        // Restore a clean state for other tests sharing this JVM.
        NetworkClient.initWithFactory(null)
        BundledTrust.clearCache()
    }
}
