package com.vayunmathur.maps.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.map.GeoPoint
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Slice 3/3 extra: [OfflineRouterTransit] degradation paths. The on-device
 * transit pack does not exist in the test sandbox, so every entry point must
 * answer null/empty rather than throw - that contract is what callers rely on
 * to fall back to the online planner.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineRouterTransitSlice3ExtraTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun nativeAvailable(): Boolean = try {
        // Loading the object runs its System.loadLibrary init.
        @Suppress("UNUSED_EXPRESSION")
        OfflineRouter.basePath
        true
    } catch (_: Throwable) { false }

    @Test fun gtfsRouteTypeModes() {
        // Pure mapping, independent of the native pack
        val modes = (0..8).map { OfflineRouterTransit.gtfsRouteTypeToMode(it) }
        assertTrue(modes.all { it.isNotBlank() }, "$modes")
        assertEquals(OfflineRouterTransit.gtfsRouteTypeToMode(3), OfflineRouterTransit.gtfsRouteTypeToMode(3))
        assertTrue(OfflineRouterTransit.gtfsRouteTypeToMode(999).isNotBlank())
        assertTrue(OfflineRouterTransit.Overlay.EMPTY.isEmpty)
    }

    @Test fun noPackEntryPointsDegrade() { runBlocking {
        Assume.assumeTrue("host offlinerouter lib unavailable", nativeAvailable())
        // Force re-init against the sandbox external dir (no packs were ever downloaded)
        OfflineRouterLifecycle.isInitialized = false
        OfflineRouterTransit.onReload()
        val start = GeoPoint(-122.4, 37.8); val end = GeoPoint(-122.3, 37.9)
        assertNull(OfflineRouterTransit.getTransitRouteOffline(ctx, start, end))
        assertNull(OfflineRouterTransit.nearestStop(ctx, 37.8, -122.4))
        assertTrue(OfflineRouterTransit.activeVehicles(ctx, 37.0, -123.0, 38.0, -122.0).isEmpty())
        assertTrue(OfflineRouterTransit.railLines(ctx, 37.0, -123.0, 38.0, -122.0).isEmpty())
        assertTrue(OfflineRouterTransit.stopLines(ctx, 37.8, -122.4).isEmpty())
        assertNull(OfflineRouterTransit.tripItinerary(ctx, 42L, 37.8, -122.4))
        assertTrue(OfflineRouterTransit.getStopDeparturesOffline(ctx, 37.8, -122.4).isEmpty())
        assertTrue(OfflineRouterTransit.getStopDeparturesOffline(ctx, 37.8, -122.4, max = 5, until = java.time.Instant.now().plusSeconds(3600)).isEmpty())
        Unit
    } }

    @Test fun fakeFeedFileDegradesThroughNative() { runBlocking {
        Assume.assumeTrue("host offlinerouter lib unavailable", nativeAvailable())
        val base = OfflineRouterLifecycle.transitBase(ctx)
        assertNotNull(base)
        // A zero-byte sidecar pack makes transitFeeds report this feed; every native
        // query against the unreadable pack must still degrade, not throw.
        File(base, "fakefeed.transit").writeBytes(ByteArray(0))
        try {
            OfflineRouterTransit.onReload()
            assertNull(OfflineRouterTransit.getTransitRouteOffline(ctx, GeoPoint(-122.4, 37.8), GeoPoint(-122.3, 37.9)))
            assertNull(OfflineRouterTransit.nearestStop(ctx, 37.8, -122.4))
            assertTrue(OfflineRouterTransit.railLines(ctx, 37.0, -123.0, 38.0, -122.0).isEmpty())
            assertTrue(OfflineRouterTransit.stopLines(ctx, 37.8, -122.4).isEmpty())
            assertTrue(OfflineRouterTransit.activeVehicles(ctx, 37.0, -123.0, 38.0, -122.0).isEmpty())
        } finally {
            File(base, "fakefeed.transit").delete()
            OfflineRouterTransit.onReload()
        }
        Unit
    } }
}
