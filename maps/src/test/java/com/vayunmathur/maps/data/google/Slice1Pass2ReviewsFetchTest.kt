package com.vayunmathur.maps.data.google

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 1/3 second pass (maps): [WebReviewsFetcher.fetch] with a valid cid —
 * the hidden-WebView scrape in the sandbox never answers through the JS
 * bridge, so the fetch must walk its load/settle path and give up (null)
 * at its own timeout rather than hanging or throwing.
 */
@RunWith(RobolectricTestRunner::class)
class Slice1Pass2ReviewsFetchTest {

    @Test
    fun `valid cid scrape times out to null in the sandbox`() = runBlocking {
        val fetcher = WebReviewsFetcher(
            ApplicationProvider.getApplicationContext(),
        )
        var progressTicks = 0
        val deferred = CoroutineScope(Dispatchers.Default).async {
            fetcher.fetch("0x1234:0xABCD", onProgress = { progressTicks++ })
        }
        // the fetcher waits on the main looper for page settle; pump it while
        // the scrape's own 45s timeout runs down in real time
        val deadline = System.currentTimeMillis() + 90_000
        while (!deferred.isCompleted && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(100)
        }
        assertTrue(deferred.isCompleted)
        assertNull(deferred.await())
    }
}
