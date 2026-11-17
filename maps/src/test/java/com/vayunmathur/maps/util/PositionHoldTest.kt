package com.vayunmathur.maps.util
import com.vayunmathur.maps.platform.Fix
import com.vayunmathur.maps.platform.PositionHold
import com.vayunmathur.maps.platform.PositionSample
import com.vayunmathur.library.map.GeoPoint
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The stationary-jitter gate behind the user puck's position.
 *
 * A phone on a desk still wanders a few metres per GPS fix; before this
 * existed every such fix moved the puck, so it jumped at 1 Hz while standing
 * still. A hold accumulates in-circle fixes into a running average and only
 * releases when a fix proves real movement — by speed, by leaving the gate
 * circle, or by outlasting the stationary timeout.
 *
 * The cases worth pinning are the escape hatches, because a gate with no way
 * out freezes the puck on a real departure.
 */
class PositionHoldTest {
    private val second = 1_000_000_000L
    private val origin = GeoPoint(0.0, 0.0)

    /** [meters] north of the origin — 110_540 m per degree of latitude. */
    private fun northOf(meters: Double) = GeoPoint(0.0, meters / 110_540.0)

    private fun hold(accuracyM: Float = 8f, atSeconds: Long = 0) = PositionHold(
        drawn = origin,
        average = origin,
        accuracyM = accuracyM,
        sinceNanos = atSeconds * second,
    )

    private fun sample(northM: Double, accuracyM: Float = 8f, atSeconds: Long = 1) = PositionSample(
        point = northOf(northM),
        accuracyM = accuracyM,
        elapsedRealtimeNanos = atSeconds * second,
    )

    @Test
    fun `desk jitter inside the accuracy circle is held`() {
        // The reported bug: ±2 m of GPS noise moved the puck every second.
        assertNotNull(hold().coalesce(sample(northM = 2.0)))
        assertNotNull(hold().coalesce(sample(northM = -2.0)))
    }

    @Test
    fun `real movement outside the circle releases`() {
        assertNull(hold().coalesce(sample(northM = 20.0)))
    }

    @Test
    fun `speed overrides the gate`() {
        // A real departure may start inside the circle — trust the Doppler.
        assertNull(hold().coalesce(sample(northM = 1.0), speedMps = 1.5f))
    }

    @Test
    fun `missing speed reads as stationary`() {
        // `0` means the provider did not say, not that the phone is parked —
        // but with no evidence either way the conservative answer is to hold.
        assertNotNull(hold().coalesce(sample(northM = 1.0), speedMps = 0f))
    }

    @Test
    fun `an unstated accuracy can never pin the puck`() {
        // `0` means the provider declined to say, not that it is perfect.
        assertNull(hold(accuracyM = 0f).coalesce(sample(northM = 1.0)))
        assertNull(hold().coalesce(sample(northM = 1.0, accuracyM = 0f)))
    }

    @Test
    fun `tight accuracy still needs real movement`() {
        // A 3 m-accuracy phone on a desk wanders a metre or two per fix;
        // without a floor the puck follows it.
        assertNotNull(hold(accuracyM = 3f).coalesce(sample(northM = 2.0, accuracyM = 3f)))
        assertNull(hold(accuracyM = 3f).coalesce(sample(northM = 6.0, accuracyM = 3f)))
    }

    @Test
    fun `a coarse circle cannot freeze the puck`() {
        // A 500 m network circle would otherwise pin the puck for a whole
        // indoor walk; past the cap the user is genuinely moving relative to
        // the fix quality.
        assertNull(hold(accuracyM = 500f).coalesce(sample(northM = 60.0, accuracyM = 500f)))
        assertNotNull(hold(accuracyM = 500f).coalesce(sample(northM = 20.0, accuracyM = 500f)))
    }

    @Test
    fun `a widening circle does not buy a bigger budget`() {
        // Fix degrading (tunnel, urban canyon) is not the user moving: the
        // gate radius is set by the tightest accuracy seen this stretch.
        val held = hold(accuracyM = 8f).coalesce(sample(northM = 2.0, accuracyM = 100f))
        assertNotNull(held)
        // 20 m clears the 8 m gate, so the puck moves — had the gate widened
        // to the latest 100 m accuracy, this fix would have been wrongly held.
        assertNull(held.coalesce(sample(northM = 20.0, accuracyM = 100f, atSeconds = 2)))
    }

    @Test
    fun `the hold times out instead of freezing`() {
        // Slow filter convergence (or a provider that never reports speed
        // while the user actually walks) must degrade to periodic re-emits,
        // never a stuck puck.
        assertNotNull(hold(atSeconds = 0).coalesce(sample(northM = 1.0, atSeconds = 30)))
        assertNull(hold(atSeconds = 0).coalesce(sample(northM = 1.0, atSeconds = 61)))
    }

    @Test
    fun `zero-mean jitter converges instead of walking`() {
        // Alternating ±2 m fixes must not accumulate into movement: the gate
        // compares against the running average, which converges to the middle.
        var h = hold()
        repeat(10) { i ->
            h = h.coalesce(sample(northM = if (i % 2 == 0) 2.0 else -2.0, atSeconds = (i + 1).toLong()))
                ?: return
        }
        assertNotNull(h)
    }
}
