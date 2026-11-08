package com.vayunmathur.maps.service

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication

/**
 * Slice 3/3 extra: [ReviewsScraperClient] IPC against a fake scraper service
 * (a Messenger that speaks the MSG_FETCH/MSG_PARTIAL/MSG_RESULT protocol),
 * plus the unbound failure path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewsScraperSlice3ExtraTest {

    private val app get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val reviewsJson = """[{"a":"Ann","r":5,"d":"a week ago","t":"Loved it"},{"a":"Bo","r":3,"t":"Ok"}]"""

    /** Installs a fake :reviews service that replies PARTIAL then RESULT for any FETCH. */
    private fun installFakeService(replyJson: String?, sendPartial: Boolean = true) {
        val handler = object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                if (msg.what != ReviewsScraperService.MSG_FETCH) return
                val id = msg.arg1
                val replyTo = msg.replyTo ?: return
                if (sendPartial) {
                    val partial = Message.obtain(null, ReviewsScraperService.MSG_PARTIAL, id, 0)
                    partial.data = Bundle().apply { putString(ReviewsScraperService.KEY_JSON, replyJson) }
                    replyTo.send(partial)
                }
                val result = Message.obtain(null, ReviewsScraperService.MSG_RESULT, id, 0)
                result.data = Bundle().apply { putString(ReviewsScraperService.KEY_JSON, replyJson) }
                replyTo.send(result)
            }
        }
        val messenger = Messenger(handler)
        val shadow = Shadows.shadowOf(app as android.app.Application) as ShadowApplication
        shadow.setComponentNameAndServiceForBindService(
            ComponentName(app, ReviewsScraperService::class.java),
            messenger.binder,
        )
    }

    /** fetch() hops to Dispatchers.Main, whose Robolectric looper is paused: pump it. */
    private suspend fun <T> pumpMain(block: suspend () -> T): T {
        val deferred = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).async { block() }
        withTimeout(15000) {
            while (!deferred.isCompleted) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                delay(25)
            }
        }
        return deferred.await()
    }

    @Test fun fetchStreamsPartialsAndReturnsParsedResult() { runBlocking {
        installFakeService(reviewsJson)
        val client = ReviewsScraperClient(app)
        val partials = mutableListOf<Int>()
        val reviews = pumpMain { client.fetch("feature-1") { partials.add(it.size) } }
        assertEquals(listOf("Ann", "Bo"), reviews.map { it.author })
        assertEquals(5, reviews[0].rating)
        assertEquals(listOf(2), partials) // one partial with both reviews
        // Second fetch reuses the warm binding
        val again = pumpMain { client.fetch("feature-2") {} }
        assertEquals(2, again.size)
        Unit
    } }

    @Test fun fetchWithNullResultReturnsEmpty() { runBlocking {
        installFakeService(null, sendPartial = false)
        val client = ReviewsScraperClient(app)
        val reviews = pumpMain { client.fetch("feature-x") {} }
        assertTrue(reviews.isEmpty())
        Unit
    } }

    @Test fun fetchWithoutServiceReturnsEmpty() { runBlocking {
        // No fake service installed and no real :reviews process under Robolectric:
        // bind fails or never connects, and fetch must degrade to empty, not hang.
        // Robolectric's bindService for an unresolvable component returns false.
        val client = ReviewsScraperClient(app)
        val reviews = pumpMain { client.fetch("feature-y") {} }
        assertTrue(reviews.isEmpty())
        Unit
    } }
}
