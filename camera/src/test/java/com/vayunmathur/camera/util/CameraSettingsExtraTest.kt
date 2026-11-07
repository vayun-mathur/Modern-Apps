package com.vayunmathur.camera.util

import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class CameraSettingsExtraTest {

    private fun vm() = CameraViewModel(ApplicationProvider.getApplicationContext())

    @Test fun allSettingsSettersUpdateFlows() {
        val v = vm()
        v.setFlashMode(FlashMode.AUTO)
        assertEquals(FlashMode.AUTO, v.flashMode.value)
        v.setMirrorFront(false)
        assertFalse(v.mirrorFront.value)
        v.setNoiseReductionEnabled(false)
        assertFalse(v.noiseReductionEnabled.value)
        v.setEdgeSharpeningEnabled(false)
        assertFalse(v.edgeSharpeningEnabled.value)
        v.toggleTorch()
        assertTrue(v.torchEnabled.value)
        v.toggleTorch()
        assertFalse(v.torchEnabled.value)
        v.setTimerDuration(TimerDuration.TEN)
        assertEquals(TimerDuration.TEN, v.timerDuration.value)
        assertEquals(10, v.timerDuration.value.seconds)
        v.setVideoCodec(VideoCodec.AV1)
        assertEquals(VideoCodec.AV1, v.videoCodec.value)
        v.setMotionPhotoDuration(MotionPhotoDuration.ONE)
        assertEquals(1, v.motionPhotoDuration.value.seconds)
        v.setAudioInputSource(AudioInputSource.MIC)
        assertEquals(AudioInputSource.MIC, v.audioInputSource.value)
        v.setLocationEnabled(true)
        assertTrue(v.locationEnabled.value)
        v.toggleGrid()
        assertTrue(v.gridEnabled.value)
        v.toggleLevel()
        assertTrue(v.levelEnabled.value)
        v.toggleLevel()
        assertFalse(v.levelEnabled.value)
        v.setLastCaptureUri(Uri.parse("content://media/1"))
        assertEquals("content://media/1", v.lastCaptureUri.value.toString())
        v.setLastCaptureUri(null)
        assertEquals(null, v.lastCaptureUri.value)
    }

    @Test fun aspectRatioCycling() {
        val v = vm()
        val start = v.aspectRatio.value
        repeat(4) { v.cycleAspectRatio() }
        assertEquals(start, v.aspectRatio.value)
        v.setAspectRatio(AspectRatioOption.RATIO_1_1)
        assertEquals("1:1", v.aspectRatio.value.label)
        v.cycleAspectRatio()
        assertEquals(AspectRatioOption.RATIO_4_3, v.aspectRatio.value)
    }

    @Test fun slidersClampAndStore() {
        val v = vm()
        v.setBlurStrength(2f)
        assertEquals(1f, v.blurStrength.value)
        v.setBlurStrength(-1f)
        assertEquals(0f, v.blurStrength.value)
        v.setExposureCompensation(1.5f)
        assertEquals(1.5f, v.exposureCompensation.value)
        v.setWarmth(0.25f)
        assertEquals(0.25f, v.warmth.value)
        v.setShadows(-0.5f)
        assertEquals(-0.5f, v.shadows.value)
    }

    @Test fun updateLocationWithoutEnableIsNoOp() {
        val v = vm()
        v.updateLocation() // locationEnabled defaults to false; must not throw
        assertFalse(v.locationEnabled.value)
    }

    @Test fun zoomLabels() {
        assertEquals(".5", formatZoomLabel(0.5f))
        assertEquals(".9", formatZoomLabel(0.9f))
        assertEquals("1x", formatZoomLabel(1f))
        assertEquals("2x", formatZoomLabel(2f))
        assertEquals("1.5x", formatZoomLabel(1.5f))
        assertEquals("10x", formatZoomLabel(10f))
    }

    @Test fun colorMatrixReactsToWarmthAndShadows() {
        val neutral = buildColorAdjustmentMatrix(0f, 0f)
        val warm = buildColorAdjustmentMatrix(1f, 0f)
        val lifted = buildColorAdjustmentMatrix(0f, 1f)
        // Warmth raises the red gain (element 0) and lowers blue (element 12).
        assertTrue(warm.array[0] > neutral.array[0])
        assertTrue(warm.array[12] < neutral.array[12])
        // Shadows lift is the translation column (element 4).
        assertTrue(lifted.array[4] > neutral.array[4])
        assertEquals(0f, neutral.array[4])
    }

    @Test fun enumMetadata() {
        assertEquals(8, CameraMode.entries.size)
        assertEquals(listOf(0, 1, 3, 5, 10), TimerDuration.entries.map { it.seconds })
        assertEquals(4, AspectRatioOption.entries.size)
        assertEquals(3, VideoCodec.entries.size)
        assertEquals(4, AudioInputSource.entries.size)
        assertEquals(listOf(1, 3, 5), MotionPhotoDuration.entries.map { it.seconds })
    }

    @Test fun manualLifecycleOwnerTransitions() {
        val owner = ManualLifecycleOwner()
        assertEquals(Lifecycle.State.INITIALIZED, owner.lifecycle.currentState)
        owner.start()
        assertEquals(Lifecycle.State.RESUMED, owner.lifecycle.currentState)
        owner.destroy()
        assertEquals(Lifecycle.State.DESTROYED, owner.lifecycle.currentState)
    }

    @Test fun codecSupportDefaultsAreConsistent() {
        // Under Robolectric the Recorder reports no encoders, so HEVC/AV1
        // availability must agree with the chosen default.
        assertEquals(
            if (CodecSupport.isHevcEncoderAvailable) VideoCodec.HEVC else VideoCodec.AVC,
            CodecSupport.defaultVideoCodec,
        )
    }

    @Test fun saveTargetSealedForms() {
        assertEquals(SaveTarget.MediaStoreDefault, SaveTarget.MediaStoreDefault)
        val saf = SaveTarget.SafTree(Uri.parse("content://tree/primary%3ADCIM"))
        assertEquals("content://tree/primary%3ADCIM", saf.treeUri.toString())
    }
}
