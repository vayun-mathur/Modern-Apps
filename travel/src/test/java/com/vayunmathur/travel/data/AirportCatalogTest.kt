package com.vayunmathur.travel.data

import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Bundled TSV catalog: load once, then search/lookup fallbacks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AirportCatalogTest {

    @Test
    fun loaded_searchAndLookups() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        AirportCatalog.ensureLoaded(ctx)
        AirportCatalog.ensureLoaded(ctx)
        val results = AirportCatalog.search("New York")
        assertTrue(results.isNotEmpty(), "expected New York matches")
        assertTrue(AirportCatalog.search("x").isEmpty())
        assertTrue(AirportCatalog.search("").isEmpty())
        val jfk = AirportCatalog.airport("jfk")
        assertNotNull(jfk); assertEquals("JFK", jfk.iataCode)
        assertNull(AirportCatalog.airport("")); assertNull(AirportCatalog.airport("ZZZZ"))
        assertTrue(AirportCatalog.airlines().isNotEmpty())
        assertEquals("9Z", AirportCatalog.airlineName("9Z"))
        assertEquals("Zz", AirportCatalog.airlineName("ZZ"))
        assertTrue(AirportCatalog.airlineName("AA").length >= 2)
        assertEquals("ZZZ", AirportCatalog.aircraftName("ZZZ"))
        assertTrue(AirportCatalog.cities().isNotEmpty())
        assertEquals("ZZZ", AirportCatalog.cityName("ZZZ"))
        assertNull(AirportCatalog.coordinates("")); assertNull(AirportCatalog.coordinates("ZZZZ"))
        assertNotNull(AirportCatalog.coordinates("JFK"))
        val stays = AirportCatalog.staySuggestions("London")
        assertTrue(stays.isNotEmpty())
        assertTrue(AirportCatalog.aircraft().isNotEmpty())
    }

    @Test
    fun foldText_stripsDiacritics() {
        assertEquals("sao paulo", foldText("São Paulo"))
        assertEquals("cafe", foldText("Café"))
    }
}
