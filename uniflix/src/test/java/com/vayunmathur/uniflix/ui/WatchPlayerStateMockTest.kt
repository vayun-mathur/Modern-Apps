package com.vayunmathur.uniflix.ui

import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.media3.common.text.Cue
import androidx.media3.exoplayer.ExoPlayer
import kotlin.test.Test
import kotlin.test.assertEquals
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/** WatchPlayerState transport logic against a mocked ExoPlayer. */
class WatchPlayerStateMockTest {

    private fun state(player: ExoPlayer?): WatchPlayerState = WatchPlayerState(
        player = player,
        positionState = mutableLongStateOf(1_000L),
        bufferedState = mutableLongStateOf(2_000L),
        durationState = mutableLongStateOf(60_000L),
        playingState = mutableStateOf(false),
        bufferingState = mutableStateOf(false),
        draggingState = mutableStateOf(false),
        cuesState = mutableStateOf<List<Cue>>(emptyList()),
        heightsState = mutableStateOf(listOf(1080)),
        errorState = mutableStateOf<String?>(null),
    )

    @Test fun togglePlaysWhenPausedAndPausesWhenPlaying() {
        val player = mock(ExoPlayer::class.java)
        `when`(player.isPlaying).thenReturn(false)
        state(player).togglePlay()
        verify(player).play()
        verify(player, never()).pause()

        val playing = mock(ExoPlayer::class.java)
        `when`(playing.isPlaying).thenReturn(true)
        state(playing).togglePlay()
        verify(playing).pause()
        verify(playing, never()).play()
    }

    @Test fun seekClampsNegativeToZero() {
        val player = mock(ExoPlayer::class.java)
        val s = state(player)
        s.seekTo(-500L)
        verify(player).seekTo(0L)
        s.seekTo(12_345L)
        verify(player).seekTo(12_345L)
    }

    @Test fun skipClampsToDurationAndZero() {
        val player = mock(ExoPlayer::class.java)
        `when`(player.duration).thenReturn(60_000L)
        `when`(player.currentPosition).thenReturn(55_000L)
        state(player).skipBy(10_000L)
        verify(player).seekTo(60_000L)

        val atStart = mock(ExoPlayer::class.java)
        `when`(atStart.duration).thenReturn(60_000L)
        `when`(atStart.currentPosition).thenReturn(5_000L)
        state(atStart).skipBy(-10_000L)
        verify(atStart).seekTo(0L)
    }

    @Test fun skipWithUnknownDurationDoesNotClampEnd() {
        val player = mock(ExoPlayer::class.java)
        `when`(player.duration).thenReturn(-1L)
        `when`(player.currentPosition).thenReturn(5_000L)
        state(player).skipBy(10_000L)
        verify(player).seekTo(15_000L)
    }

    @Test fun stateDelegatesReflectSnapshots() {
        val s = state(null)
        assertEquals(1_000L, s.positionMs)
        s.positionMs = 42L
        assertEquals(42L, s.positionMs)
        assertEquals(listOf(1080), s.videoHeights)
        s.isDragging = true
        assertEquals(true, s.isDragging)
    }
}
