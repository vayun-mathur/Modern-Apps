package com.vayunmathur.appstore.util

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The image fetcher's input contract: only http(s) strings and Uris
 * are fetched; everything else (null, non-URL values, other schemes)
 * is declined with null so the image pipeline shows its fallback.
 * (The fetch itself needs the store's network stack and is not
 * exercised offline.)
 */
@RunWith(RobolectricTestRunner::class)
class RemoteImageFetchersTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `non url inputs are declined`() = runBlocking {
        assertNull(RemoteImageFetchers.network(null, context))
        assertNull(RemoteImageFetchers.network(42, context))
        assertNull(RemoteImageFetchers.network("file:///sdcard/icon.png", context))
        assertNull(RemoteImageFetchers.network("content://icons/1", context))
        assertNull(RemoteImageFetchers.network(Uri.parse("file:///sdcard/icon.png"), context))
        assertNull(RemoteImageFetchers.network(Uri.parse("android.resource://x/1"), context))
    }
}
