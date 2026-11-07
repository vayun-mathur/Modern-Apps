package com.vayunmathur.auto.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class CaptureAndTtsTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `music capture without thread or grant emits nothing`() {
        val events = mutableListOf<AudioEvent>()
        val capture = MusicCapture(ctx, mediaSink = { null }, isPlayingNow = { true }, onEvent = { events += it })
        capture.start(null) // no thread: dropped
        capture.stop()
        capture.startThread()
        capture.startThread() // idempotent
        capture.start(null) // no grant / not consented: stays silent (async on capture thread)
        capture.stop()
        capture.releaseThread()
        capture.start(null) // after release: dropped
        assertTrue(events.none { it is AudioEvent.MusicCaptured })
    }

    @Test fun `tts speak before start is dropped and after start reports unready engine`() {
        val events = mutableListOf<AudioEvent>()
        val latch = CountDownLatch(1)
        val tts = CarTts(ctx, systemSink = { null }, onEvent = { e -> events += e; if (e is AudioEvent.TtsDropped) latch.countDown() })
        tts.speak("hello") // no handler yet
        assertTrue(events.isEmpty())
        tts.start()
        tts.speak("hello car")
        // Engine never becomes ready under Robolectric, so synthesize drops with engine-unready
        assertTrue(latch.await(10, TimeUnit.SECONDS), "expected TtsDropped, got $events")
        assertEquals("engine-unready", events.filterIsInstance<AudioEvent.TtsDropped>().first().reason)
        tts.stop()
        tts.speak("after stop")
    }
}
