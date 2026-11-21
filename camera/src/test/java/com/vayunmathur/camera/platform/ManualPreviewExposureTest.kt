package com.vayunmathur.camera.platform

import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Controller-level split between the live preview exposure and the still exposure:
 * which values each use-case stream effectively gets for a given manual setting.
 * The repeating (preview+analysis) request only ever receives the preview half via
 * applyManualControls(); the still half is locked transiently at capture time.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManualPreviewExposureTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun vm() = CameraViewModel(context.applicationContext as android.app.Application)

    private fun stopIndex(label: String): Int =
        CameraViewModel.EXPOSURE_TIME_STOPS.indexOfFirst { it.label == label }

    @Test fun autoExposureHasNoSplitAndNeedsNoOverride() {
        val viewModel = vm()
        viewModel.setExposureTimeIndex(0)
        viewModel.setManualIsoIndex(0)
        assertNull(viewModel.currentExposureSplit())
        assertFalse(viewModel.stillExposureOverrideRequired())
        // Unbound camera: pushing the (cleared) controls must not throw.
        viewModel.applyManualControls()
    }

    @Test fun shortManualShutterAppliesIdenticallyToPreviewAndStill() {
        val viewModel = vm()
        viewModel.isoStopsMutable.value = listOf(100, 200, 400, 800)
        viewModel.setExposureTimeIndex(stopIndex("1/60s"))
        viewModel.setManualIsoIndex(3) // ISO 400
        val split = assertNotNull(viewModel.currentExposureSplit())
        assertEquals(16_666_667L, split.stillNanos)
        assertEquals(400, split.stillIso)
        assertEquals(split.stillNanos, split.previewNanos)
        assertEquals(split.stillIso, split.previewIso)
        assertFalse(viewModel.stillExposureOverrideRequired())
    }

    @Test fun longManualShutterCapsPreviewButKeepsStill() {
        val viewModel = vm()
        viewModel.isoStopsMutable.value = listOf(100, 200, 400, 800, 1600, 3200)
        viewModel.setExposureTimeIndex(stopIndex("4s"))
        viewModel.setManualIsoIndex(2) // ISO 200
        val split = assertNotNull(viewModel.currentExposureSplit())
        assertEquals(4_000_000_000L, split.stillNanos)
        assertEquals(200, split.stillIso)
        assertEquals(33_333_333L, split.previewNanos)
        // 120x gain clamped to the highest known stop.
        assertEquals(3200, split.previewIso)
        assertTrue(viewModel.stillExposureOverrideRequired())
        // Unbound camera: the capped preview push must not throw.
        viewModel.applyManualControls()
    }

    @Test fun oneSecondShutterPreviewRunsThirtyFpsWhileStillRunsOne() {
        val viewModel = vm()
        viewModel.isoStopsMutable.value = listOf(50, 100, 200, 400, 800, 1600, 3200, 6400, 12800)
        viewModel.setExposureTimeIndex(stopIndex("1s"))
        viewModel.setManualIsoIndex(2) // ISO 100
        val split = assertNotNull(viewModel.currentExposureSplit())
        assertEquals(3000, split.previewIso)
        assertEquals(
            30.0,
            com.vayunmathur.camera.domain.PreviewExposurePolicy.maxFps(split.previewNanos),
            0.01
        )
        assertEquals(
            1.0,
            com.vayunmathur.camera.domain.PreviewExposurePolicy.maxFps(split.stillNanos),
            0.001
        )
    }

    @Test fun halfManualIsoOnlySeedsShutterFromLastAe() {
        val viewModel = vm()
        viewModel.isoStopsMutable.value = listOf(100, 200, 400)
        viewModel.lastAeExposureNanos = 8_000_000L // 1/125s converged
        viewModel.setExposureTimeIndex(0)
        viewModel.setManualIsoIndex(2) // ISO 200, shutter auto-seeded
        val split = assertNotNull(viewModel.currentExposureSplit())
        assertEquals(8_000_000L, split.stillNanos)
        assertEquals(200, split.stillIso)
        assertFalse(viewModel.stillExposureOverrideRequired())
    }
}
