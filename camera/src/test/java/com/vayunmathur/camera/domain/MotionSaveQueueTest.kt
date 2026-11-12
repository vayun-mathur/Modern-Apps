package com.vayunmathur.camera.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MotionSaveQueueTest {

    @Test
    fun reservesUpToCapacityThenRefuses() {
        val q = MotionSaveQueue(2)
        assertTrue(q.tryReserve())
        assertTrue(q.tryReserve())
        assertFalse(q.tryReserve())
        assertEquals(2, q.pending)
    }

    @Test
    fun releaseFreesASlot() {
        val q = MotionSaveQueue(1)
        assertTrue(q.tryReserve())
        assertFalse(q.tryReserve())
        q.release()
        assertEquals(0, q.pending)
        assertTrue(q.tryReserve())
    }

    @Test
    fun extraReleaseNeverGoesNegative() {
        val q = MotionSaveQueue(1)
        q.release()
        assertEquals(0, q.pending)
        assertTrue(q.tryReserve())
        assertFalse(q.tryReserve())
    }

    @Test
    fun concurrentReservationsNeverExceedCapacity() {
        val q = MotionSaveQueue(3)
        val granted = java.util.concurrent.atomic.AtomicInteger()
        val threads = List(16) {
            Thread { repeat(100) { if (q.tryReserve()) granted.incrementAndGet() } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(3, granted.get())
        assertEquals(3, q.pending)
    }

    @Test
    fun rejectsNonPositiveCapacity() {
        assertFailsWith<IllegalArgumentException> { MotionSaveQueue(0) }
    }
}
