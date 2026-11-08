package com.vayunmathur.maps.util

import android.app.Application
import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.maps.data.MapPreferences
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.google.GooglePoiDataSource
import com.vayunmathur.maps.service.ReviewsScraperService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication

/** Slice 3/3 extra: currentPoiInfo review streaming + routes flow emission. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SelectedRoutesReviewsSlice3ExtraTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private class Payload {
        private val root = mutableListOf<Any?>()
        fun set(vararg path: Int, value: JsonElement) {
            var cur = root
            for (i in path.dropLast(1)) {
                while (cur.size <= i) cur.add(null)
                @Suppress("UNCHECKED_CAST")
                val nxt = (cur[i] as? MutableList<Any?>)
                    ?: mutableListOf<Any?>().also { cur[i] = it }
                cur = nxt
            }
            val last = path.last()
            while (cur.size <= last) cur.add(null)
            cur[last] = value
        }
        private fun toEl(v: Any?): JsonElement = when (v) {
            null -> JsonNull
            is JsonElement -> v
            is List<*> -> JsonArray(v.map { toEl(it) })
            else -> error("unexpected $v")
        }
        fun node(): JsonElement = toEl(root)
        fun searchBody(vararg entries: JsonElement): String {
            set(64, value = JsonArray(entries.toList()))
            return ")]}'\n${toEl(root)}"
        }
    }

    private fun pump() = Shadows.shadowOf(Looper.getMainLooper()).idleFor(300, java.util.concurrent.TimeUnit.MILLISECONDS)

    private fun installFakeScraper(reviewsJson: String) {
        val handler = object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                if (msg.what != ReviewsScraperService.MSG_FETCH) return
                val result = Message.obtain(null, ReviewsScraperService.MSG_RESULT, msg.arg1, 0)
                result.data = Bundle().apply { putString(ReviewsScraperService.KEY_JSON, reviewsJson) }
                msg.replyTo?.send(result)
            }
        }
        val shadow = Shadows.shadowOf(app) as ShadowApplication
        shadow.setComponentNameAndServiceForBindService(
            ComponentName(app, ReviewsScraperService::class.java),
            Messenger(handler).binder,
        )
    }

    // NOTE: no runBlocking here. Robolectric runs tests on the paused main
    // looper and viewModelScope is Main-bound, so blocking the test thread
    // starves the very coroutines under test (this test previously hung the
    // whole suite). Poll the StateFlow value while pumping the looper,
    // bounded, and assert on whatever arrived.
    @Test fun poiInfoStreamsReviewsFromScraper() {
        val ds = DataStoreUtils.getInstance(app)
        val prev = ds.getBoolean(MapPreferences.KEY_GOOGLE_SERVICES, false)
        kotlinx.coroutines.runBlocking { ds.setBoolean(MapPreferences.KEY_GOOGLE_SERVICES, true) }
        val defaultGet = GooglePoiDataSource.httpGet
        val defaultWarm = GooglePoiDataSource.httpWarm
        val name = "Review Stream Cafe"
        // Search entries wrap the place node: entry = [null, place], and the
        // parser's paths are entry-relative (NAME=(1,11), FEATURE_ID=(1,10)).
        val node = Payload().apply {
            set(11, value = JsonPrimitive(name))
            set(9, 2, value = JsonPrimitive(37.8)); set(9, 3, value = JsonPrimitive(-122.4))
            set(4, 7, value = JsonPrimitive(4.5))
            set(10, value = JsonPrimitive("0x808580:0x808581"))
        }.node()
        val entry = JsonArray(listOf(JsonNull, node))
        GooglePoiDataSource.httpWarm = { "" }
        GooglePoiDataSource.httpGet = { Payload().searchBody(entry) }
        installFakeScraper("""[{"a":"Rev Ann","r":5,"t":"Great"}]""")
        try {
            val vm = SelectedFeatureViewModel(app)
            vm.set(SpecificFeature.GenericPlace(name, "555", null, null, GeoPoint(-122.4, 37.8)))
            // currentPoiInfo is WhileSubscribed: keep a collector alive on a
            // background scope while the main looper is pumped from here.
            val seen = java.util.concurrent.atomic.AtomicReference<com.vayunmathur.maps.data.google.GooglePoiInfo?>(null)
            val job = CoroutineScope(Dispatchers.Default).launch {
                vm.currentPoiInfo.collect { if (it?.reviews?.isNotEmpty() == true) seen.set(it) }
            }
            val deadline = System.currentTimeMillis() + 25000
            while (seen.get() == null && System.currentTimeMillis() < deadline) { pump() }
            job.cancel()
            val info = seen.get()
            assertNotNull(info, "reviews-bearing poi info should stream from the fake scraper")
            assertEquals("Rev Ann", info.reviews.single().author)
            assertEquals("0x808580:0x808581", info.featureId)
        } finally {
            GooglePoiDataSource.httpGet = defaultGet
            GooglePoiDataSource.httpWarm = defaultWarm
            kotlinx.coroutines.runBlocking { ds.setBoolean(MapPreferences.KEY_GOOGLE_SERVICES, prev) }
        }
    }

    @Test fun routesFlowEmitsAllModesForRouteSelection() {
        val vm = SelectedFeatureViewModel(app)
        val from = SpecificFeature.GenericPlace("From", null, null, null, GeoPoint(-122.4, 37.8))
        val to = SpecificFeature.GenericPlace("To", null, null, null, GeoPoint(-122.3, 37.9))
        vm.set(SpecificFeature.Route(listOf(from, to)))
        val seen = java.util.concurrent.atomic.AtomicReference<Map<RouteService.TravelMode, RouteService.RouteType?>?>(null)
        val job = CoroutineScope(Dispatchers.Default).launch {
            vm.routes.collect { if (it != null) seen.set(it) }
        }
        val deadline = System.currentTimeMillis() + 25000
        while (seen.get() == null && System.currentTimeMillis() < deadline) { pump() }
        job.cancel()
        val latest = seen.get()
        assertNotNull(latest, "routes flow should emit a mode map for a route selection")
        assertTrue(latest.keys.containsAll(RouteService.TravelMode.entries), "keys=${latest.keys}")
        // No road graph in the sandbox: every mode degrades to EmptyRoute or null
        assertTrue(latest.values.all { it == null || it is RouteService.EmptyRoute }, "values=${latest.values}")
    }
}
