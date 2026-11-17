package com.vayunmathur.maps.util
import com.vayunmathur.maps.data.nearest
import com.vayunmathur.maps.util.OfflineRouter
import com.vayunmathur.maps.data.OfflineRouterLifecycle
import com.vayunmathur.maps.data.OfflineRouterTransit
import com.vayunmathur.maps.data.gtfsRouteTypeToMode

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.map.GeoPoint
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * C5 transit branches: every [OfflineRouterTransit] entry point against a
 * planted (unreadable) sidecar feed, so the per-feed walks — departure
 * board paging, itinerary, rail lines, vehicles — run and degrade to
 * empty/null instead of throwing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapsTransitC5Test {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun nativeAvailable(): Boolean = try {
        @Suppress("UNUSED_EXPRESSION")
        OfflineRouter.basePath
        true
    } catch (_: Throwable) {
        false
    }

    @Test
    fun fakeFeed_entryPointsDegradeThroughPerFeedWalks() {
        org.junit.Assume.assumeTrue("host offlinerouter lib unavailable", nativeAvailable())
        runBlocking {
            val base = OfflineRouterLifecycle.transitBase(ctx)
            assertTrue(base != null)
            val feedFile = File(base, "c5feed.transit")
            feedFile.writeBytes(ByteArray(0))
            try {
                OfflineRouterTransit.onReload()
                // Departure board: with and without the paging window.
                assertTrue(
                    OfflineRouterTransit.getStopDeparturesOffline(ctx, 37.8, -122.4).isEmpty(),
                )
                assertTrue(
                    OfflineRouterTransit.getStopDeparturesOffline(
                        ctx, 37.8, -122.4, max = 5,
                        anchor = Instant.now().minusSeconds(3_600),
                        until = Instant.now().plusSeconds(3_600),
                    ).isEmpty(),
                )
                assertTrue(
                    OfflineRouterTransit.getStopDeparturesOffline(
                        ctx, 37.8, -122.4, max = 0,
                        until = Instant.now().plusSeconds(60),
                    ).isEmpty(),
                )
                // Itinerary, nearest stop, vehicles, rail and stop lines.
                assertNull(OfflineRouterTransit.tripItinerary(ctx, 42L, 37.8, -122.4))
                assertNull(OfflineRouterTransit.nearestStop(ctx, 37.8, -122.4))
                assertTrue(
                    OfflineRouterTransit.activeVehicles(ctx, 37.0, -123.0, 38.0, -122.0).isEmpty(),
                )
                assertTrue(
                    OfflineRouterTransit.railLines(ctx, 37.0, -123.0, 38.0, -122.0).isEmpty(),
                )
                assertTrue(OfflineRouterTransit.stopLines(ctx, 37.8, -122.4).isEmpty())
                assertNull(
                    OfflineRouterTransit.getTransitRouteOffline(
                        ctx, GeoPoint(-122.4, 37.8), GeoPoint(-122.3, 37.9),
                    ),
                )
            } finally {
                feedFile.delete()
                OfflineRouterTransit.onReload()
            }
            Unit
        }
    }

    @Test
    fun overlayAndModeHelpers() {
        assertTrue(OfflineRouterTransit.Overlay.EMPTY.isEmpty)
        val overlay = OfflineRouterTransit.Overlay(
            doubleArrayOf(37.0, -122.0), arrayOf("Muni"), intArrayOf(1, 2, 3),
        )
        assertEquals(false, overlay.isEmpty)
        assertEquals(3, overlay.times.size)
        // Every GTFS route type in and out of range maps to a label.
        for (t in intArrayOf(-1, 0, 1, 2, 3, 4, 5, 6, 7, 11, 100, 900, 1500)) {
            assertTrue(gtfsRouteTypeToMode(t).isNotBlank(), "type $t")
        }
        assertEquals(
            gtfsRouteTypeToMode(3),
            gtfsRouteTypeToMode(3),
        )
    }
}
