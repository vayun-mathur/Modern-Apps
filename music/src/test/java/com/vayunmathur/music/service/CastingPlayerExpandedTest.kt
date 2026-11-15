package com.vayunmathur.music.service

import androidx.media3.common.Player
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * CastingPlayer's command gating under Robolectric, where Player.Commands is a
 * real Android-backed flag set (on a plain JVM it is a stubbed
 * SparseBooleanArray, which is why CastingPlayerTest cannot assert this).
 * Instantiating CastingPlayer itself needs a live media3 Player delegate and a
 * main looper session, so getState/handle* remain device/TV-only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CastingPlayerExpandedTest {

    private fun seekCommands(): Player.Commands = Player.Commands.Builder()
        .add(Player.COMMAND_PLAY_PAUSE)
        .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        .add(Player.COMMAND_SEEK_BACK)
        .add(Player.COMMAND_SEEK_FORWARD)
        .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        .add(Player.COMMAND_SET_SPEED_AND_PITCH)
        .build()

    @Test
    fun `seekable tracks keep the delegate commands untouched`() {
        val base = seekCommands()
        assertSame(base, CastingPlayer.seekCommands(base, seekable = true))
    }

    @Test
    fun `unseekable tracks lose only the within-item seek commands`() {
        val gated = CastingPlayer.seekCommands(seekCommands(), seekable = false)
        assertFalse(gated.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
        assertFalse(gated.contains(Player.COMMAND_SEEK_BACK))
        assertFalse(gated.contains(Player.COMMAND_SEEK_FORWARD))
        // Queue jumps and transport are the phone's, and must survive gating:
        // removing them would strand the user on a track that is still encoding.
        assertTrue(gated.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
        assertTrue(gated.contains(Player.COMMAND_PLAY_PAUSE))
        assertTrue(gated.contains(Player.COMMAND_SET_SPEED_AND_PITCH))
    }

    @Test
    fun `gating is driven by whether the playing resource is the growing one`() {
        val base = seekCommands()
        assertSame(base, CastingPlayer.seekCommands(base, CastingPlayer.seekable(null, "12")))
        assertSame(base, CastingPlayer.seekCommands(base, CastingPlayer.seekable("99", "12")))
        val gated = CastingPlayer.seekCommands(base, CastingPlayer.seekable("12", "12"))
        assertFalse(gated.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
        assertEquals(
            Player.STATE_ENDED,
            CastingPlayer.playbackStateFor(buffering = false, ended = true),
        )
    }
}
