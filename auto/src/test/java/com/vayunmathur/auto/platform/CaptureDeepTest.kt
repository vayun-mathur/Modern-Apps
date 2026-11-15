package com.vayunmathur.auto.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deep [MusicCapture] coverage: the stereo-to-mono fallback conversion and
 * the consent/projection gates on the capture thread.
 */
@RunWith(RobolectricTestRunner::class)
class CaptureDeepTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    private fun shorts(vararg values: Int): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buffer.putShort(it.toShort()) }
        return buffer.array()
    }

    private fun toShorts(bytes: ByteArray): List<Int> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return List(buffer.remaining()) { buffer.get(it).toInt() }
    }

    private fun stereoToMono(capture: MusicCapture, stereo: ByteArray): ByteArray {
        val m = MusicCapture::class.java
            .getDeclaredMethod("stereoToMono", ByteArray::class.java)
            .apply { isAccessible = true }
        return m.invoke(capture, stereo) as ByteArray
    }

    @Test fun `stereo frames average to mono with clamping`() {
        val capture = MusicCapture(ctx, mediaSink = { null })
        assertEquals(listOf(15), toShorts(stereoToMono(capture, shorts(10, 20))))
        assertEquals(listOf(32767), toShorts(stereoToMono(capture, shorts(32767, 32767))))
        assertEquals(listOf(-32768), toShorts(stereoToMono(capture, shorts(-32768, -32768))))
        assertEquals(listOf(0), toShorts(stereoToMono(capture, shorts(30_000, -30_000))))
        // A trailing unpaired sample is dropped, never read out of bounds.
        assertEquals(listOf(2), toShorts(stereoToMono(capture, shorts(1, 3, 99))))
        assertEquals(emptyList(), toShorts(stereoToMono(capture, ByteArray(0))))
    }

    @Test fun `consented start without a projection grant stays silent`() {
        MusicCapturePrefs.setMusicCaptureConsented(ctx, true)
        try {
            val events = mutableListOf<AudioEvent>()
            val capture = MusicCapture(ctx, mediaSink = { null }, isPlayingNow = { true }, onEvent = { events += it })
            capture.startThread()
            capture.start(null) // consented but no grant: the capture thread drops it
            capture.stop()
            capture.releaseThread()
            assertTrue(events.none { it is AudioEvent.MusicCaptured })
        } finally {
            MusicCapturePrefs.setMusicCaptureConsented(ctx, false)
        }
    }
}
