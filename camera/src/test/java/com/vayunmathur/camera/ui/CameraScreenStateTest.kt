package com.vayunmathur.camera.ui

import android.hardware.camera2.CameraMetadata
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.vayunmathur.camera.platform.AspectRatioOption
import com.vayunmathur.camera.platform.AudioInputSource
import com.vayunmathur.camera.platform.CameraMode
import com.vayunmathur.camera.platform.FlashMode
import com.vayunmathur.camera.platform.TimerDuration
import com.vayunmathur.camera.platform.VideoCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CameraScreenStateTest {

    private fun state(
        mode: CameraMode = CameraMode.PHOTO,
        lensFacing: Int = CameraMetadata.LENS_FACING_BACK,
        aspectRatio: AspectRatioOption = AspectRatioOption.RATIO_4_3,
        mirrorFront: Boolean = true,
        nightModeActive: Boolean = false,
        nightExtAvailable: Boolean = false,
        selectedLens: com.vayunmathur.camera.domain.PhysicalLens? = null,
        extensionStrength: Int? = null,
        camera2FallbackBindKey: String? = null,
        camera2NightFallbackBindKey: String? = null,
        camera2SloMoFallbackBindKey: String? = null,
    ) = CameraScreenState(
        cameraMode = mode,
        lensFacing = lensFacing,
        selectedLens = selectedLens,
        availableLenses = emptyList(),
        hasFlashUnit = true,
        flashMode = FlashMode.OFF,
        torchEnabled = false,
        videoCodec = VideoCodec.AVC,
        audioInputSource = AudioInputSource.CAMCORDER,
        isRecording = false,
        recordingDuration = 0,
        timerCountdown = 0,
        qrResult = null,
        aspectRatio = aspectRatio,
        zoomRatio = 1f,
        mirrorFront = mirrorFront,
        timerDuration = TimerDuration.NONE,
        isCapturing = false,
        burstActive = false,
        burstCount = 0,
        focusLocked = false,
        recordingPaused = false,
        micMuted = false,
        videoSnapshotSupported = false,
        lastCaptureUri = null,
        gridEnabled = false,
        levelEnabled = false,
        roll = 0f,
        blurStrength = 0.5f,
        exposureComp = 0f,
        warmth = 0f,
        shadows = 0f,
        exposureTimeIndex = 0,
        manualIsoIndex = 0,
        isoStops = emptyList(),
        longExposureProgress = 0f,
        longExposureRemaining = "",
        lowLightDetected = false,
        nightModeActive = nightModeActive,
        extensionStrength = extensionStrength,
        sloMoSupported = null,
        highSpeedActive = false,
        photoSessionActive = false,
        analysisStreamActive = false,
        nightExtAvailable = nightExtAvailable,
        availableZoomLevels = listOf("1x" to 1f),
        galleryBitmap = null,
        panoSweeping = false,
        panoStitching = false,
        panoDots = emptyList(),
        panoCurrentAngle = 0f,
        panoDirection = 0,
        panoPitch = 0f,
        activeSettingState = mutableStateOf(null),
        maskBitmapState = mutableStateOf(null),
        deviceRotationState = mutableIntStateOf(0),
        camera2FallbackBindKey = camera2FallbackBindKey,
        camera2NightFallbackBindKey = camera2NightFallbackBindKey,
        camera2SloMoFallbackBindKey = camera2SloMoFallbackBindKey,
    )

    private fun lens(id: String = "0") = com.vayunmathur.camera.domain.PhysicalLens(
        logicalCameraId = id,
        lensType = com.vayunmathur.camera.domain.LensType.WIDE,
        facing = com.vayunmathur.camera.domain.LensFacing.BACK,
        focalLengthMm35Eq = 24f,
        nominalZoomRatio = 1f,
        fallbackPriority = 0,
        labelKey = "wide",
    )

    @Test fun modeFamilies() {
        assertTrue(state(CameraMode.PHOTO).isPhotoType)
        assertTrue(state(CameraMode.PORTRAIT).isPortrait)
        assertTrue(state(CameraMode.PANORAMA).isPanoType)
        assertTrue(state(CameraMode.SLOW_MO).isSloMo)
        assertTrue(state(CameraMode.VIDEO).isVideoType)
        assertTrue(state(CameraMode.TIMELAPSE).isVideoType)
        assertTrue(state(CameraMode.CINEMATIC).isVideoType)
        assertFalse(state(CameraMode.VIDEO).isPhotoType)
    }

    @Test fun sessionKindMapping() {
        assertEquals(SessionKind.PHOTO, state(CameraMode.PHOTO).sessionKind)
        assertEquals(SessionKind.PORTRAIT, state(CameraMode.PORTRAIT).sessionKind)
        assertEquals(SessionKind.PANORAMA, state(CameraMode.PANORAMA).sessionKind)
        assertEquals(SessionKind.PANORAMA, state(CameraMode.PHOTOSPHERE).sessionKind)
        assertEquals(SessionKind.HIGH_SPEED, state(CameraMode.SLOW_MO).sessionKind)
        assertEquals(SessionKind.VIDEO, state(CameraMode.VIDEO).sessionKind)
        assertEquals(SessionKind.VIDEO, state(CameraMode.TIMELAPSE).sessionKind)
    }

    @Test fun nightPreviewRequiresPhotoModeAndExtension() {
        assertTrue(state(nightModeActive = true, nightExtAvailable = true).useNightPreview)
        assertFalse(state(nightModeActive = true, nightExtAvailable = false).useNightPreview)
        assertFalse(state(CameraMode.PORTRAIT, nightModeActive = true, nightExtAvailable = true).useNightPreview)
        assertFalse(state(nightModeActive = false, nightExtAvailable = true).useNightPreview)
    }

    @Test fun camera2NightPreviewRequiresPhotoNightAndLens() {
        // No gate and no other extension to be usable: PHOTO + night + lens.
        assertTrue(state(nightModeActive = true, nightExtAvailable = false, selectedLens = lens()).useCamera2NightPreview)
        assertFalse(state(nightModeActive = false, selectedLens = lens()).useCamera2NightPreview)
        assertFalse(state(nightModeActive = true, selectedLens = null).useCamera2NightPreview)
        assertFalse(state(CameraMode.PORTRAIT, nightModeActive = true, selectedLens = lens()).useCamera2NightPreview)
        // Latched failure for this bind (cameraId:mode) suppresses retry.
        val key = com.vayunmathur.camera.platform.camera2.Camera2FallbackLatch.bindKey("0", CameraMode.PHOTO)
        assertFalse(state(nightModeActive = true, selectedLens = lens(), camera2NightFallbackBindKey = key).useCamera2NightPreview)
    }

    @Test fun camera2PreviewCoversAllModesWhenLensKnownAndUnlatched() {
        for (mode in CameraMode.entries) {
            assertTrue(state(mode, selectedLens = lens()).useCamera2Preview, "$mode")
        }
        assertFalse(state(selectedLens = null).useCamera2Preview)
        val key = com.vayunmathur.camera.platform.camera2.Camera2FallbackLatch.bindKey("0", CameraMode.PHOTO)
        assertFalse(state(selectedLens = lens(), camera2FallbackBindKey = key).useCamera2Preview)
        // A latch for a different bind does not block this one.
        val other = com.vayunmathur.camera.platform.camera2.Camera2FallbackLatch.bindKey("9", CameraMode.PHOTO)
        assertTrue(state(selectedLens = lens(), camera2FallbackBindKey = other).useCamera2Preview)
    }

    @Test fun camera2SloMoPreviewSelectedWhenReadyAndUnlatched() {
        assertTrue(state(CameraMode.SLOW_MO, selectedLens = lens()).useCamera2SloMoPreview)
        assertFalse(state(CameraMode.PHOTO, selectedLens = lens()).useCamera2SloMoPreview)
        assertFalse(state(CameraMode.SLOW_MO, selectedLens = null).useCamera2SloMoPreview)
        val key = com.vayunmathur.camera.platform.camera2.Camera2FallbackLatch.bindKey("0", CameraMode.SLOW_MO)
        assertFalse(state(CameraMode.SLOW_MO, selectedLens = lens(), camera2SloMoFallbackBindKey = key).useCamera2SloMoPreview)
    }

    @Test fun visibleExtensionStrengthFollowsCamera2Extension() {
        assertEquals(42, state(extensionStrength = 42).visibleExtensionStrength)
        assertEquals(null, state(extensionStrength = null).visibleExtensionStrength)
    }

    @Test fun previewAspectRatios() {
        assertEquals(9f / 16f, state(aspectRatio = AspectRatioOption.RATIO_16_9).previewAspectRatio)
        assertEquals(3f / 4f, state(aspectRatio = AspectRatioOption.RATIO_4_3).previewAspectRatio)
        assertEquals(2f / 3f, state(aspectRatio = AspectRatioOption.RATIO_3_2).previewAspectRatio)
        assertEquals(1f, state(aspectRatio = AspectRatioOption.RATIO_1_1).previewAspectRatio)
        // Non-photo modes always show their native 4:3 frame.
        assertEquals(3f / 4f, state(CameraMode.VIDEO, aspectRatio = AspectRatioOption.RATIO_16_9).previewAspectRatio)
        assertEquals(2f / 3f, state(CameraMode.PORTRAIT, aspectRatio = AspectRatioOption.RATIO_3_2).previewAspectRatio)
    }

    @Test fun mirrorPreviewOnlyForUnmirroredFront() {
        assertTrue(state(lensFacing = CameraMetadata.LENS_FACING_FRONT, mirrorFront = false).mirrorPreview)
        assertFalse(state(lensFacing = android.hardware.camera2.CameraMetadata.LENS_FACING_FRONT, mirrorFront = true).mirrorPreview)
        assertFalse(state(lensFacing = CameraMetadata.LENS_FACING_BACK, mirrorFront = false).mirrorPreview)
    }

    @Test fun backedStateSurvivesHolderRecreation() {
        val setting = mutableStateOf<CameraSetting?>(null)
        val a = state()
        a.activeSetting = CameraSetting.BRIGHTNESS
        // A recreated holder sharing the same backing state sees the value.
        assertEquals(CameraSetting.BRIGHTNESS, a.activeSetting)
        assertTrue(setting.value == null)
    }
}
