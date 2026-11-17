package com.vayunmathur.measure.ui.components

import android.content.Context
import android.util.Size
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.vayunmathur.measure.data.model.TrackingQuality
import com.vayunmathur.measure.platform.CameraIntrinsicsResolver
import com.vayunmathur.measure.platform.vio.VioEngine
import com.vayunmathur.measure.platform.sensor.ImuRecorder
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.Executors

/**
 * Per-frame diagnostics: frame rate, camera/IMU clock skew, IMU rate, tracked feature
 * count, landmark count and scale confidence.
 */
typealias Diagnostics = (Double, Double, Double, Int, Int, Double) -> Unit

/** Analysis resolution: enough texture for tracking, cheap enough to run every frame. */
internal val ANALYSIS_SIZE = Size(640, 480)

/**
 * Camera viewfinder plus the frame pump that drives the Rust VIO tracker.
 *
 * The analyser hands the Y plane straight to the engine. No Bitmap is
 * allocated and no colour conversion happens: tracking only needs
 * luminance, and an RGB conversion every frame would dominate the
 * pipeline's cost.
 */
@Composable
fun MeasureCamera(
    imuRecorder: ImuRecorder,
    onTrackingUpdate: (quality: TrackingQuality, hasPlane: Boolean) -> Unit,
    onEngineReady: (engine: VioEngine?) -> Unit,
    modifier: Modifier = Modifier,
    onDiagnostics: Diagnostics = { _, _, _, _, _, _ -> },
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val surfaceRequest = remember { MutableStateFlow<SurfaceRequest?>(null) }
    val surfaceReqState by surfaceRequest.collectAsState()

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val executor = Executors.newSingleThreadExecutor()
            var provider: ProcessCameraProvider? = null
            val engine = createEngine(context)
            onEngineReady(engine)
            try {
                provider = ProcessCameraProvider.awaitInstance(context)
                provider.unbindAll()

                val preview = Preview.Builder().build()
                preview.setSurfaceProvider { req -> surfaceRequest.value = req }

                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    ANALYSIS_SIZE,
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                )
                            )
                            .build()
                    )
                    // Tracking must act on the newest frame; a backlog would feed the
                    // estimator stale motion and make it diverge.
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                    .also {
                        it.setAnalyzer(
                            executor,
                            VioFrameAnalyzer(engine, imuRecorder, onTrackingUpdate, onDiagnostics),
                        )
                    }

                // Without live IMU the visual tracker can never lock metric
                // scale. Report Limited (the flow's Scanning state) instead of
                // letting a refused high-rate registration crash the session.
                if (!imuRecorder.start()) {
                    onTrackingUpdate(TrackingQuality.Limited, false)
                }
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
                awaitCancellation()
            } finally {
                imuRecorder.stop()
                provider?.unbindAll()
                executor.shutdown()
                engine?.close()
                surfaceRequest.value = null
                onEngineReady(null)
            }
        }
    }

    surfaceReqState?.let { req ->
        CameraXViewfinder(surfaceRequest = req, modifier = modifier.fillMaxSize())
    }
}

private fun createEngine(context: Context): VioEngine? {
    if (!VioEngine.available) return null
    val intr = CameraIntrinsicsResolver.resolve(context, ANALYSIS_SIZE) ?: return null
    return VioEngine(intr)
}

internal class VioFrameAnalyzer(
    private val engine: VioEngine?,
    private val imuRecorder: ImuRecorder,
    private val onTrackingUpdate: (TrackingQuality, Boolean) -> Unit,
    private val onDiagnostics: Diagnostics,
) : ImageAnalysis.Analyzer {

    private var buffer: ByteArray? = null
    private var lastFrameNs = 0L
    private var frameIntervalNs = 0L

    override fun analyze(image: ImageProxy) {
        try {
            val eng = engine ?: return

            // Drain IMU first so every sample older than this frame is already queued,
            // which is what lets the engine align its scale windows.
            val imu = imuRecorder.drain()
            if (imu.isNotEmpty()) eng.pushImu(imu)

            val plane = image.planes[0]
            val src = plane.buffer
            val needed = src.remaining()
            val dst = buffer?.takeIf { it.size >= needed } ?: ByteArray(needed).also { buffer = it }
            src.get(dst, 0, needed)

            val tNs = image.imageInfo.timestamp
            val result = eng.processFrame(
                yPlane = dst,
                width = image.width,
                height = image.height,
                rowStride = plane.rowStride,
                timestampNs = tNs,
            )
            // Cap at Limited while the IMU is down: visual tracking may run,
            // but with no IMU windows the metric scale cannot lock, so Good
            // (the only state that allows placing anchors) must not be quoted.
            val quality = if (!imuRecorder.isActive && result.quality != TrackingQuality.Lost) {
                TrackingQuality.Limited
            } else {
                result.quality
            }
            onTrackingUpdate(quality, result.hasPlane)

            if (lastFrameNs != 0L) {
                frameIntervalNs = (frameIntervalNs * 3 + (tNs - lastFrameNs)) / 4
            }
            lastFrameNs = tNs
            val fps = if (frameIntervalNs > 0) 1e9 / frameIntervalNs else 0.0
            // Camera and IMU share the REALTIME clock on supported devices, so this gap
            // should stay near zero. A large or growing value means the streams cannot
            // be fused and tracking will not converge.
            val skewMs = (tNs - imuRecorder.lastTimestampNs()) / 1e6
            onDiagnostics(
                fps,
                skewMs,
                imuRecorder.rateHz(),
                result.trackedFeatures,
                result.landmarkCount,
                result.scaleConfidence,
            )
        } finally {
            image.close()
        }
    }
}
