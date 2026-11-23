package com.vayunmathur.camera.ui

import android.content.Context
import android.hardware.camera2.CameraManager
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.vayunmathur.camera.domain.LensFacing
import com.vayunmathur.camera.platform.Camera2RecordingCore
import com.vayunmathur.camera.platform.camera2.AndroidCamera2Driver
import com.vayunmathur.camera.platform.camera2.Camera2Executor
import com.vayunmathur.camera.platform.camera2.Camera2HighSpeedSession
import com.vayunmathur.camera.platform.camera2.Camera2IdResolution
import com.vayunmathur.camera.platform.camera2.Camera2IdResolver
import com.vayunmathur.camera.platform.camera2.CharacteristicsFacade
import com.vayunmathur.camera.platform.camera2.HighSpeedPlanner
import com.vayunmathur.camera.platform.camera2.OnceErrorReporter
import com.vayunmathur.camera.platform.camera2.PreviewSurfaceBridge
import com.vayunmathur.camera.platform.camera2.PreviewSurfaceEvent
import com.vayunmathur.camera.platform.camera2.RecordingSessionOwnership
import com.vayunmathur.camera.platform.toCustomRecordingConfig
import com.vayunmathur.library.log.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Camera2 slo-mo viewfinder (piece 4): a TextureView whose surface feeds
 * a [Camera2HighSpeedSession] as its preview target, alongside the shared
 * core's encoder surface. Mirrors [Camera2Preview]/[Camera2NightPreview].
 *
 * Composed only when `CameraScreenState.useCamera2SloMoPreview` selects
 * it — which requires the slo-mo gate, a high-speed selection, no latched
 * fallback, and `SloMoRecordingReadiness.recordingEngineReady`. That flag
 * is false until device verification (piece 6), so today this composable
 * is never composed live and SLOW_MO preview/recording stay on the
 * CameraX high-speed session exactly as before. The plumbing exists so
 * flipping readiness after verification needs no new surface code.
 *
 * [cameraId] is the bind identity only; the session opens the id
 * [Camera2IdResolver] maps from [lensFacing], never this value.
 */
@Composable
internal fun Camera2SloMoPreview(
    cameraId: String,
    lensFacing: LensFacing?,
    onSessionChanged: (Camera2HighSpeedSession?) -> Unit = {},
    onControlChanged: (com.vayunmathur.camera.platform.SloMoRecordingControl?) -> Unit = {},
    onError: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val sessionHolder = remember(lensFacing) {
        Camera2SloMoPreviewSession(context, lensFacing, onError)
    }
    var session by remember(sessionHolder) { mutableStateOf<Camera2HighSpeedSession?>(null) }

    DisposableEffect(sessionHolder) {
        onDispose {
            onControlChanged(null)
            onSessionChanged(null)
            sessionHolder.close()
        }
    }
    LaunchedEffect(sessionHolder) {
        sessionHolder.build()
        session = sessionHolder.session
        if (session != null) {
            onSessionChanged(session)
            onControlChanged(sessionHolder.recordingControl())
        }
        sessionHolder.deliverPendingError()
    }

    if (session == null) {
        Box(modifier = modifier.background(Color.Black))
        return
    }

    AndroidView(
        factory = { ctx ->
            TextureView(ctx).also { view -> sessionHolder.bridge.attachTo(view) }
        },
        modifier = modifier,
    )
}
