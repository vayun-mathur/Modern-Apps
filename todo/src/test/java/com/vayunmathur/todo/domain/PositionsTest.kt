package com.vayunmathur.todo.domain

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PositionsTest {

    @Test
    fun `after and before step away from the edge`() {
        assertEquals(Positions.STEP, Positions.after(null))
        assertEquals(10.0 + Positions.STEP, Positions.after(10.0))
        assertEquals(10.0 - Positions.STEP, Positions.before(10.0))
    }

    @Test
    fun `between lands strictly inside the middle half of the gap`() {
        val random = Random(42)
        repeat(1000) {
            val p = Positions.between(0.0, 100.0, random)
            assertTrue(p > 25.0 && p < 75.0, "got $p")
        }
    }

    @Test
    fun `concurrent inserts between the same neighbours do not collide`() {
        val a = Positions.between(1.0, 2.0, Random(1))
        val b = Positions.between(1.0, 2.0, Random(2))
        assertTrue(a != b)
    }

    @Test
    fun `moving down lands between the target and the row after it`() {
        val sorted = listOf(10.0, 20.0, 30.0, 40.0)
        val p = Positions.forMove(sorted, from = 0, to = 2, random = Random(0))
        assertTrue(p > 30.0 && p < 40.0)
    }

    @Test
    fun `moving up lands between the row before the target and the target`() {
        val sorted = listOf(10.0, 20.0, 30.0, 40.0)
        val p = Positions.forMove(sorted, from = 3, to = 1, random = Random(0))
        assertTrue(p > 10.0 && p < 20.0)
    }

    @Test
    fun `moving to either end steps past the edge`() {
        val sorted = listOf(10.0, 20.0, 30.0)
        assertEquals(30.0 + Positions.STEP, Positions.forMove(sorted, from = 0, to = 2))
        assertEquals(10.0 - Positions.STEP, Positions.forMove(sorted, from = 2, to = 0))
    }

    @Test
    fun `colliding keys need renormalizing`() {
        assertTrue(Positions.needsRenormalize(listOf(0.0, 0.0, 0.0)))
        assertFalse(Positions.needsRenormalize(listOf(1.0, 2.0, 3.0)))
        assertFalse(Positions.needsRenormalize(emptyList()))
    }

    @Test
    fun `renormalized keys are evenly spaced and ascending`() {
        assertEquals(listOf(1024.0, 2048.0, 3072.0), Positions.renormalized(3))
    }
}
