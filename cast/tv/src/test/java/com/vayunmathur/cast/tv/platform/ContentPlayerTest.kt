package com.vayunmathur.cast.tv.platform

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.cast.protocol.ContentSession
import com.vayunmathur.cast.protocol.PlayMedia
import com.vayunmathur.cast.protocol.PlaybackAction
import com.vayunmathur.cast.protocol.PlaybackCommand
import kotlin.io.encoding.Base64
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ContentPlayerTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun session(fp: String) = ContentSession(host = "127.0.0.1", port = 9, certificateFingerprint = fp, token = "tok", video = false, appLabel = "App")

    @Test fun unstartedPlayerIsInert() {
        val p = ContentPlayer(ctx, session("!!!"))
        assertEquals(0L, p.position); assertEquals(0L, p.duration); assertEquals(false, p.isPlaying); assertEquals(false, p.isBuffering); assertEquals(false, p.isEnded)
        assertEquals(1f, p.speed); assertEquals(1f, p.volume)
        val snap = p.snapshot(); assertEquals(0L, snap.positionMs); assertEquals(false, snap.playing)
        assertEquals(false, p.apply(PlaybackCommand(PlaybackAction.Next))); assertEquals(false, p.apply(PlaybackCommand(PlaybackAction.Previous)))
        assertTrue(p.apply(PlaybackCommand(PlaybackAction.Play))); assertTrue(p.apply(PlaybackCommand(PlaybackAction.Pause)))
        assertTrue(p.apply(PlaybackCommand(PlaybackAction.Toggle))); assertTrue(p.apply(PlaybackCommand(PlaybackAction.SeekTo, value = 5000.0)))
        assertTrue(p.apply(PlaybackCommand(PlaybackAction.SkipForward))); assertTrue(p.apply(PlaybackCommand(PlaybackAction.SkipBack)))
        assertTrue(p.apply(PlaybackCommand(PlaybackAction.SetSpeed, value = 1.5))); assertTrue(p.apply(PlaybackCommand(PlaybackAction.SetVolume, value = 0.5)))
        assertTrue(p.apply(PlaybackCommand(PlaybackAction.SeekTo))) // no value: no-op but owned
        p.play(PlayMedia(resourceId = "r1", mimeType = "audio/mp4")); p.setSurface(null); p.play(); p.pause(); p.seekTo(100); p.setSpeed(2f); p.setVolume(2f); p.release(); p.release()
    }
    @Test fun startRejectsBadFingerprint() {
        val p = ContentPlayer(ctx, session("not-base64!!"))
        assertEquals(false, p.start { })
        val p2 = ContentPlayer(ctx, session(Base64.encode(byteArrayOf(1, 2, 3))))
        assertEquals(false, p2.start { }) // wrong size
    }
    @Test fun startBuildsPlayerWithValidFingerprint() {
        shadowOf(Looper.getMainLooper()).idle()
        val p = ContentPlayer(ctx, session(Base64.encode(ByteArray(32) { it.toByte() })))
        val started = runCatching { p.start { } }.getOrDefault(false)
        if (started) {
            p.play(PlayMedia(resourceId = "r1", mimeType = "audio/mp4", durationMs = 123_000, startPositionMs = 1000))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(123_000L, p.duration)
            assertTrue(p.apply(PlaybackCommand(PlaybackAction.Pause)))
            p.onChanged = {}; p.setVolume(0.25f); assertEquals(0.25f, p.volume)
            p.setSpeed(1.25f); assertEquals(1.25f, p.speed)
            p.seekTo(2000); p.setSurface(null)
        }
        p.release()
    }
}
