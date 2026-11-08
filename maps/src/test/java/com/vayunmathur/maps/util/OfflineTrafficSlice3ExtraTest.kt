package com.vayunmathur.maps.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Slice 3/3 extra: OfflineRouterTraffic display cache state machine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineTrafficSlice3ExtraTest {

    private fun comps(ids: LongArray, ratios: ByteArray) =
        OfflineRouterTraffic.TrafficComponents(ids, ratios)

    @Test fun storeMergeDedupAndReload() { runBlocking {
        OfflineRouterTraffic.onReload()
        assertTrue(OfflineRouterTraffic.trafficComponents.value.ids.isEmpty())

        OfflineRouterTraffic.storeSquare(1, comps(longArrayOf(10, 20), byteArrayOf(50, 0)))
        var snap = OfflineRouterTraffic.trafficComponents.value
        assertEquals(listOf(10L, 20L), snap.ids.toList())
        assertEquals(listOf(50.toByte(), 0.toByte()), snap.ratioPct.toList())

        // Re-storing identical content must not republish (same snapshot instance)
        val before = OfflineRouterTraffic.trafficComponents.value
        OfflineRouterTraffic.storeSquare(1, comps(longArrayOf(10, 20), byteArrayOf(50, 0)))
        assertTrue(before === OfflineRouterTraffic.trafficComponents.value)

        // A second square concatenates; a changed first square republishes merged
        OfflineRouterTraffic.storeSquare(2, comps(longArrayOf(30), byteArrayOf(90)))
        snap = OfflineRouterTraffic.trafficComponents.value
        assertEquals(3, snap.ids.size)
        assertTrue(snap.ids.toList().containsAll(listOf(10L, 20L, 30L)))

        OfflineRouterTraffic.storeSquare(1, comps(longArrayOf(10), byteArrayOf(25)))
        snap = OfflineRouterTraffic.trafficComponents.value
        assertEquals(25.toByte(), snap.ratioPct[snap.ids.indexOf(10L)])

        // notifyTrafficUpdated bumps the version asynchronously
        val v0 = OfflineRouterTraffic.trafficVersion.value
        OfflineRouterTraffic.notifyTrafficUpdated()
        withTimeout(8000) { while (OfflineRouterTraffic.trafficVersion.value <= v0) delay(25) }

        OfflineRouterTraffic.onReload()
        assertTrue(OfflineRouterTraffic.trafficComponents.value.ids.isEmpty())
        assertTrue(OfflineRouterTraffic.TrafficComponents.EMPTY.ids.isEmpty())
        Unit
    } }

    @Test fun fetchTrafficDataWithoutNetworkDegrades() {
        // No validated network in the sandbox: the fetch must finish quietly
        // (exception swallowed, native finish callback is a host-lib no-op surface)
        OfflineRouterTrafficFetch.fetchTrafficData(37.0, -123.0, 38.0, -122.0, packedSquare = 123, forceAsync = true)
        OfflineRouterTrafficFetch.fetchTrafficData(37.0, -123.0, 38.0, -122.0, packedSquare = 124, forceAsync = false)
    }
}
