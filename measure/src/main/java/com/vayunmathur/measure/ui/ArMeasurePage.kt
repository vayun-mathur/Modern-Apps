package com.vayunmathur.measure.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.fillMaxSize
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.ui.rememberPermissionRequest
import com.vayunmathur.measure.Route
import com.vayunmathur.measure.domain.MeasureNative
import com.vayunmathur.measure.platform.MeasureViewModel
import com.vayunmathur.measure.platform.trackingQualityFrom
import com.vayunmathur.measure.platform.updateDiagnosticsImpl
import com.vayunmathur.measure.ui.components.MeasureBottomBar
import com.vayunmathur.measure.ui.components.ANALYSIS_SIZE
import com.vayunmathur.measure.ui.components.MeasureCamera
import kotlinx.coroutines.delay

@Composable
fun ArMeasurePage(backStack: NavBackStack<Route>, viewModel: MeasureViewModel) {
    val state by viewModel.ar.collectAsState()
    var sessionHandle by remember { mutableLongStateOf(0L) }
    var screenPoints by remember { mutableStateOf<List<Offset?>>(emptyList()) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }

    val cameraGranted = rememberCameraPermission { viewModel.setCameraPermission(it) }

    // Reproject anchors whenever the anchor set or the pose changes. Projection lives
    // in the engine because it owns the current pose and the intrinsics.
    LaunchedEffect(state.anchors, state.quality, canvasSize) {
        if (sessionHandle == 0L || canvasSize == Size.Zero || state.anchors.isEmpty()) {
            screenPoints = emptyList()
            return@LaunchedEffect
        }
        while (true) {
            val flat = DoubleArray(state.anchors.size * 3)
            state.anchors.forEachIndexed { i, a ->
                flat[i * 3] = a.x
                flat[i * 3 + 1] = a.y
                flat[i * 3 + 2] = a.z
            }
            // The engine projects with analysis-stream intrinsics and
            // normalises by the size it is given, so it must be given the
            // analysis size, not the canvas size: passing canvas pixels
            // compressed every anchor into the top-left of the screen.
            val projected = MeasureNative.projectPoints(
                sessionHandle,
                flat,
                ANALYSIS_SIZE.width.toDouble(),
                ANALYSIS_SIZE.height.toDouble(),
            )
            // The third component is the engine's visibility flag: points
            // behind the camera project to (0, 0, false) and must not be
            // drawn as an anchor in the top-left corner.
            screenPoints = projected.toList().chunked(3).map { c ->
                if (c[2] > 0.5) {
                    Offset(c[0].toFloat() * canvasSize.width, c[1].toFloat() * canvasSize.height)
                } else {
                    null
                }
            }
            delay(PROJECTION_INTERVAL_MS)
        }
    }

    ArMeasureContent(
        state = state,
        actions = viewModel,
        screenPoints = screenPoints,
        onCanvasSized = { canvasSize = it },
        onTap = { nx, ny ->
            if (canvasSize != Size.Zero) {
                // rayToWorld unprojects with analysis-stream intrinsics, so
                // the tap must be scaled into analysis pixels, not canvas
                // pixels (the two differ by the screen/analysis ratio).
                val hit = MeasureNative.rayToWorld(
                    sessionHandle,
                    nx * ANALYSIS_SIZE.width,
                    ny * ANALYSIS_SIZE.height,
                )
                if (hit != null) {
                    viewModel.addResolvedAnchor(hit[0], hit[1], hit[2], hit[3] > 0.5)
                }
            }
        },
        cameraContent = {
            if (cameraGranted && MeasureNative.available) {
                MeasureCamera(
                    imuRecorder = viewModel.imuRecorder,
                    onTrackingUpdate = { q, hasPlane ->
                        viewModel.onTrackingUpdate(trackingQualityFrom(q), hasPlane)
                    },
                    onSessionReady = {
                        sessionHandle = it
                        viewModel.nativeSessionHandle = it
                    },
                    onDiagnostics = { fps, skew, imuHz, tracked, landmarks, confidence ->
                        viewModel.updateDiagnosticsImpl {
                            copy(
                                frameRateHz = fps,
                                timestampSkewMs = skew,
                                imuRateHz = imuHz,
                                trackedCount = tracked,
                                featureCount = tracked,
                                landmarkCount = landmarks,
                                scaleConfidence = confidence,
                                nativeEngineAvailable = MeasureNative.available,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        },
        onOpenSettings = { backStack.add(Route.Settings) },
        bottomBar = { MeasureBottomBar(backStack, Route.ArMeasure) },
    )
}

/** Requests CAMERA lazily, so the four sensor tools never trigger a camera prompt. */
@Composable
private fun rememberCameraPermission(onResult: (Boolean) -> Unit): Boolean {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberPermissionRequest(Manifest.permission.CAMERA) { result ->
        granted = result
        onResult(result)
    }
    LaunchedEffect(Unit) {
        onResult(granted)
        if (!granted) launcher()
    }
    return granted
}

private const val PROJECTION_INTERVAL_MS = 33L
