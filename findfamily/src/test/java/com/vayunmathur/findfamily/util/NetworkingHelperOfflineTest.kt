package com.vayunmathur.findfamily.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.data.Coord
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.havershine
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@RunWith(AndroidJUnit4::class)
class NetworkingHelperOfflineTest {

    @Test
    fun trackerHelpersFailClosedWithoutSession() = runTest {
        // wsSession is null until Networking's live loop connects: every helper must
        // fail closed immediately without touching the network.
        assertEquals(null, Networking.wsSession)
        assertFalse(Networking.registerTracker(42L, byteArrayOf(1, 2), byteArrayOf(3)))
        assertNull(Networking.resolveTrackerBundle(ByteArray(16) { it.toByte() }))
        assertFalse(Networking.uploadTrackerReport(ByteArray(16), byteArrayOf(9)))
        assertTrue(Networking.fetchTrackerReports(listOf(ByteArray(16))).isEmpty())
        assertTrue(Networking.fetchTrackerReports(emptyList()).isEmpty())
        assertFalse(Networking.registerPoweredOffEids(7L, listOf(ByteArray(16)), byteArrayOf(1)))
        assertFalse(Networking.registerPoweredOffEids(7L, emptyList(), byteArrayOf(1)))
        assertFalse(Networking.liveConnected)
    }

    @Test
    fun withPairIdAttachesClaimedId() {
        val loc = LocationValue(
            userid = 5L, coord = Coord(1.0, 2.0), speed = 0f, acc = 5f,
            timestamp = Instant.fromEpochMilliseconds(1000), battery = 50f,
            reportedAt = Instant.fromEpochMilliseconds(1000),
        )
        val fix = loc.withPairId("pair-xyz")
        assertEquals(loc, fix.location)
        assertEquals("pair-xyz", fix.pairId)
        assertNull(loc.withPairId(null).pairId)
    }

    @Test
    fun havershineDistances() {
        val a = Coord(51.5, -0.12)
        assertEquals(0.0, havershine(a, a), 0.001)
        val d = havershine(a, Coord(52.5, -0.12))
        assertTrue(d > 100_000 && d < 120_000, "1 degree latitude should be ~111km, was $d")
    }
}
