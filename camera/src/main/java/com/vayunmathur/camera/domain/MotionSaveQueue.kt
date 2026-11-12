package com.vayunmathur.camera.domain

import java.util.concurrent.atomic.AtomicInteger

/**
 * Admission control for Motion Photos still being built after their shutter was released.
 *
 * Each pending save holds its drained ring-buffer frames (up to 24 analysis bitmaps, ~5 MB each)
 * until the clip is encoded, so the number in flight must stay small. A shot reserves a slot
 * before it is captured and releases it once its save finishes (or fails); when no slot is free
 * the caller takes a plain still instead of blocking the shutter. Thread-safe: reserved on the
 * main thread, released from the save worker.
 */
class MotionSaveQueue(val capacity: Int) {

    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    private val inFlight = AtomicInteger(0)

    /** Saves currently reserved (captured or capturing, not yet saved). */
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
