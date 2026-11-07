package com.vayunmathur.library.network

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.OfflineAware
import com.vayunmathur.library.util.OfflineBanner
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Trust bundles, bundled CA factory, response helpers, data stream, offline UI. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryNetworkTrustTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun trustBundleAssetPathsNestAndSystemEmpty() {
        assertEquals(emptyList(), TrustBundle.SYSTEM.assetPaths())
        assertEquals(listOf("ca/gsma-rsp2-root-ci1.der"), TrustBundle.ESIM.assetPaths())
        val firstParty = TrustBundle.FIRST_PARTY.assetPaths()
        assertEquals(6, firstParty.size)
        assertTrue(firstParty.all { it.startsWith("ca/") && it.endsWith(".der") })
        val standard = TrustBundle.STANDARD.assetPaths()
        assertTrue(standard.containsAll(firstParty))
        assertTrue(standard.size > firstParty.size)
        val extended = TrustBundle.EXTENDED.assetPaths()
        assertTrue(extended.containsAll(standard))
        assertEquals(standard.size + 4, extended.size)
        // No duplicates at any level (a duplicated root would double-count in the keystore).
        assertEquals(extended.size, extended.toSet().size)
        assertTrue(TrustBundle.ESIM.includeSystemIssuers())
        assertTrue(!TrustBundle.FIRST_PARTY.includeSystemIssuers())
        assertTrue(!TrustBundle.SYSTEM.includeSystemIssuers())
    }

    @Test
    fun bundledTrustSystemIsNullAndFirstPartyBuilds() {
        assertNull(BundledTrust.createFactory(context, TrustBundle.SYSTEM))
        BundledTrust.clearCache()
        val pair = BundledTrust.createFactory(context, TrustBundle.FIRST_PARTY)
        assertNotNull(pair)
        assertNotNull(pair.first)
        assertNotNull(pair.second)
        // Cached second call returns the same pair.
        val again = BundledTrust.createFactory(context, TrustBundle.FIRST_PARTY)
        assertTrue(pair.first === again!!.first)
        BundledTrust.clearCache()
        val rebuilt = BundledTrust.createFactory(context, TrustBundle.FIRST_PARTY)
        assertNotNull(rebuilt)
        assertTrue(pair.first !== rebuilt!!.first)
    }

    @Test
    fun bundledTrustExtendedAndEsimBuild() {
        assertNotNull(BundledTrust.createFactory(context, TrustBundle.EXTENDED))
        assertNotNull(BundledTrust.createFactory(context, TrustBundle.ESIM))
        assertNotNull(BundledTrust.createFactory(context, TrustBundle.STANDARD))
    }

    @Test
    fun networkClientInitSystemAndBundled() {
        NetworkClient.init(context, TrustBundle.SYSTEM)
        assertEquals(TrustBundle.SYSTEM, NetworkClient.currentBundle)
        assertNull(NetworkClient.defaultSslSocketFactory)
        assertNull(NetworkClient.defaultTrustManager)
        NetworkClient.init(context, TrustBundle.FIRST_PARTY)
        assertEquals(TrustBundle.FIRST_PARTY, NetworkClient.currentBundle)
        assertNotNull(NetworkClient.defaultSslSocketFactory)
        assertNotNull(NetworkClient.defaultTrustManager)
        // Re-init with the same bundle is a no-op (same factory instance).
        val factory = NetworkClient.defaultSslSocketFactory
        NetworkClient.init(context, TrustBundle.FIRST_PARTY)
        assertTrue(factory === NetworkClient.defaultSslSocketFactory)
        NetworkClient.initWithFactory(null)
        assertNull(NetworkClient.defaultSslSocketFactory)
        assertEquals(TrustBundle.SYSTEM, NetworkClient.currentBundle)
    }

    @Test
    fun simpleAndRawResponseHeadersAndSuccess() {
        val headers = mapOf("Content-Length" to listOf("123"), "Set-Cookie" to listOf("a=1", "b=2"))
        val simple = SimpleResponse(200, "OK", "body", headers, "https://x.example/")
        assertTrue(simple.isSuccess)
        assertEquals(123L, simple.contentLength)
        assertEquals("a=1", simple.header("set-cookie"))
        assertEquals(listOf("a=1", "b=2"), simple.headerValues("SET-COOKIE"))
        assertNull(simple.header("missing"))
        assertEquals(emptyList(), simple.headerValues("missing"))
        assertTrue(!SimpleResponse(404, "NF", "", emptyMap(), "u").isSuccess)
        assertTrue(SimpleResponse(204, "NC", "", emptyMap(), "u").isSuccess)
        assertNull(SimpleResponse(200, "OK", "", mapOf("Content-Length" to listOf("abc")), "u").contentLength)
        val raw = RawResponse(201, "Created", "héllo".toByteArray(Charsets.UTF_8), headers, "u")
        assertTrue(raw.isSuccess)
        assertEquals("héllo", raw.text)
        assertEquals("123", raw.header("content-length"))
    }

    @Test
    fun byteArrayDataStreamReadsInChunks() = runBlocking {
        val stream = byteArrayOf(1, 2, 3, 4, 5).asNetworkDataStream()
        assertTrue(!stream.isClosedForRead)
        val buf = ByteArray(2)
        assertEquals(2, stream.read(buf))
        assertTrue(byteArrayOf(1, 2).contentEquals(buf))
        assertEquals(2, stream.read(buf, 0, 2))
        assertEquals(1, stream.read(buf))
        assertTrue(stream.isClosedForRead)
        assertEquals(-1, stream.read(buf))
        val empty = byteArrayOf().asNetworkDataStream()
        assertTrue(empty.isClosedForRead)
        assertEquals(-1, empty.read(ByteArray(4)))
        // Offset reads land at the requested position.
        val s2 = byteArrayOf(9, 8, 7).asNetworkDataStream()
        val big = ByteArray(5)
        assertEquals(3, s2.read(big, 1, 3))
        assertTrue(byteArrayOf(0, 9, 8, 7, 0).contentEquals(big))
    }

    @Test
    fun urlsHostAndAppendQuery() {
        assertEquals("api.example.com", Urls.host("https://api.example.com/path?q=1"))
        assertEquals("", Urls.host("not a url"))
        assertEquals("", Urls.host(""))
        assertEquals("https://x.example/p", Urls.appendQuery("https://x.example/p", emptyMap()))
        assertEquals("https://x.example/p?a=1", Urls.appendQuery("https://x.example/p", mapOf("a" to "1")))
        assertEquals(
            "https://x.example/p?z=9&a=1",
            Urls.appendQuery("https://x.example/p?z=9", mapOf("a" to "1")),
        )
        assertEquals(
            "https://x.example/p?q=hello+world",
            Urls.appendQuery("https://x.example/p?", mapOf("q" to "hello world")),
        )
    }

    @Test
    fun offlineBannerHiddenWhenOnline() {
        composeRule.setContent { OfflineBanner(online = true) }
        composeRule.onNodeWithText("No internet connection").assertDoesNotExist()
    }

    @Test
    fun offlineBannerShownWhenOffline() {
        composeRule.setContent { OfflineBanner(online = false) }
        composeRule.onNodeWithText("No internet connection").assertIsDisplayed()
    }

    @Test
    fun offlineAwareWrapsContent() {
        composeRule.setContent {
            OfflineAware { Text("Screen body") }
        }
        composeRule.onNodeWithText("Screen body").assertIsDisplayed()
    }
}
