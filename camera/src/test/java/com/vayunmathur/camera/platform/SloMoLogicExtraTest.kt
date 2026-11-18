package com.vayunmathur.camera.platform

import android.graphics.Bitmap
import android.graphics.Color
import androidx.camera.core.CameraInfo
import androidx.camera.core.DynamicRange
import androidx.camera.video.Quality
import androidx.camera.video.VideoCapabilities
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Slo-mo quality/range resolution, timelapse fallback, motion encoder guards. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SloMoLogicExtraTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun vm() = CameraViewModel(context.applicationContext as android.app.Application)

    private fun capabilities(vararg qualities: Quality): VideoCapabilities {
        val caps = Mockito.mock(VideoCapabilities::class.java)
        Mockito.`when`(caps.getSupportedQualities(Mockito.any(DynamicRange::class.java) ?: DynamicRange.SDR))
            .thenReturn(qualities.toList())
        return caps
    }

    @Test fun hfrQualitiesEmptyMarksUnsupported() {
        val viewModel = vm()
        assertNull(viewModel.resolveHfrQualities(capabilities()))
        assertEquals(false, viewModel.sloMoSupportedMutable.value)
    }

    @Test fun hfrQualitiesPreferFhdThenHd() {
        val viewModel = vm()
        val resolved = viewModel.resolveHfrQualities(
            capabilities(Quality.SD, Quality.HD, Quality.FHD),
        )
        assertNotNull(resolved)
        // The slo-mo preference order is FHD, HD, UHD, SD regardless of input order.
        assertEquals(listOf(Quality.FHD, Quality.HD, Quality.SD), resolved)
    }

    @Test fun hfrQualitiesFallBackToWhateverIsSupported() {
        val viewModel = vm()
        val resolved = viewModel.resolveHfrQualities(capabilities(Quality.SD))
        assertEquals(listOf(Quality.SD), resolved)
    }

    @Test fun hfrCaptureBuildsAndRangesQueryDegrades() {
        val viewModel = vm()
        val pair = viewModel.buildHfrCapture(listOf(Quality.HD, Quality.SD))
        assertNotNull(pair.first); assertNotNull(pair.second)
        assertTrue(viewModel.highSpeedVideoCapture != null)
        val info = Mockito.mock(CameraInfo::class.java)
        Mockito.`when`(
            info.getSupportedFrameRateRanges(Mockito.any(androidx.camera.core.SessionConfig::class.java)),
        ).thenThrow(IllegalStateException("no ranges"))
        val tempConfig = androidx.camera.video.HighSpeedVideoSessionConfig.Builder(pair.second)
            .setPreview(pair.first).setSlowMotionEnabled(true).build()
        assertTrue(viewModel.queryHfrRanges(info, tempConfig).isEmpty())
        // No true-HFR ranges (all <= 30fps are filtered by resolveHfrRanges' caller
        // contract); anti-banding without a bound camera must not throw.
        viewModel.applyAntiBanding()
    }

    @Test fun timelapseRemuxFallsBackToRawFile() {
        val cache = File(context.cacheDir, "VID_raw.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val out = remuxTimelapse(cache, "20260101_000000_000")
        assertEquals(cache, out)
        assertTrue(cache.exists())
        cache.delete()
    }

    @Test fun motionEncoderRejectsEmptyAndTinyFrames() {
        val out = File(context.cacheDir, "motion.mp4")
        assertFalse(MotionPhotoEncoder.encode(emptyList(), out, 0))
        val tiny = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        assertFalse(MotionPhotoEncoder.encode(listOf(tiny), out, 0))
        // A plausible frame still cannot encode without native codecs in this
        // harness: false (never a throw) is the graceful contract.
        val frame = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        assertFalse(MotionPhotoEncoder.encode(listOf(frame, frame), out, 90))
    }
}
