package com.vayunmathur.camera.ui

import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.vayunmathur.camera.platform.CameraViewModel
import com.vayunmathur.camera.platform.registerRecordingPreviewSurface
import com.vayunmathur.camera.platform.camera2.PreviewSurfaceBridge
import com.vayunmathur.camera.platform.camera2.PreviewSurfaceEvent

/**
 * Viewfinder while a Camera2 recording owns the camera.
 *
 * The TextureView is unremarkable on purpose: it creates and owns its
 * own SurfaceTexture exactly like every other (working) preview in
 * this UI, and the [PreviewSurfaceBridge] publishes the view's own
 * Surface to the ViewModel. The recording engine is started against
 * that surface, so its preview target and the on-screen view are the
 * same surface by construction.
 *
 * The previous wiring did the inverse — the engine created a detached
 * `SurfaceTexture`, the HAL wrote preview frames into a `Surface`
 * wrapping it, and this composable adopted that foreign texture via
 * `TextureView.setSurfaceTexture`. A TextureView renders the texture
 * it owns; the adopted texture was never the view's own surface, so
 * the viewfinder stayed black for the whole recording while the
 * encoder kept recording. Do not reintroduce an engine-owned texture
 * here.
 */
@Composable
internal fun Camera2RecordingPreview(
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier,
) {
    // The bridge callback needs the bridge itself (to pin the buffer
    // size before the surface is registered, hence before the engine
    // configures its session against it); events only fire after
    // attachTo below, by which time the assignment has happened.
    lateinit var bridge: PreviewSurfaceBridge
    bridge = remember(viewModel) {
        PreviewSurfaceBridge { event ->
            when (event) {
                is PreviewSurfaceEvent.Available -> {
                    viewModel.camera2RecordingState.previewStreamSize?.let { size ->
                        bridge.setDefaultBufferSize(size.width, size.height)
                    }
                    viewModel.registerRecordingPreviewSurface(event.surface)
                }
                is PreviewSurfaceEvent.SizeChanged ->
                    viewModel.registerRecordingPreviewSurface(event.surface)
                is PreviewSurfaceEvent.Destroyed ->
                    viewModel.registerRecordingPreviewSurface(null)
            }
        }
    }
    DisposableEffect(bridge) {
        onDispose { viewModel.registerRecordingPreviewSurface(null) }
    }
    AndroidView(
        factory = { ctx ->
            TextureView(ctx).also { view -> bridge.attachTo(view) }
        },
        modifier = modifier,
    )
}
