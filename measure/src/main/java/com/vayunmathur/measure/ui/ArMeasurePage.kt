package com.vayunmathur.measure.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.fillMaxSize
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.ui.rememberPermissionRequest
import com.vayunmathur.measure.Route
import com.vayunmathur.measure.domain.ArUnavailableReason
import com.vayunmathur.measure.platform.vio.VioEngine
import com.vayunmathur.measure.platform.CameraIntrinsicsResolver
import com.vayunmathur.measure.platform.MeasureViewModel
import com.vayunmathur.measure.platform.onEngineChanged
import com.vayunmathur.measure.platform.onTrackingUpdate
import com.vayunmathur.measure.platform.refreshReticle
import com.vayunmathur.measure.platform.setCameraPermission
import com.vayunmathur.measure.platform.setUnavailableReason
import com.vayunmathur.measure.platform.tickFlow
import com.vayunmathur.measure.platform.VioArHitProvider
import com.vayunmathur.measure.platform.updateDiagnosticsImpl
import com.vayunmathur.measure.ui.components.MeasureBottomBar
import com.vayunmathur.measure.ui.components.ANALYSIS_SIZE
import com.vayunmathur.measure.ui.components.MeasureCamera
import kotlinx.coroutines.delay

@Composable
fun ArMeasurePage(backStack: NavBackStack<Route>, viewModel: MeasureViewModel) {
    val state by viewModel.ar.collectAsState()
    var engine by remember { mutableStateOf<VioEngine?>(null) }
    var screenPoints by remember { mutableStateOf<List<Offset?>>(emptyList()) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    val context = LocalContext.current

    val cameraGranted = rememberCameraPermission { viewModel.setCameraPermission(it) }

    // Availability is decided before any camera work: a device whose
    // camera clock cannot fuse with its IMU, or with no gyro/accel,
    // would otherwise coach "move your phone" forever (the old flow
    // never called timestampsAlignWithSensors at all).
    LaunchedEffect(Unit) {
        viewModel.hitProvider = VioArHitProvider(
            engine = { engine },
            analysisWidth = ANALYSIS_SIZE.width,
            analysisHeight = ANALYSIS_SIZE.height,
        )
        viewModel.setUnavailableReason(availabilityReason(context))
    }

    // Continuous centre-reticle hit test: the reticle, not the finger,
    // is the placement point, and it must snap to the best sane hit
    // every frame rather than resolving one raw ray at tap instant.
    LaunchedEffect(engine) {
        if (engine == null) return@LaunchedEffect
        while (true) {
            viewModel.refreshReticle()
            delay(RETICLE_INTERVAL_MS)
        }
    }

    // Reproject anchors whenever the anchor set or the pose changes.
    LaunchedEffect(state.anchors, state.quality, canvasSize) {
        val eng = engine
        if (eng == null || canvasSize == Size.Zero || state.anchors.isEmpty()) {
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
            // analysis size, not the canvas size.
            val projected = eng.projectPoints(
                flat,
                eng.frameWidth.takeIf { it > 0 }?.toDouble() ?: ANALYSIS_SIZE.width.toDouble(),
                eng.frameHeight.takeIf { it > 0 }?.toDouble() ?: ANALYSIS_SIZE.height.toDouble(),
            )
            // Third component is the visibility flag: points behind the
            // camera must not be drawn in the top-left corner.
            screenPoints = projected.toList().chunked(3).map { c ->
                if (c[2] > 0.5) {
                    Offset(c[0].toFloat() * canvasSize.width, c[1].toFloat() * canvasSize.height)
                } else {
                    null
                }
            }
            viewModel.tickFlow()
            delay(PROJECTION_INTERVAL_MS)
        }
    }

    ArMeasureContent(
        state = state,
        actions = viewModel,
        screenPoints = screenPoints,
        onCanvasSized = { canvasSize = it },
        cameraContent = {
            if (cameraGranted && VioEngine.available) {
                MeasureCamera(
                    imuRecorder = viewModel.imuRecorder,
                    onTrackingUpdate = { q, hasPlane ->
                        viewModel.onTrackingUpdate(q, hasPlane)
                    },
                    onEngineReady = {
                        engine = it
                        viewModel.onEngineChanged(it)
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
                                nativeEngineAvailable = VioEngine.available,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        },
        onOpenSettings = { backStack.add(Route.Settings) },
        onOpenRuler = { backStack.add(Route.Ruler) },
        bottomBar = { MeasureBottomBar(backStack, Route.ArMeasure) },
    )
}

/** Explicit availability states, checked once before the session starts. */
private fun availabilityReason(context: Context): ArUnavailableReason? {
    if (!VioEngine.available) return ArUnavailableReason.NativeEngineMissing
    val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        ?: return ArUnavailableReason.MotionSensorsMissing
    val hasGyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED) != null ||
        sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
    val hasAccel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED) != null ||
        sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
    if (!hasGyro || !hasAccel) return ArUnavailableReason.MotionSensorsMissing
    if (!CameraIntrinsicsResolver.timestampsAlignWithSensors(context)) {
        return ArUnavailableReason.CameraTimestampMismatch
    }
    return null
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
private const val RETICLE_INTERVAL_MS = 100L
