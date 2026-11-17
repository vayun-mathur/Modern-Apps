package com.vayunmathur.cast.protocol

/**
 * A frame's identifier.
 *
 * **Not an 8-bit wrapping value**, which is the trap here. openscreen's `FrameId`
 * (`cast/streaming/public/frame_id.h`) extends `ExpandedValueBase<int64_t>`: the counter is 64-bit
 * and only *truncated* to 8 bits for the RTP header, with the receiver re-expanding it. Modelling
 * Modelling
 * the counter itself as a byte would still produce correct headers but the wrong encryption IV
 * from frame 256 onward, because [Crypto] derives the IV from [lower32] of the full value.
 *
 * `first()` is 0 and `leader()` is -1 ("the frame before the first"), both verbatim from
 * openscreen; `leader` is what a key frame references, since it has no real predecessor.
 */
@JvmInline
value class FrameId(val value: Long) : Comparable<FrameId> {

    /** What goes in the RTP header's Frame ID and Reference Frame ID fields. */
    val lower8: Int get() = (value and 0xff).toInt()

    /** What the encryption IV is derived from. */
    val lower32: Long get() = value and 0xffff_ffffL

    operator fun plus(delta: Long): FrameId = FrameId(value + delta)

    operator fun minus(delta: Long): FrameId = FrameId(value - delta)

    operator fun minus(other: FrameId): Long = value - other.value

    override fun compareTo(other: FrameId): Int = value.compareTo(other.value)

    override fun toString(): String = value.toString()

    /** Well-known [FrameId] values for the first and leader frames. */
    companion object {
        /** `FrameId::first()`. */
        val First = FrameId(0)

        /** `FrameId::leader()` - the reference for a frame that depends on nothing. */
        val Leader = FrameId(-1)
    }
}
