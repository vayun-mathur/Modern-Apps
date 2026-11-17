package com.vayunmathur.maps.util
import com.vayunmathur.maps.platform.Fix
import com.vayunmathur.maps.platform.acceptsFix
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The provider-preference filter behind the user puck's position.
 *
 * GPS and network are registered on the same listener, so before this existed every network fix
 * overwrote the GPS one from a moment earlier and the puck alternated between the road and a few
 * hundred metres off it. The rules are pure arithmetic over a provider flag, an accuracy and a
 * monotonic timestamp, so they test without a device.
 *
 * The cases worth pinning are the two escape hatches, because a GPS-always-wins rule that has no
 * way out strands the puck at the last rooftop the phone saw before walking indoors.
 */
class AcceptsFixTest {
    private val second = 1_000_000_000L

    private fun gps(accuracyM: Float, atSeconds: Long) =
        Fix(fromGps = true, accuracyM = accuracyM, elapsedRealtimeNanos = atSeconds * second)

    private fun network(accuracyM: Float, atSeconds: Long) =
        Fix(fromGps = false, accuracyM = accuracyM, elapsedRealtimeNanos = atSeconds * second)

    @Test
    fun `the first fix is always taken`() {
        assertTrue(acceptsFix(null, network(500f, 0)))
    }

    @Test
    fun `gps always displaces network`() {
        assertTrue(acceptsFix(network(30f, 0), gps(50f, 1)))
    }

    @Test
    fun `a fresh gps fix is not displaced by a coarse network one`() {
        // The reported bug: 5 m of GPS thrown away for 500 m of cell tower, once a second.
        assertFalse(acceptsFix(gps(5f, 0), network(500f, 1)))
    }

    @Test
    fun `network replaces network`() {
        assertTrue(acceptsFix(network(300f, 0), network(400f, 1)))
    }

    @Test
    fun `network is taken once gps has gone quiet`() {
        // Ten seconds of silence: a stale position is worse than a coarse one.
        assertFalse(acceptsFix(gps(5f, 0), network(500f, 9)))
        assertTrue(acceptsFix(gps(5f, 0), network(500f, 10)))
    }

    @Test
    fun `network is taken when it is twice as accurate`() {
        // Indoors: GPS degrades to a wide multipath estimate while wifi stays tight.
        assertTrue(acceptsFix(gps(200f, 0), network(40f, 1)))
    }

    @Test
    fun `a marginally better network fix is not enough`() {
        // Comparable accuracy means GPS is still the more trustworthy of the two, and swapping
        // between them every second would jitter the puck for nothing.
        assertFalse(acceptsFix(gps(50f, 0), network(40f, 1)))
        assertFalse(acceptsFix(gps(50f, 0), network(26f, 1)))
        assertTrue(acceptsFix(gps(50f, 0), network(24f, 1)))
    }

    @Test
    fun `an unstated accuracy is not evidence of being better`() {
        // `0` means the provider declined to say, not that it is perfect.
        assertFalse(acceptsFix(gps(200f, 0), network(0f, 1)))
        assertFalse(acceptsFix(gps(0f, 0), network(10f, 1)))
    }

    @Test
    fun `staleness is measured between the fixes, not against now`() {
        // Both fixes are old in wall-clock terms; what matters is the gap between them, so the
        // answer cannot change just because the caller asked later.
        assertFalse(acceptsFix(gps(5f, 10_000), network(500f, 10_001)))
        assertTrue(acceptsFix(gps(5f, 10_000), network(500f, 10_020)))
    }
}
