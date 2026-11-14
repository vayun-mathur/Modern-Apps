package com.vayunmathur.auto.protocol

/**
 * Unwraps the 0x8004 `MediaAck` frame counter.
 *
 * The wire `ack` field is a frame counter **mod 256**: it wraps back to 0 every
 * 256 frames, so a raw value below the previous one is a wrap, not a
 * regression. This folds each arrival into a monotonically non-decreasing
 * sequence the phone UI can track; stale arrivals (reordered or duplicated
 * acks) hold the line instead of moving it backwards.
 *
 * Pure state machine, no I/O: the Android side feeds it parsed ack values and
 * publishes the unwrapped sequence.
 */
class AckTracker {
    /** Last unwrapped counter, or null before the first ack carrying the field. */
    var lastUnwrapped: Long? = null
        private set

    /**
     * Folds [raw] (the unsigned-extended `ack` field) into the sequence.
     *
     * Forward distance on the wire decides wrap versus stale: a small step
     * (at most half the modulus, residue dropped) is the head unit moving on
     * across the 255→0 wrap; a large apparent jump backwards is a reordered
     * or duplicated older arrival and holds the line.
     *
     * @return the new sequence head, or the previous head when [raw] is stale.
     */
    fun onAck(raw: Long): Long {
        val value = raw and (MOD - 1)
        val previous = lastUnwrapped
        if (previous == null) {
            lastUnwrapped = value
            return value
        }
        val prevResidue = previous and (MOD - 1)
        val forward = ((value - prevResidue) % MOD + MOD) % MOD
        val candidate = when {
            forward == 0L -> previous
            forward <= MOD / 2 ->
                previous - prevResidue + value + if (value < prevResidue) MOD else 0L
            else -> previous
        }
        if (candidate > previous) {
            lastUnwrapped = candidate
        }
        return checkNotNull(lastUnwrapped)
    }

    fun reset() {
        lastUnwrapped = null
    }

    /** Wire constants for the mod-256 `ack` frame counter. */
    companion object {
        /** Modulus of the 0x8004 `ack` frame counter. */
        const val MOD = 256L
    }
}
