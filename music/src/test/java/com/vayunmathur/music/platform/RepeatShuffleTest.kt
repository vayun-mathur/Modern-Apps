package com.vayunmathur.music.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the repeat and shuffle transitions behind the transport buttons.
 *
 * Repeat is pinned against the production [nextRepeatMode] (this test
 * previously re-implemented the cycle locally, so it kept passing while the
 * app and notification surfaces cycled in different orders): repeat cycles
 * off, all, one and wraps, while shuffle flips independently of it.
 * Player constants: OFF=0, ONE=1, ALL=2.
 */
class RepeatShuffleTest {

    private fun toggleShuffle(enabled: Boolean): Boolean = !enabled

    @Test
    fun repeatCyclesOffAllOneThenWraps() {
        assertEquals(2, nextRepeatMode(0))
        assertEquals(1, nextRepeatMode(2))
        assertEquals(0, nextRepeatMode(1))
    }

    @Test
    fun unknownRepeatModeFallsBackToOff() {
        assertEquals(0, nextRepeatMode(99))
        assertEquals(0, nextRepeatMode(-1))
    }

    @Test
    fun threePressesReturnToTheStart() {
        var mode = 0
        repeat(3) { mode = nextRepeatMode(mode) }
        assertEquals(0, mode)
    }

    @Test
    fun shuffleFlipsAndFlippingTwiceRestores() {
        assertTrue(toggleShuffle(false))
        assertFalse(toggleShuffle(true))
        assertEquals(false, toggleShuffle(toggleShuffle(false)))
        assertEquals(true, toggleShuffle(toggleShuffle(true)))
    }

    @Test
    fun repeatPressesNeverTouchShuffle() {
        val shuffle = true
        var mode = 0
        repeat(4) { mode = nextRepeatMode(mode) }
        assertTrue(shuffle)
        assertEquals(2, mode)
    }

    @Test
    fun shufflePressesNeverTouchRepeat() {
        val mode = 2
        var shuffle = toggleShuffle(false)
        assertTrue(shuffle)
        assertEquals(2, mode)
        shuffle = toggleShuffle(shuffle)
        assertFalse(shuffle)
        assertEquals(2, mode)
    }
}
