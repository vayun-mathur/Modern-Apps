package com.vayunmathur.cast.platform.mirror

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.cast.protocol.CodecLimits
import com.vayunmathur.cast.protocol.CodecSelection
import com.vayunmathur.cast.protocol.DisplayMode
import com.vayunmathur.cast.protocol.VideoCodec
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MirrorGeometryTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun chosen(
        codec: VideoCodec = VideoCodec.Hevc,
        limits: CodecLimits = CodecLimits(codec, 3840, 2160, 60, 20_000_000),
        ceiling: Int = 0,
    ) = CodecSelection.Chosen(codec, limits, ceiling)

    @Test
    fun `frameRateFor returns ceiling when no panel rate`() {
        val limits = CodecLimits(VideoCodec.Hevc, 1920, 1080, 30, 1_000_000)
        assertEquals(30f, MirrorGeometry.frameRateFor(limits))
    }

    @Test
    fun `frameRateFor caps at protocol max when limits say zero`() {
        val limits = CodecLimits(VideoCodec.Hevc, 1920, 1080, 0, 1_000_000)
        assertEquals(60f, MirrorGeometry.frameRateFor(limits))
    }

    @Test
    fun `frameRateFor respects encoder ceiling`() {
        val limits = CodecLimits(VideoCodec.Hevc, 1920, 1080, 60, 1_000_000)
        assertEquals(24f, MirrorGeometry.frameRateFor(limits, encoderFrameRate = 24f))
    }

    @Test
    fun `frameRateFor accepts panel rate within tolerance and rejects above`() {
        val limits = CodecLimits(VideoCodec.Hevc, 1920, 1080, 60, 1_000_000)
        assertEquals(59.94f, MirrorGeometry.frameRateFor(limits, panelRefreshRate = 59.94f, encoderFrameRate = 59.9f))
        assertEquals(null, MirrorGeometry.frameRateFor(limits, panelRefreshRate = 120f, encoderFrameRate = 60f))
    }

    @Test
    fun `frameRateFor with negative limits uses the protocol ceiling`() {
        val limits = CodecLimits(VideoCodec.Hevc, 1920, 1080, -5, 1_000_000)
        assertEquals(60f, MirrorGeometry.frameRateFor(limits))
        assertEquals(60f, MirrorGeometry.frameRateFor(limits, panelRefreshRate = 60f, encoderFrameRate = 0f))
        assertEquals(null, MirrorGeometry.frameRateFor(limits, panelRefreshRate = 61f, encoderFrameRate = 0f))
    }

    @Test
    fun `forContent defaults nonsense size to 720p shape fitted to limits`() {
        val g = MirrorGeometry.forContent(0, -5, chosen())
        assertTrue(g.width > 0 && g.height > 0)
        assertTrue(g.width <= 3840 && g.height <= 2160)
        assertEquals(160, g.densityDpi) // DENSITY_DEFAULT
        assertTrue(g.bitRate >= 300_000)
        assertTrue(g.frameRate >= 30f)
    }

    @Test
    fun `forContent scales down to receiver limits preserving aspect`() {
        val g = MirrorGeometry.forContent(
            3840, 2160,
            chosen(limits = CodecLimits(VideoCodec.Hevc, 1920, 1080, 30, 5_000_000)),
        )
        assertTrue(g.width <= 1920 && g.height <= 1080, "got ${g.width}x${g.height}")
        assertEquals(30f, g.frameRate)
    }

    @Test
    fun `forContent bitrate respects ceiling`() {
        val capped = MirrorGeometry.forContent(1920, 1080, chosen(ceiling = 400_000))
        assertEquals(400_000, capped.bitRate)
        val av1 = MirrorGeometry.forContent(1920, 1080, chosen(codec = VideoCodec.Av1))
        val hevc = MirrorGeometry.forContent(1920, 1080, chosen(codec = VideoCodec.Hevc))
        assertTrue(av1.bitRate < hevc.bitRate, "AV1 ${av1.bitRate} should be below HEVC ${hevc.bitRate}")
    }

    @Test
    fun `screenSize falls back when metrics are empty`() {
        val (w, h) = MirrorGeometry.screenSize(context)
        assertTrue(w > 0 && h > 0, "screen size $w x $h")
    }

    @Test
    fun `forDisplay produces even positive geometry`() {
        val g = MirrorGeometry.forDisplay(context, chosen())
        assertTrue(g.width > 0 && g.height > 0)
        assertTrue(g.densityDpi > 0)
        assertTrue(g.bitRate >= 300_000)
    }

    @Test
    fun `desktopModes with no modes falls back to display geometry`() {
        val modes = MirrorGeometry.desktopModes(context, chosen(), emptyList())
        assertEquals(1, modes.size)
        val viaDesktop = MirrorGeometry.forDesktop(context, chosen(), emptyList())
        assertEquals(modes.first(), viaDesktop)
    }

    @Test
    fun `desktopModes orders largest first and dedupes`() {
        val modes = listOf(
            DisplayMode(1280, 720, 60f),
            DisplayMode(1920, 1080, 60f),
            DisplayMode(1920, 1080, 30f),
            DisplayMode(1920, 1080, 60f),
        )
        val result = MirrorGeometry.desktopModes(context, chosen(), modes)
        assertTrue(result.isNotEmpty())
        assertEquals(1920, result.first().width)
        // sorted by area desc then rate desc
        val sorted = result.sortedWith(
            compareByDescending<CaptureGeometry> { it.width.toLong() * it.height }.thenByDescending { it.frameRate },
        )
        assertEquals(sorted, result)
        assertNotNull(result.firstOrNull { it.width == 1280 })
    }

    @Test
    fun `desktop mode above encoder ability may be dropped or clamped but never invalid`() {
        val modes = listOf(DisplayMode(3840, 2160, 120f))
        val result = MirrorGeometry.desktopModes(
            context,
            chosen(limits = CodecLimits(VideoCodec.Hevc, 3840, 2160, 60, 10_000_000)),
            modes,
        )
        // 120Hz panel exceeds 60 ceiling: either dropped (fallback) or not offered at 120
        assertTrue(result.all { it.frameRate <= 60.5f })
        assertTrue(result.all { it.width > 0 && it.height > 0 && it.densityDpi >= 100 })
    }
}
