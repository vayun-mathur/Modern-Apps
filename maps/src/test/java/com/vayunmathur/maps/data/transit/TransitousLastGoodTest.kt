package com.vayunmathur.maps.data.transit

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Last-good serving in [TransitousDataSource]: failures must not blank a board that once loaded. */
class TransitousLastGoodTest {

    private val defaultFetch = TransitousDataSource.fetchStoptimes

    private fun dto() = MotisStoptimes(
        listOf(
            MotisStopTime(
                place = MotisPlace(
                    name = "LastGood Station",
                    scheduledDeparture = "2026-11-06T17:00:00+01:00",
                    departure = "2026-11-06T17:00:00+01:00",
                ),
                routeShortName = "N",
                headsign = "Downtown",
            )
        )
    )

    private fun withFetch(fetch: suspend (String) -> MotisStoptimes?, block: suspend () -> Unit) = runBlocking {
        TransitousDataSource.fetchStoptimes = fetch
        try {
            block()
        } finally {
            TransitousDataSource.fetchStoptimes = defaultFetch
        }
    }

    @Test
    fun `a failure after a success serves last-good as stale`() {
        var fail = false
        withFetch({ if (fail) null else dto() }) {
            val stop = "lastgood-stale"
            val fresh = TransitousDataSource.departuresResult(stop)
            assertFalse(fresh.stale)
            assertEquals(1, fresh.departures.size)
            fail = true
            val stale = TransitousDataSource.departuresResult(stop, force = true)
            assertTrue(stale.stale)
            assertFalse(stale.unavailable)
            assertEquals(fresh.departures, stale.departures)
            assertEquals(fresh.fetchedAtMillis, stale.fetchedAtMillis)
            // The plain list API serves the same last-good board, not empty.
            assertEquals(1, TransitousDataSource.departures(stop, force = true).size)
        }
    }

    @Test
    fun `a failure with no last-good is unavailable not genuinely empty`() {
        withFetch({ null }) {
            val result = TransitousDataSource.departuresResult("lastgood-none", force = true)
            assertTrue(result.unavailable)
            assertFalse(result.stale)
            assertTrue(result.departures.isEmpty())
            assertEquals(result, TransitousDataSource.cachedResult("lastgood-none"))
        }
    }

    @Test
    fun `a genuinely empty success is neither stale nor unavailable`() {
        withFetch({ MotisStoptimes(emptyList()) }) {
            val result = TransitousDataSource.departuresResult("lastgood-empty", force = true)
            assertFalse(result.stale)
            assertFalse(result.unavailable)
            assertTrue(result.departures.isEmpty())
        }
    }
}
