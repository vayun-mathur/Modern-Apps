package com.vayunmathur.games.voxels.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorldTouchTrackerTest {
    private fun tracker() = WorldTouchTracker(slopPx = 10f, longPressMs = 400, repeatMs = 300)

    @Test fun `a quick still tap places`() {
        val t = tracker()
        t.down(1, 100f, 100f, 0, lookSide = true)
        assertTrue(t.tick(200).isEmpty())
        assertEquals(listOf<WorldTouch>(WorldTouch.Tap(100f, 100f)), t.up(1))
    }

    @Test fun `holding still acts after the timeout and keeps repeating`() {
        val t = tracker()
        t.down(1, 50f, 60f, 0, lookSide = false)
        assertTrue(t.tick(399).isEmpty())
        assertEquals(listOf<WorldTouch>(WorldTouch.Act(50f, 60f)), t.tick(400))
        assertTrue(t.tick(650).isEmpty())
        assertEquals(0.5f, assertNotNull(t.hold(550)).progress, 0.01f)
        assertEquals(listOf<WorldTouch>(WorldTouch.Act(50f, 60f)), t.tick(700))
        assertEquals(listOf<WorldTouch>(WorldTouch.Act(50f, 60f)), t.tick(1000))
        assertEquals(listOf<WorldTouch>(WorldTouch.ActEnd), t.up(1))
        assertNull(t.hold(1100))
    }

    @Test fun `a finger that moves past the slop never acts, and looks on the look side`() {
        val t = tracker()
        t.down(1, 300f, 100f, 0, lookSide = true)
        assertTrue(t.move(1, 305f, 100f).isEmpty(), "inside the slop")
        val started = t.move(1, 330f, 100f)
        assertEquals(WorldTouch.LookStart(300f, 100f), started.first())
        assertTrue(t.looking)
        assertTrue(t.tick(5000).isEmpty(), "a look drag turned into a long press")
        assertEquals(listOf<WorldTouch>(WorldTouch.LookMove(340f, 110f, 10f, 10f)), t.move(1, 340f, 110f))
        assertEquals(listOf<WorldTouch>(WorldTouch.LookEnd), t.up(1))
    }

    @Test fun `a drag on the other side is ignored, not a tap or a hold`() {
        val t = tracker()
        t.down(1, 10f, 10f, 0, lookSide = false)
        assertTrue(t.move(1, 40f, 10f).isEmpty())
        assertTrue(t.tick(1000).isEmpty())
        assertTrue(t.up(1).isEmpty())
    }

    @Test fun `a second finger can tap and hold while the first one looks`() {
        val t = tracker()
        t.down(1, 300f, 100f, 0, lookSide = true)
        t.move(1, 340f, 100f)
        t.down(2, 320f, 200f, 10, lookSide = true)
        assertEquals(listOf<WorldTouch>(WorldTouch.Tap(320f, 200f)), t.up(2))
        t.down(3, 330f, 220f, 20, lookSide = true)
        // Only one look pointer: a second drag on the look side is ignored rather than stealing it.
        t.down(4, 400f, 50f, 20, lookSide = true)
        assertTrue(t.move(4, 450f, 50f).isEmpty())
        assertEquals(listOf<WorldTouch>(WorldTouch.Act(330f, 220f)), t.tick(420))
        assertTrue(t.looking)
    }

    @Test fun `cancel ends holds and looks`() {
        val t = tracker()
        t.down(1, 300f, 100f, 0, lookSide = true)
        t.move(1, 340f, 100f)
        t.down(2, 10f, 10f, 0, lookSide = false)
        t.tick(500)
        assertEquals(listOf(WorldTouch.ActEnd, WorldTouch.LookEnd), t.cancel())
        assertTrue(t.up(1).isEmpty())
    }
}
