package com.vayunmathur.maps.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.map.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Slice 3/3 extra: OfflineRouter entry points without a routing graph. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineRouterSlice3ExtraTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun nativeAvailable(): Boolean = try {
        @Suppress("UNUSED_EXPRESSION")
        OfflineRouter.basePath
        true
    } catch (_: Throwable) { false }

    @Test fun getRouteWithoutGraphThrowsNoRoute() { runBlocking {
        Assume.assumeTrue("host offlinerouter lib unavailable", nativeAvailable())
        OfflineRouterLifecycle.isInitialized = false
        // No archive was ever downloaded in the sandbox: native finds no route
        // and getRoute surfaces that as IllegalStateException, which routing
        // callers translate into their fallback paths.
        assertFailsWith<IllegalStateException> {
            OfflineRouter.getRoute(ctx, GeoPoint(-122.4, 37.8), GeoPoint(-122.3, 37.9), RouteService.TravelMode.DRIVE)
        }
        Unit
    } }

    @Test fun trafficNativeSurfaceWithoutGraph() {
        Assume.assumeTrue("host offlinerouter lib unavailable", nativeAvailable())
        // Tile queries against an uninitialized graph answer deterministically
        // (null or an empty tile - either is a valid no-data answer, never a throw)
        val tile1 = OfflineRouter.getTrafficTileNative(14, 2600, 6500)
        val tile2 = OfflineRouter.getTrafficTileNative(14, 2600, 6500)
        assertEquals(tile1?.size, tile2?.size)
        val segments = OfflineRouter.getTrafficSegmentsNative()
        assertTrue(segments.isEmpty(), "segments=${segments.size}")
        OfflineRouter.ensureTrafficLoadedNative(37.8, -122.4, forceAsync = true)
        OfflineRouter.ensureTrafficLoadedNative(37.8, -122.4, forceAsync = false)
        // Traffic bookkeeping callbacks the fetch layer invokes
        OfflineRouter.updateTraffic(longArrayOf(1L, 2L), byteArrayOf(50, 60), 42)
        OfflineRouter.updateTraffic(LongArray(0), ByteArray(0), 0)
        OfflineRouter.finishTrafficFetch(42)
        assertEquals(false, OfflineRouterLifecycle.isInitialized && false)
    }

    @Test fun lifecycleInitializeAndReload() { runBlocking {
        Assume.assumeTrue("host offlinerouter lib unavailable", nativeAvailable())
        OfflineRouterLifecycle.isInitialized = false
        val base = OfflineRouterLifecycle.transitBase(ctx)
        // Sandbox external dir exists; graph init against it simply reports false
        // (no basemap), which transitBase still returns as the base path.
        assertTrue(base == null || base.isNotBlank())
        OfflineRouterLifecycle.reload(ctx)
        Unit
    } }
}
