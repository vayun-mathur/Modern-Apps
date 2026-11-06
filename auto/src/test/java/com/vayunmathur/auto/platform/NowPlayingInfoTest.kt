package com.vayunmathur.auto.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Auto module: [NowPlayingInfo] — the media snapshot. [shouldShowCard] gates
 * the car card (playing always shows, paused-with-a-title stays for instant
 * resume, idle hides), and equality compares artwork by content with the icon
 * by identity.
 */
class NowPlayingInfoTest {

    private fun info(
        title: String? = "Song",
        artist: String? = "Band",
        playing: Boolean = true,
    ) = NowPlayingInfo(
        title = title,
        artist = artist,
        playing = playing,
        positionMs = 0L,
        durationMs = null,
    )

    @Test
    fun `playing always shows the card`() {
        assertTrue(info(playing = true).shouldShowCard())
        assertTrue(info(title = null, playing = true).shouldShowCard())
    }

    @Test
    fun `paused with a title stays for instant resume`() {
        assertTrue(info(title = "Song", playing = false).shouldShowCard())
        assertTrue(info(title = "  ", playing = false).shouldShowCard().not())
    }

    @Test
    fun `idle with no title hides the card`() {
        assertFalse(info(title = null, playing = false).shouldShowCard())
        assertFalse(info(title = "", playing = false).shouldShowCard())
    }

    @Test
    fun `equal snapshots match and differ on scalar changes`() {
        assertEquals(info(), info())
        assertNotEquals(info(), info(title = "Other"))
        assertNotEquals(info(), info(artist = "Other"))
        assertNotEquals(info(), info(playing = false))
        assertNotEquals(info().copy(positionMs = 1L), info().copy(positionMs = 2L))
        assertNotEquals(info().copy(hasNext = true), info())
    }

    @Test
    fun `artwork compares by content while the icon compares by identity`() {
        val a = info().copy(artworkData = byteArrayOf(1, 2, 3))
        assertEquals(a, info().copy(artworkData = byteArrayOf(1, 2, 3)))
        assertNotEquals(a, info().copy(artworkData = byteArrayOf(1, 2, 4)))
        assertNotEquals(a, info())
        assertEquals(a.hashCode(), info().copy(artworkData = byteArrayOf(1, 2, 3)).hashCode())
    }
}
