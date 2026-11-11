package com.vayunmathur.maps.util

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.maps.data.MapPreferences
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.google.GooglePoiDataSource
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

/** Slice 3/3 extra: currentPoiInfo is place details only (no reviews) + routes flow emission. */
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

    // NOTE: no runBlocking here. Robolectric runs tests on the paused main
    // looper and viewModelScope is Main-bound, so blocking the test thread
    // starves the very coroutines under test (this test previously hung the
    // whole suite). Poll the StateFlow value while pumping the looper,
    // bounded, and assert on whatever arrived.
    @Test fun poiInfoCarriesPlaceDetailsAndNoReviews() {
        val ds = DataStoreUtils.getInstance(app)
        val prev = ds.getBoolean(MapPreferences.KEY_GOOGLE_SERVICES, false)
        kotlinx.coroutines.runBlocking { ds.setBoolean(MapPreferences.KEY_GOOGLE_SERVICES, true) }
        val defaultGet = GooglePoiDataSource.httpGet
        val defaultWarm = GooglePoiDataSource.httpWarm
        val name = "Details Only Cafe"
        // The response carries rating, featured review and feature id (the old review-scrape
        // key); none of it may reach the app. Only the place facts do.
        val node = Payload().apply {
            set(11, value = JsonPrimitive(name))
            set(9, 2, value = JsonPrimitive(37.8)); set(9, 3, value = JsonPrimitive(-122.4))
            set(4, 7, value = JsonPrimitive(4.5))
            set(10, value = JsonPrimitive("0x808580:0x808581"))
            set(142, 1, 0, 1, 0, 0, value = JsonPrimitive("Great"))
            set(7, 0, value = JsonPrimitive("https://cafe.example"))
            set(203, 1, 4, 0, value = JsonPrimitive("Open · Closes 9 PM"))
        }.node()
        val entry = JsonArray(listOf(JsonNull, node))
        val requested = java.util.concurrent.CopyOnWriteArrayList<String>()
        GooglePoiDataSource.httpWarm = { "" }
        GooglePoiDataSource.httpGet = { url -> requested.add(url); Payload().searchBody(entry) }
        try {
            val vm = SelectedFeatureViewModel(app)
            vm.set(SpecificFeature.GenericPlace(name, "555", null, null, GeoPoint(-122.4, 37.8)))
            // The sheet only fetches Google details once the user reveals them.
            vm.revealDetails()
            val seen = java.util.concurrent.atomic.AtomicReference<com.vayunmathur.maps.data.google.GooglePoiInfo?>(null)
            val job = CoroutineScope(Dispatchers.Default).launch {
                vm.currentPoiInfo.collect { if (it != null) seen.set(it) }
            }
            val deadline = System.currentTimeMillis() + 25000
            while (seen.get() == null && System.currentTimeMillis() < deadline) { pump() }
            job.cancel()
            val info = seen.get()
            assertNotNull(info, "place details should emit for a matched place")
            assertEquals("https://cafe.example", info.website)
            assertEquals("Open · Closes 9 PM", info.statusText)
            // One details request, and it no longer asks for review posts.
            assertEquals(1, requested.size)
            assertTrue(requested.none { it.contains("review_posts") }, "request still selects review posts")
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
