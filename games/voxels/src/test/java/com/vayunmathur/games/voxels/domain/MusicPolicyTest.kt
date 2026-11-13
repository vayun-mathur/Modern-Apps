package com.vayunmathur.games.voxels.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MusicPolicyTest {
    @Test fun `music stops when the game leaves the screen`() {
        assertFalse(musicShouldPlay(foreground = false, focus = MusicFocus.GAINED))
    }

    @Test fun `music pauses for calls and other players`() {
        assertFalse(musicShouldPlay(true, MusicFocus.LOST_TRANSIENT))
        assertFalse(musicShouldPlay(true, MusicFocus.LOST))
        assertFalse(musicShouldPlay(true, MusicFocus.NONE))
    }

    @Test fun `music plays in the foreground with focus, quietly when ducked`() {
        assertTrue(musicShouldPlay(true, MusicFocus.GAINED))
        assertTrue(musicShouldPlay(true, MusicFocus.DUCKED))
        assertEquals(1f, musicVolumeScale(MusicFocus.GAINED))
        assertTrue(musicVolumeScale(MusicFocus.DUCKED) < 0.5f)
    }
}
