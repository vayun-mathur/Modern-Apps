package com.vayunmathur.library.image

import android.net.Uri
import com.vayunmathur.library.image.fetchers.FetchResult
import com.vayunmathur.library.image.fetchers.HttpImageFetcher
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shared HTTP image fetcher's contract (formerly copied into eight
 * apps as `RemoteImageFetchersTest`): only http(s) strings and Uris are
 * fetched, everything else is declined with null, and a failing primary
 * URL is retried once through the fallback form when one is provided.
 */
@RunWith(RobolectricTestRunner::class)
class HttpImageFetcherTest {

    private val unusedTransport: suspend (String) -> ByteArray =
        { throw AssertionError("transport must not be called for declined data: $it") }

    @Test
    fun `non url inputs are declined`() = runBlocking {
        assertNull(HttpImageFetcher.fetch(null, unusedTransport))
        assertNull(HttpImageFetcher.fetch(42, unusedTransport))
        assertNull(HttpImageFetcher.fetch("file:///sdcard/icon.png", unusedTransport))
        assertNull(HttpImageFetcher.fetch("content://icons/1", unusedTransport))
        assertNull(HttpImageFetcher.fetch("ftp://example.com/x.png", unusedTransport))
        assertNull(HttpImageFetcher.fetch("", unusedTransport))
        assertNull(HttpImageFetcher.fetch(Uri.parse("file:///sdcard/icon.png"), unusedTransport))
        assertNull(HttpImageFetcher.fetch(Uri.parse("android.resource://x/1"), unusedTransport))
    }

    @Test
    fun `http string and uri are fetched through the transport`() = runBlocking {
        val seen = mutableListOf<String>()
        val transport: suspend (String) -> ByteArray = { url ->
            seen += url
            byteArrayOf(1, 2, 3)
        }
        val fromString = HttpImageFetcher.fetch("https://example.com/a.png", transport)
        val fromUri = HttpImageFetcher.fetch(Uri.parse("http://example.com/b.png"), transport)
        assertEquals(listOf("https://example.com/a.png", "http://example.com/b.png"), seen)
        assertTrue(fromString is FetchResult.Bytes && fromString.bytes.contentEquals(byteArrayOf(1, 2, 3)))
        assertTrue(fromUri is FetchResult.Bytes)
    }

    @Test
    fun `fallback url is tried once after a primary failure`() = runBlocking {
        val seen = mutableListOf<String>()
        val transport: suspend (String) -> ByteArray = { url ->
            seen += url
            if (url.contains("static")) throw IOException("HTTP 404 for $url")
            byteArrayOf(9)
        }
        val result = HttpImageFetcher.fetch(
            "https://static.example.com/tmdb/w342/x.jpg",
            transport,
        ) { url -> url.replace("static", "proxy") }
        assertEquals(
            listOf(
                "https://static.example.com/tmdb/w342/x.jpg",
                "https://proxy.example.com/tmdb/w342/x.jpg",
            ),
            seen,
        )
        assertTrue(result is FetchResult.Bytes && result.bytes.contentEquals(byteArrayOf(9)))
    }

    @Test
    fun `failure propagates when no fallback applies`() {
        runBlocking {
            assertFailsWith<IOException> {
                HttpImageFetcher.fetch(
                    "https://example.com/x.png",
                    transport = { throw IOException("HTTP 404 for it") },
                )
            }
        }
    }
}
