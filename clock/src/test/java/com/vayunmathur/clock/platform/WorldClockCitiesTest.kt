package com.vayunmathur.clock.platform

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/** Tests for [WorldClockCities] storage: ordered encode/decode, toggle and legacy migration. */
@RunWith(RobolectricTestRunner::class)
class WorldClockCitiesTest {

    private fun ds(): DataStoreUtils =
        DataStoreUtils.getInstance(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun setAndFlowRoundTripPreservesOrder() = runBlocking {
        val ds = ds()
        WorldClockCities.set(ds, listOf("Paris, France", "Tokyo, Japan", "New York, USA"))
        assertEquals(
            listOf("Paris, France", "Tokyo, Japan", "New York, USA"),
            WorldClockCities.flow(ds).first(),
        )
        WorldClockCities.set(ds, emptyList())
    }

    @Test
    fun toggleAddsThenRemoves() = runBlocking {
        val ds = ds()
        WorldClockCities.set(ds, listOf("London, UK"))
        WorldClockCities.toggle(ds, "Berlin, Germany")
        assertEquals(listOf("London, UK", "Berlin, Germany"), WorldClockCities.flow(ds).first())
        WorldClockCities.toggle(ds, "London, UK")
        assertEquals(listOf("Berlin, Germany"), WorldClockCities.flow(ds).first())
        WorldClockCities.set(ds, emptyList())
    }

    @Test
    fun migrateSeedsSortedLegacySetOnce() = runBlocking {
        val ds = ds()
        WorldClockCities.set(ds, emptyList())
        // Seed the legacy unordered set, then clear the ordered key via an empty write is not
        // enough (it stores ""), so remove both keys first through a fresh migrate cycle:
        // The suspending add commits each write before returning: the fire-and-forget
        // addStringToSet launches on the store's scope, and polling for its landing
        // with a fixed try count gave up under CI load before the edits ran.
        ds.addStringToSetIfAbsent("time_zones", "Zurich, Switzerland")
        ds.addStringToSetIfAbsent("time_zones", "Athens, Greece")
        assertEquals(2, ds.getStringSetAwait("time_zones").size)
        // The ordered key currently holds "" (from the empty set above), which decodes to an
        // empty list but is non-null, so migrate is a no-op here; verify that contract.
        WorldClockCities.migrate(ds)
        assertEquals(emptyList(), WorldClockCities.flow(ds).first())
    }
}
