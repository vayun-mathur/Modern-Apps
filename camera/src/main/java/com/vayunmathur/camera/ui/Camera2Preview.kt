package com.vayunmathur.camera.ui

import android.content.Context
import android.hardware.camera2.CameraManager
import android.view.TextureView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.vayunmathur.camera.platform.camera2.AndroidCamera2Driver
import com.vayunmathur.camera.platform.camera2.Camera2Executor
import com.vayunmathur.camera.platform.camera2.Camera2Session
import com.vayunmathur.camera.platform.camera2.CharacteristicsFacade
import com.vayunmathur.camera.platform.camera2.FrameSize
import com.vayunmathur.camera.platform.camera2.PreviewSurfaceBridge
import com.vayunmathur.camera.platform.camera2.PreviewSurfaceEvent
import com.vayunmathur.library.log.Log

/**
 * Camera2 (Phase 2) viewfinder: a TextureView whose surface feeds a
 * [Camera2Session]. Rendered only when the preview-engine gate selects
 * Camera2 (PHOTO + gate on — see `PreviewEnginePolicy`); the CameraX
 * viewfinder path is untouched otherwise.
 *
 * The [modifier] carries the same graphicsLayer RenderEffects / clipping as
 * the CameraX preview, so warmth/bokeh grading composites identically over
 * this TextureView (EMBEDDED-style texture rendering under the overlay tree).
 *
 * Ownership: this composition owns the executor and the session; both are
 * torn down when it leaves composition (mode switch, lens flip, gate off).
 */
@Composable
internal fun Camera2Preview(
    cameraId: String,
    zoomRatio: Float,
    torchOn: Boolean,
    exposureCompEv: Float,
    flashSetting: com.vayunmathur.camera.domain.FlashSetting =
        com.vayunmathur.camera.domain.FlashSetting.OFF,
    onSessionChanged: (Camera2Session?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val sessionHolder = remember(cameraId) { Camera2PreviewSession(context, cameraId) }

    DisposableEffect(sessionHolder) {
        onSessionChanged(sessionHolder.session)
        onDispose {
            onSessionChanged(null)
            sessionHolder.close()
        }
    }
    LaunchedEffect(sessionHolder, zoomRatio) { sessionHolder.session.setZoomRatio(zoomRatio) }
    LaunchedEffect(sessionHolder, torchOn) { sessionHolder.session.setTorch(torchOn) }
    LaunchedEffect(sessionHolder, exposureCompEv) {
        sessionHolder.session.setExposureCompEv(exposureCompEv)
    }
    LaunchedEffect(sessionHolder, flashSetting) {
        sessionHolder.session.setFlashSetting(flashSetting)
    }

    AndroidView(
        factory = { ctx ->
            TextureView(ctx).also { view ->
                sessionHolder.session.setDisplayRotationDegrees(
                    (view.display?.rotation ?: 0) * 90,
                )
                sessionHolder.bridge.attachTo(view)
            }
        },
        modifier = modifier.pointerInput(sessionHolder) {
            detectTapGestures { offset ->
                if (size.width > 0 && size.height > 0) {
                    sessionHolder.session.tapToFocus(
                        tapX = offset.x / size.width,
                        tapY = offset.y / size.height,
                    )
                }
            }
        },
    )
}

/** Executor + bridge + session for one camera id; built once, closed once. */
private class Camera2PreviewSession(context: Context, private val cameraId: String) {
    val executor = Camera2Executor("Camera2Preview-$cameraId")
    val session: Camera2Session
    val bridge: PreviewSurfaceBridge

    init {
        val manager = context.getSystemService(CameraManager::class.java)
        val facade = CharacteristicsFacade.fromCameraManager(manager, cameraId)
        val driver = AndroidCamera2Driver(
            manager,
            android.os.Handler(executor.looper),
        )
        session = Camera2Session(
            driver = driver,
            executor = executor,
            snapshot = facade.snapshot,
            listener = object : Camera2Session.Listener {
                override fun onError(message: String) {
                    Log.status("Camera2Preview", message)
                }
            },
        )
        bridge = PreviewSurfaceBridge { event ->
            when (event) {
                is PreviewSurfaceEvent.Available -> session.open(
                    cameraId = cameraId,
                    surface = event.surface,
                    requestedSize = FrameSize(event.width, event.height),
                )
                is PreviewSurfaceEvent.SizeChanged -> {
                    // No resize API on the Phase-2 session: reopen at the new
                    // size so the stream is re-validated up front.
                    session.close()
                    session.open(
                        cameraId = cameraId,
                        surface = event.surface,
                        requestedSize = FrameSize(event.width, event.height),
                    )
                }
                is PreviewSurfaceEvent.Destroyed -> session.close()
            }
        }
    }

    fun close() {
        session.close()
        executor.shutdown()
    }
}
