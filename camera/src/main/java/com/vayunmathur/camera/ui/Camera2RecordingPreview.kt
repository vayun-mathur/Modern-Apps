package com.vayunmathur.camera.ui

import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import com.vayunmathur.camera.platform.CameraViewModel

/**
 * Viewfinder while a Camera2 recording owns the camera: a TextureView
 * rendering the recording engine's owned preview texture. The texture
 * is published by the ViewModel once the engine has started; until
 * then (the sub-second settle/open window) the preview is black, like
 * every other bind gap in this UI.
 */
@Composable
internal fun Camera2RecordingPreview(
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier,
) {
    val texture by viewModel.camera2RecordingState.previewTexture.collectAsState()
    val liveTexture = texture
    if (liveTexture == null) {
        Box(modifier = modifier.background(Color.Black))
        return
    }
    AndroidView(
        factory = { ctx ->
            TextureView(ctx).also { view ->
                view.setSurfaceTexture(liveTexture)
                viewModel.camera2RecordingState.engine?.markPreviewTextureBorrowed()
            }
        },
        modifier = modifier,
    )
}
