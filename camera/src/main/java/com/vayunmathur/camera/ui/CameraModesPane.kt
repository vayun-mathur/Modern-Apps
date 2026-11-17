package com.vayunmathur.camera.ui

import androidx.compose.runtime.Composable
import com.vayunmathur.camera.platform.CameraMode

@Composable
internal fun CameraModesPane(
    cameraMode: CameraMode,
    isPhotoType: Boolean,
    sloMoSupported: Boolean?,
    captureForResult: Boolean,
    videoPickerEnabled: Boolean = true,
    onModeSelected: (CameraMode) -> Unit,
    iconRotation: Float,
    onPickerChanged: (Boolean) -> Unit,
    onSettingsClick: () -> Unit,
) {
    ModeSelector(
        cameraMode = cameraMode,
        isPhotoType = isPhotoType,
        sloMoSupported = sloMoSupported,
        captureForResult = captureForResult,
        onModeSelected = onModeSelected
    )

    BottomBar(
        isPhotoType = isPhotoType,
        iconRotation = iconRotation,
        onPickerChanged = onPickerChanged,
        onSettingsClick = onSettingsClick,
        videoPickerEnabled = videoPickerEnabled,
    )
}
