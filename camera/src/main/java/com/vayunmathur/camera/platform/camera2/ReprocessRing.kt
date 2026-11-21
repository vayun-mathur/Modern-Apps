package com.vayunmathur.camera.platform.camera2

import android.hardware.camera2.CameraMetadata

/**
 * Camera2 depth phase: own reprocess ZSL ring — pure core.
 *
 * A YUV `ImageReader` ring (N=[DEFAULT_CAPACITY]) is continuously filled
 * while the Camera2 session streams. On `capture()` the ring hands back an
 * already-exposed frame, so the shutter does not wait for AE convergence
 * or a fresh exposure (see [StillLatencyModel]). This file is the pure
 * half: frame descriptors, eviction/selection math and the latency model.
 * The Android holder (`YuvRingSource.kt`) and the session glue
 * (`Camera2Session.captureFromRing`) own the `Image` lifetimes.
 *
 * A second [ReprocessRing] of RAW frames (depth Phase 2, `RawRingSource.kt`)
 * now sits beside this one: the rings share this eviction/selection math
 * unchanged, one instance per format, selected by `RawNightPolicy`.
 */

/** Gate for the ring path. Default OFF, mirroring [NightCamera2Gate]. */
object ReprocessRingGate {
    const val ENABLED: Boolean = false
}

/** Pixel format of one ring. RAW entries exist only as the future slot. */
enum class RingFormat {
    YUV_420_888,
    PRIVATE,
    RAW10,
    RAW_SENSOR,
}

/** What one ring instance holds. [format] picks the future dual-ring slot. */
data class ReprocessRingConfig(
    val enabled: Boolean = false,
    val capacity: Int = DEFAULT_CAPACITY,
    val format: RingFormat = RingFormat.YUV_420_888,
) {
    init {
        require(capacity > 0) { "ring capacity must be positive" }
    }

    companion object {
        const val DEFAULT_CAPACITY: Int = 8
        val DISABLED = ReprocessRingConfig(enabled = false)
    }
}

/**
 * Metadata of one ring frame, as far as `CaptureResult` reported it.
 * [sensorTimestampNs] is the frame's SENSOR_TIMESTAMP (start of exposure
 * on current HALs); [exposureTimeNs] is SENSOR_EXPOSURE_TIME when read.
 */
data class RingFrameMeta(
    val sensorTimestampNs: Long,
    val exposureTimeNs: Long? = null,
    val frameNumber: Long? = null,
    val aeState: Int? = null,
    val iso: Int? = null,
    val width: Int = 0,
    val height: Int = 0,
    val format: RingFormat = RingFormat.YUV_420_888,
) {
    /** End of exposure, or null when the exposure time is unknown. */
    val exposureEndNs: Long? get() = exposureTimeNs?.let { sensorTimestampNs + it }

    /**
     * True when this frame was fully exposed at [atNs]. With a known
     * exposure time that is `timestamp + exposure <= at`; without one the
     * frame can only be proven incomplete when it starts after [atNs],
     * so a started frame counts as complete (unknown is not a veto).
     */
    fun isExposureComplete(atNs: Long): Boolean {
        val end = exposureEndNs ?: return sensorTimestampNs <= atNs
        return end <= atNs
    }

    /** AE had converged/locked for this frame, when AE state was reported. */
    val aeConverged: Boolean
        get() = aeState == null ||
            aeState == CameraMetadata.CONTROL_AE_STATE_CONVERGED ||
            aeState == CameraMetadata.CONTROL_AE_STATE_LOCKED
}

/** Why a ring selection came back empty. */
enum class RingMissReason { EMPTY, NO_EXPOSURE_COMPLETE_FRAME }

/** Outcome of [ReprocessRing.select]. */
sealed interface RingSelection<out T> {
    data class Hit<T>(val frame: T, val meta: RingFrameMeta, val ageAtShutterNs: Long) :
        RingSelection<T>

    data class Miss(val reason: RingMissReason) : RingSelection<Nothing>
}

/**
 * Bounded ring of the newest [capacity] frames, newest last.
 *
 * Drop policy mirrors Phase 3 (`AnalysisImageSource`): the producer uses
 * acquireLatest semantics, frames are never queued for later — offering
 * into a full ring evicts (and returns, so the owner can close) the
 * oldest frame and counts it in [evictedFrames]. Selection does not
 * remove the frame; the caller removes/closes it once consumed.
 *
 * Not thread-safe by itself: the session feeds and drains it on the
 * Camera2 executor thread only.
 */
class ReprocessRing<T>(
    val capacity: Int = ReprocessRingConfig.DEFAULT_CAPACITY,
) {
    init {
        require(capacity > 0) { "ring capacity must be positive" }
    }

    private class Entry<T>(val payload: T, var meta: RingFrameMeta, val seq: Long)

    private val entries = ArrayDeque<Entry<T>>()
    private var nextSeq: Long = 0

    var evictedFrames: Long = 0
        private set
    var offeredFrames: Long = 0
        private set

    val size: Int get() = entries.size
    fun isEmpty(): Boolean = entries.isEmpty()

    /** Metas currently held, oldest first. */
    fun metas(): List<RingFrameMeta> = entries.map { it.meta }

    /** Payload for an exact sensor timestamp, or null. */
    fun payloadFor(timestampNs: Long): T? =
        entries.firstOrNull { it.meta.sensorTimestampNs == timestampNs }?.payload

    /**
     * Adds [payload]; returns the evicted oldest payload (owner must
     * close it) or null. Out-of-order timestamps are kept in arrival
     * order — selection is by timestamp, not position.
     */
    fun offer(meta: RingFrameMeta, payload: T): T? {
        offeredFrames++
        entries.addLast(Entry(payload, meta, nextSeq++))
        if (entries.size <= capacity) return null
        evictedFrames++
        return entries.removeFirst().payload
    }

    /** Patches CaptureResult fields onto a frame that arrived first. */
    fun attachMetadata(
        sensorTimestampNs: Long,
        exposureTimeNs: Long? = null,
        frameNumber: Long? = null,
        aeState: Int? = null,
        iso: Int? = null,
    ): Boolean {
        val entry = entries.firstOrNull { it.meta.sensorTimestampNs == sensorTimestampNs }
            ?: return false
        entry.meta = entry.meta.copy(
            exposureTimeNs = exposureTimeNs ?: entry.meta.exposureTimeNs,
            frameNumber = frameNumber ?: entry.meta.frameNumber,
            aeState = aeState ?: entry.meta.aeState,
            iso = iso ?: entry.meta.iso,
        )
        return true
    }

    /**
     * Picks the frame for a shutter at [shutterTimestampNs]: among
     * exposure-complete frames (see [RingFrameMeta.isExposureComplete])
     * the one whose sensor timestamp is nearest the shutter; ties go to
     * the later frame. Incomplete frames are never picked while a
     * complete one exists, and with no complete frame this is a [Miss]
     * (the caller falls back to a normal capture, never a half-exposed
     * frame). AE state does not veto — a converged frame is preferred
     * only as a tie-break via the timestamp rule in practice; ZSL rings
     * upstream only hold converged frames, here the exposure check is
     * the honest gate we can prove from metadata.
     */
    fun select(shutterTimestampNs: Long): RingSelection<T> {
        if (entries.isEmpty()) return RingSelection.Miss(RingMissReason.EMPTY)
        val complete = entries.filter { it.meta.isExposureComplete(shutterTimestampNs) }
        if (complete.isEmpty()) {
            return RingSelection.Miss(RingMissReason.NO_EXPOSURE_COMPLETE_FRAME)
        }
        val best = complete.minWithOrNull(
            compareBy<Entry<T>>(
                { kotlin.math.abs(it.meta.sensorTimestampNs - shutterTimestampNs) },
                // Later timestamp, then later arrival: ties go to the newest.
                { -it.meta.sensorTimestampNs },
                { -it.seq },
            ),
        ) ?: return RingSelection.Miss(RingMissReason.EMPTY)
        return RingSelection.Hit(
            frame = best.payload,
            meta = best.meta,
            ageAtShutterNs = shutterTimestampNs - best.meta.sensorTimestampNs,
        )
    }

    /**
     * The newest [count] exposure-complete frames ending at or before
     * [atNs], oldest first (burst order for the RAW night merge, whose
     * first frame is the alignment reference). Fewer than [count] when
     * the ring holds fewer complete frames; never a still-exposing one.
     */
    fun latestCompleteBurst(count: Int, atNs: Long): List<Pair<T, RingFrameMeta>> {
        if (count <= 0) return emptyList()
        return entries.filter { it.meta.isExposureComplete(atNs) }
            .sortedBy { it.meta.sensorTimestampNs }
            .takeLast(count)
            .map { it.payload to it.meta }
    }

    /** Latest fully-exposed frame at [atNs], or null (the select fallback). */
    fun latestComplete(atNs: Long): Pair<T, RingFrameMeta>? =
        entries.filter { it.meta.isExposureComplete(atNs) }
            .maxByOrNull { it.meta.sensorTimestampNs }
            ?.let { it.payload to it.meta }

    /** Removes and returns one frame by timestamp (owner closes it). */
    fun remove(timestampNs: Long): T? {
        val index = entries.indexOfFirst { it.meta.sensorTimestampNs == timestampNs }
        if (index < 0) return null
        return entries.removeAt(index).payload
    }

    /** Drains every payload, oldest first (owner closes each). */
    fun drainAll(): List<T> {
        val all = entries.map { it.payload }
        entries.clear()
        return all
    }

    fun clear() {
        entries.clear()
    }
}

/** Latency split for one still, in milliseconds. Pure accounting. */
data class StillLatencyBreakdown(
    val aeConvergenceWaitMs: Long,
    val freshExposureMs: Long,
    val readoutEncodeMs: Long,
    val reprocessMs: Long,
) {
    val totalMs: Long get() = aeConvergenceWaitMs + freshExposureMs + readoutEncodeMs + reprocessMs
}

/**
 * Shutter-to-still latency model. The conventional Camera2/CameraX still
 * pays AE convergence plus a fresh exposure after the press; a ring
 * capture pays neither — the frame was already exposed while streaming —
 * only readout/encode (direct fallback) or reprocess+encode. This is
 * the win the ring exists for, stated as arithmetic tests can assert.
 */
object StillLatencyModel {

    fun conventional(
        aeConvergenceWaitMs: Long,
        exposureMs: Long,
        readoutEncodeMs: Long,
    ): StillLatencyBreakdown = StillLatencyBreakdown(
        aeConvergenceWaitMs = aeConvergenceWaitMs.coerceAtLeast(0),
        freshExposureMs = exposureMs.coerceAtLeast(0),
        readoutEncodeMs = readoutEncodeMs.coerceAtLeast(0),
        reprocessMs = 0,
    )

    /** Ring + HAL reprocess: no AE wait, no fresh exposure. */
    fun ringReprocess(reprocessMs: Long, readoutEncodeMs: Long): StillLatencyBreakdown =
        StillLatencyBreakdown(
            aeConvergenceWaitMs = 0,
            freshExposureMs = 0,
            readoutEncodeMs = readoutEncodeMs.coerceAtLeast(0),
            reprocessMs = reprocessMs.coerceAtLeast(0),
        )

    /** Ring + direct encode fallback: no AE wait, no fresh exposure. */
    fun ringDirectEncode(readoutEncodeMs: Long): StillLatencyBreakdown =
        StillLatencyBreakdown(
            aeConvergenceWaitMs = 0,
            freshExposureMs = 0,
            readoutEncodeMs = readoutEncodeMs.coerceAtLeast(0),
            reprocessMs = 0,
        )

    /** Milliseconds the ring path saves over [conventional]. */
    fun savedMs(conventional: StillLatencyBreakdown, ring: StillLatencyBreakdown): Long =
        conventional.totalMs - ring.totalMs
}
