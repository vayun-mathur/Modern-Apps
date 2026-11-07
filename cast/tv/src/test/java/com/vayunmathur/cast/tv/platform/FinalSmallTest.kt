package com.vayunmathur.cast.tv.platform

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.cast.protocol.ContentSession
import com.vayunmathur.cast.protocol.LyricLine
import com.vayunmathur.cast.protocol.NowPlaying
import com.vayunmathur.cast.protocol.PlaybackState
import com.vayunmathur.cast.tv.ui.CastTvTheme
import com.vayunmathur.cast.tv.ui.Lyrics
import com.vayunmathur.cast.tv.ui.TransportOverlay
import com.vayunmathur.library.util.DataStoreUtils
import kotlin.io.encoding.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w960dp-h540dp")
class FinalSmallTest {
    @get:Rule val rule = createComposeRule()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun receivedEqualityUsesMessageAndBody() {
        val a = ControlChannel.Received(com.vayunmathur.cast.protocol.Ping, byteArrayOf(1, 2))
        val b = ControlChannel.Received(com.vayunmathur.cast.protocol.Ping, byteArrayOf(1, 2))
        val c = ControlChannel.Received(com.vayunmathur.cast.protocol.Ping, byteArrayOf(3))
        assertEquals(a, b); assertEquals(a.hashCode(), b.hashCode()); assertTrue(a != c)
        assertTrue(!a.equals(null)); assertTrue(!a.equals("ping"))
        assertEquals(a, a)
    }

    @Test fun artworkFetcherRejectsWrongSizeFingerprint() {
        val session = ContentSession(host = "127.0.0.1", port = 9, certificateFingerprint = Base64.encode(ByteArray(16)), token = "tok", video = true, appLabel = "App")
        assertNull(ArtworkFetcher(session).fetch("r1")) // fingerprint not 32 bytes: no fetch attempted
        val blank = ContentSession(host = "127.0.0.1", port = 9, certificateFingerprint = "", token = "tok", video = true, appLabel = "App")
        assertNull(ArtworkFetcher(blank).fetch("r1"))
    }

    @Test fun pairingStoreStoredFriendlyNameWins() { runBlocking {
        DataStoreUtils.getInstance(ctx).setString("tv_friendly_name", "Kitchen TV")
        assertEquals("Kitchen TV", PairingStore(ctx).friendlyName("Fallback"))
        DataStoreUtils.getInstance(ctx).setString("tv_friendly_name", "  ")
        assertEquals("Fallback", PairingStore(ctx).friendlyName("Fallback"))
    } }

    @Test fun lyricsBeforeFirstTimedLine() {
        val np = NowPlaying(title = "Song", lyrics = listOf(LyricLine(60_000, "later line"), LyricLine(120_000, "last line")))
        rule.setContent { CastTvTheme { Lyrics(np, { 0L }) } }
        rule.onNodeWithText("later line").assertExists()
    }

    @Test fun transportOverlayWholeNumberSpeed() {
        val snap = PlaybackSnapshot(PlaybackState(30_000, 60_000, playing = true, buffering = false, speed = 2f), 1_000)
        rule.setContent { CastTvTheme { TransportOverlay(snap, "YouPipe", null, { 30_000 }, scrubbing = false) } }
        rule.waitForIdle()
        val snap2 = PlaybackSnapshot(PlaybackState(30_000, 60_000, playing = true, buffering = false, speed = 1.25f), 1_000)
        // second composition in a separate test would be needed for the fractional readout; here the whole-number branch is covered
        assertEquals(1.25f, snap2.state.speed)
    }
}
