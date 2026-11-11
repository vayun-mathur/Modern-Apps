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

/** Slice 3/3 extra: SelectedFeatureViewModel Google POI enrichment (opt-in on). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SelectedPoiInfoSlice3ExtraTest {

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

    private fun placeNode(name: String, lat: Double, lng: Double): JsonElement = Payload().apply {
        set(11, value = JsonPrimitive(name))
        set(9, 2, value = JsonPrimitive(lat))
        set(9, 3, value = JsonPrimitive(lng))
        // Google sends a rating/count too; the app must not read them.
        set(4, 7, value = JsonPrimitive(4.5))
        set(4, 8, value = JsonPrimitive(230))
        set(7, 0, value = JsonPrimitive("https://place.example"))
        set(178, 0, 0, value = JsonPrimitive("+14155550123"))
    }.node()

    // No runBlocking: it blocks the paused Robolectric main looper that
    // viewModelScope needs (previously hung the suite). Collect on Default
    // into a reference, pump the looper bounded, then assert.
    @Test fun googleOnFetchesAndEmitsPoiInfo() {
        val ds = DataStoreUtils.getInstance(app)
        val prev = ds.getBoolean(MapPreferences.KEY_GOOGLE_SERVICES, false)
        kotlinx.coroutines.runBlocking { ds.setBoolean(MapPreferences.KEY_GOOGLE_SERVICES, true) }
        val defaultGet = GooglePoiDataSource.httpGet
        val defaultWarm = GooglePoiDataSource.httpWarm
        val name = "Enrichment Target Cafe"
        GooglePoiDataSource.httpWarm = { "" }
        GooglePoiDataSource.httpGet = {
            Payload().searchBody(JsonArray(listOf(JsonNull, placeNode(name, 37.8, -122.4))))
        }
        try {
            val vm = SelectedFeatureViewModel(app)
            vm.set(SpecificFeature.GenericPlace(name, "555", null, null, GeoPoint(-122.4, 37.8)))
            // The sheet only fetches Google details once the user reveals them.
            vm.revealDetails()
            val seen = java.util.concurrent.atomic.AtomicReference<com.vayunmathur.maps.data.google.GooglePoiInfo?>(null)
            val job = CoroutineScope(Dispatchers.Default).launch {
                vm.currentPoiInfo.collect { if (it != null) seen.set(it) }
            }
            val deadline = System.currentTimeMillis() + 15000
            while (seen.get() == null && System.currentTimeMillis() < deadline) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
            }
            job.cancel()
            val info = seen.get()
            assertNotNull(info, "poi info should emit for a matched place")
            // Non-review place details only (photos/hours/phone/website…); rating and review
            // count are archive-only and have no field on the Google model.
            assertEquals("https://place.example", info.website)
            assertEquals("+14155550123", info.phone)
        } finally {
            GooglePoiDataSource.httpGet = defaultGet
            GooglePoiDataSource.httpWarm = defaultWarm
            kotlinx.coroutines.runBlocking { ds.setBoolean(MapPreferences.KEY_GOOGLE_SERVICES, prev) }
        }
    }

    @Test fun googleOnNoMatchEmitsNull() {
        val ds = DataStoreUtils.getInstance(app)
        val prev = ds.getBoolean(MapPreferences.KEY_GOOGLE_SERVICES, false)
        kotlinx.coroutines.runBlocking { ds.setBoolean(MapPreferences.KEY_GOOGLE_SERVICES, true) }
        val defaultGet = GooglePoiDataSource.httpGet
        val defaultWarm = GooglePoiDataSource.httpWarm
        GooglePoiDataSource.httpWarm = { "" }
        GooglePoiDataSource.httpGet = { Payload().searchBody() } // zero entries -> NoMatch
        try {
            val vm = SelectedFeatureViewModel(app)
            vm.set(SpecificFeature.GenericPlace("Nowhere Place", "555", null, null, GeoPoint(-122.4, 37.8)))
            // The sheet only fetches Google details once the user reveals them.
            vm.revealDetails()
            val seen = java.util.concurrent.atomic.AtomicReference<com.vayunmathur.maps.data.google.GooglePoiInfo?>(null)
            val job = CoroutineScope(Dispatchers.Default).launch {
                vm.currentPoiInfo.collect { seen.set(it) }
            }
            // Give the fetch its chance (bounded pump), then the value must
            // still be null: no match means no enrichment emission.
            val deadline = System.currentTimeMillis() + 6000
            while (System.currentTimeMillis() < deadline) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
            }
            job.cancel()
            assertEquals(null, seen.get())
            assertEquals(null, vm.currentPoiInfo.value)
        } finally {
            GooglePoiDataSource.httpGet = defaultGet
            GooglePoiDataSource.httpWarm = defaultWarm
            kotlinx.coroutines.runBlocking { ds.setBoolean(MapPreferences.KEY_GOOGLE_SERVICES, prev) }
        }
    }
}
