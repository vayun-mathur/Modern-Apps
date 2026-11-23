package com.vayunmathur.camera.domain

import java.util.concurrent.atomic.AtomicInteger

/**
 * Admission control for processed stills (above all AVIF encodes) still
 * being saved after their shutter was released.
 *
 * Each pending save holds its source (a full-res bitmap, ~50 MB at
 * 12 MP, or the source JPEG bytes) until the encode lands, so the number
 * in flight must stay small. A shot reserves a slot once its source is
 * secured and releases it once its save finishes (or fails); when no
 * slot is free the caller keeps the old synchronous save instead of
 * queueing unbounded work — the shot is never dropped and never silently
 * rerouted to another format. Thread-safe: reserved on the capture
 * callback, released from the save worker. Same admission contract as
 * [MotionSaveQueue], separate instance so Motion and still saves bound
 * independently.
 */
class StillSaveQueue(val capacity: Int) {

    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    private val inFlight = AtomicInteger(0)

    /** Saves currently reserved (secured, not yet saved). */
    val pending: Int get() = inFlight.get()

    /** Claims a slot; false (and nothing claimed) when [capacity] saves are already pending. */
    fun tryReserve(): Boolean {
        while (true) {
            val current = inFlight.get()
            if (current >= capacity) return false
            if (inFlight.compareAndSet(current, current + 1)) return true
        }
    }

    /** Returns a slot claimed by [tryReserve]. Never drops below zero. */
    fun release() {
        inFlight.updateAndGet { (it - 1).coerceAtLeast(0) }
    }
}
