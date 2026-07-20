package com.vayunmathur.camera.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

enum class GuideDotState {
    PENDING,
    ALIGNING,
    CAPTURING,
    CAPTURED
}

data class GuideDot(
    val index: Int,
    val targetAngle: Float,
    val targetPitch: Float = 0f,
    val state: GuideDotState
)

/**
 * Geometry of a stitched panorama, written out as GPano XMP so compliant viewers
 * render the image as panoramic/360. [projectionType] is "cylindrical" for a flat
 * panorama or "equirectangular" for a full sphere.
 */
data class PanoInfo(
    val projectionType: String,
    val fullWidth: Int,
    val fullHeight: Int,
    val croppedWidth: Int,
    val croppedHeight: Int,
    val croppedLeft: Int,
    val croppedTop: Int,
)

/**
 * Drives the panorama/photo-sphere sweep with a guide-dot system: the user aligns
 * the reticle to each target dot and a frame is captured when steady on it. Frames
 * are captured at full resolution, stored JPEG-compressed, and fed to the native
 * [StitchNative] Rust stitcher (OpenCV-free). Panorama and photo-sphere share the
 * exact same capture + stitch path; only the dot layout and GPano tag differ.
 */
class PanoramaEngine(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val _isSweeping = MutableStateFlow(false)
    val isSweeping = _isSweeping.asStateFlow()

    private val _isStitching = MutableStateFlow(false)
    val isStitching = _isStitching.asStateFlow()

    private val _frameCount = MutableStateFlow(0)
    val frameCount = _frameCount.asStateFlow()

    private val _sweepAngle = MutableStateFlow(0f)
    val sweepAngle = _sweepAngle.asStateFlow()

    private val _guideDots = MutableStateFlow<List<GuideDot>>(emptyList())
    val guideDots: StateFlow<List<GuideDot>> = _guideDots.asStateFlow()

    private val _currentAngle = MutableStateFlow(0f)
    val currentAngle: StateFlow<Float> = _currentAngle.asStateFlow()

    private val _currentPitch = MutableStateFlow(0f)
    val currentPitch: StateFlow<Float> = _currentPitch.asStateFlow()

    private val _sweepDirection = MutableStateFlow(0)
    val sweepDirection: StateFlow<Int> = _sweepDirection.asStateFlow()

    private var nativeHandle: Long = 0L
    private val capturedFrames = mutableListOf<ByteArray>()
    private var accumulatedAngle = 0f
    private var accumulatedPitch = 0f
    private var accumulatedRoll = 0f
    private var lastTimestamp = 0L
    private var angularVelocity = 0f
    private var pitchVelocity = 0f

    private var sphereMode = false

    @Volatile
    var latestFrame: Bitmap? = null

    /**
     * Invoked once when a sweep auto-completes (all guide dots captured, or a flat
     * panorama reaches its angular limit). The owner runs stitch + save.
     */
    var onSweepComplete: (() -> Unit)? = null

    companion object {
        // Frames are captured at full analysis resolution and stored
        // JPEG-compressed (decoded one at a time by the native stitcher).
        private const val JPEG_QUALITY = 92
        // A frame is captured when the reticle is within this of a dot and steady.
        private const val ALIGNMENT_THRESHOLD_DEGREES = 3f
        private const val PITCH_THRESHOLD_DEGREES = 5f
        private const val MAX_VELOCITY_DPS = 30f
        // Safety auto-finish for a flat panorama that has swept far enough.
        private const val PANO_MAX_ANGLE = 200f
    }

    fun startSweep(fullSphere: Boolean = false) {
        sphereMode = fullSphere
        accumulatedAngle = 0f
        accumulatedPitch = 0f
        accumulatedRoll = 0f
        lastTimestamp = 0L
        angularVelocity = 0f
        pitchVelocity = 0f
        _frameCount.value = 0
        _sweepAngle.value = 0f
        _sweepDirection.value = 0
        _currentAngle.value = 0f
        _currentPitch.value = 0f

        // Guide-dot layout: a full-sphere grid (pitch rows) or a flat horizontal ring.
        val dots = if (fullSphere) {
            var idx = 0
            val result = mutableListOf<GuideDot>()
            val rows = listOf(
                0f to 30f,
                -30f to 40f,
                30f to 40f,
                -60f to 72f,
                60f to 72f
            )
            for ((pitch, yawStep) in rows) {
                val count = (360f / yawStep).toInt()
                for (i in 0 until count) {
                    result.add(GuideDot(idx, i * yawStep, pitch, if (idx == 0) GuideDotState.CAPTURING else GuideDotState.PENDING))
                    idx++
                }
            }
            result
        } else {
            (0 until 7).map { i ->
                GuideDot(i, i * 30f, 0f, if (i == 0) GuideDotState.CAPTURING else GuideDotState.PENDING)
            }
        }
        _guideDots.value = dots

        if (nativeHandle != 0L && StitchNative.isAvailable) StitchNative.free(nativeHandle)
        nativeHandle = if (StitchNative.isAvailable) StitchNative.newSession(fullSphere) else 0L
        capturedFrames.clear()

        captureFrame() // seed with the starting frame
        updateDotState(0, GuideDotState.CAPTURED)

        _isSweeping.value = true
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stopSweep() {
        _isSweeping.value = false
        sensorManager.unregisterListener(this)
    }

    private fun captureFrame() {
        val frame = latestFrame ?: return
        val handle = nativeHandle
        if (handle == 0L) return
        // Rotate to upright and JPEG-compress. Storing frames compressed keeps
        // memory bounded across a long, high-resolution sweep (they're decoded on
        // demand by the native stitcher, one at a time).
        val rotMatrix = Matrix().apply { postRotate(90f) }
        val rotated = Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, rotMatrix, true)
        val baos = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
        rotated.recycle()
        val jpeg = baos.toByteArray()
        // Keep the same JPEG bytes the native registrar receives, so the GPU
        // compositor can decode kept frames into textures by capture index.
        capturedFrames.add(jpeg)
        StitchNative.addFrame(handle, jpeg, accumulatedAngle, accumulatedPitch, accumulatedRoll)
        _frameCount.value = _frameCount.value + 1
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!_isSweeping.value) return
        if (event.sensor.type != Sensor.TYPE_GYROSCOPE) return

        val timestamp = event.timestamp
        if (lastTimestamp != 0L) {
            val dt = (timestamp - lastTimestamp) / 1_000_000_000f
            val pitchRate = Math.toDegrees(event.values[0].toDouble()).toFloat()
            val yawRate = -Math.toDegrees(event.values[1].toDouble()).toFloat()
            val rollRate = Math.toDegrees(event.values[2].toDouble()).toFloat()
            pitchVelocity = pitchRate
            angularVelocity = yawRate
            accumulatedPitch += pitchRate * dt
            accumulatedAngle += yawRate * dt
            accumulatedRoll += rollRate * dt
            _currentPitch.value = accumulatedPitch
            _currentAngle.value = accumulatedAngle
            _sweepAngle.value = Math.abs(accumulatedAngle)

            // Mirror the dot ring to match the sweep direction (flat pano only).
            if (_sweepDirection.value == 0 && Math.abs(accumulatedAngle) > 10f) {
                val dir = if (accumulatedAngle > 0) 1 else -1
                _sweepDirection.value = dir
                if (dir == -1 && !sphereMode) {
                    _guideDots.value = _guideDots.value.map { it.copy(targetAngle = -it.targetAngle) }
                }
            }

            checkDotAlignment()
        }
        lastTimestamp = timestamp

        val allCaptured = _guideDots.value.all { it.state == GuideDotState.CAPTURED }
        if (allCaptured || (!sphereMode && Math.abs(accumulatedAngle) > PANO_MAX_ANGLE)) {
            stopSweep()
            onSweepComplete?.invoke()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** Capture the frame for the nearest aligned, steady dot; update dot states. */
    private fun checkDotAlignment() {
        val dots = _guideDots.value
        val currentAng = accumulatedAngle
        var captured = false

        _guideDots.value = dots.map { dot ->
            if (dot.state == GuideDotState.CAPTURED) return@map dot

            val angleDiff = Math.abs(wrapDegrees(currentAng - dot.targetAngle))
            val pitchDiff = Math.abs(accumulatedPitch - dot.targetPitch)
            val withinCapture = angleDiff < ALIGNMENT_THRESHOLD_DEGREES && pitchDiff < PITCH_THRESHOLD_DEGREES
            val withinAligning = angleDiff < ALIGNMENT_THRESHOLD_DEGREES * 2 && pitchDiff < PITCH_THRESHOLD_DEGREES * 2
            val steadyEnough = Math.abs(angularVelocity) < MAX_VELOCITY_DPS && Math.abs(pitchVelocity) < MAX_VELOCITY_DPS

            when {
                withinCapture && steadyEnough && !captured -> {
                    captureFrame()
                    captured = true
                    dot.copy(state = GuideDotState.CAPTURED)
                }
                withinCapture -> dot.copy(state = GuideDotState.CAPTURING)
                withinAligning -> dot.copy(state = GuideDotState.ALIGNING)
                dot.state != GuideDotState.PENDING -> dot.copy(state = GuideDotState.PENDING)
                else -> dot
            }
        }
    }

    private fun updateDotState(index: Int, state: GuideDotState) {
        _guideDots.value = _guideDots.value.map {
            if (it.index == index) it.copy(state = state) else it
        }
    }

    /** Smallest signed difference of an angle in degrees, normalized to (-180, 180]. */
    private fun wrapDegrees(deg: Float): Float {
        var d = deg % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    /**
     * Produces the stitched panorama. Tries the GPU compositor first (Rust
     * registration → [GpuStitcher] warp+blend), falling back to the full Rust
     * CPU stitcher if registration, EGL/GLES, or GPU compositing fails. Returns
     * the JPEG bytes plus GPano geometry, or null.
     */
    suspend fun stitch(): Pair<ByteArray, PanoInfo>? {
        val handle = nativeHandle
        if (handle == 0L || _frameCount.value < 2) return null
        _isStitching.value = true
        return withContext(Dispatchers.IO) {
            try {
                gpuStitch(handle) ?: cpuStitch(handle)
            } catch (e: Exception) {
                e.printStackTrace()
                try {
                    cpuStitch(handle)
                } catch (e2: Exception) {
                    e2.printStackTrace()
                    null
                }
            } finally {
                _isStitching.value = false
            }
        }
    }

    /** GPU path: Rust registration blob → [GpuStitcher] → JPEG. Null on any failure. */
    private fun gpuStitch(handle: Long): Pair<ByteArray, PanoInfo>? {
        val t0 = System.currentTimeMillis()
        val blob = StitchNative.estimate(handle) ?: return null
        val estimate = GpuStitcher.parseEstimate(blob) ?: return null
        val tReg = System.currentTimeMillis()
        val result = GpuStitcher.composite(estimate, capturedFrames) ?: return null
        val tComp = System.currentTimeMillis()

        val baos = ByteArrayOutputStream()
        val ok = result.bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
        val cw = result.bitmap.width
        val ch = result.bitmap.height
        result.bitmap.recycle()
        if (!ok || cw <= 0 || ch <= 0) return null

        val info = PanoInfo(
            projectionType = if (sphereMode) "equirectangular" else "cylindrical",
            fullWidth = result.fullWidth,
            fullHeight = result.fullHeight,
            croppedWidth = cw,
            croppedHeight = ch,
            croppedLeft = result.croppedLeft,
            croppedTop = result.croppedTop,
        )
        android.util.Log.i(
            "PanoramaEngine",
            "GPU stitch: estimate=${tReg - t0}ms composite=${tComp - tReg}ms -> ${cw}x$ch"
        )
        return Pair(baos.toByteArray(), info)
    }

    /** CPU fallback: the full Rust stitcher. */
    private fun cpuStitch(handle: Long): Pair<ByteArray, PanoInfo>? {
        val jpeg = StitchNative.stitch(handle) ?: return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
        val w = opts.outWidth
        val h = opts.outHeight
        if (w <= 0 || h <= 0) return null
        val info = PanoInfo(
            projectionType = if (sphereMode) "equirectangular" else "cylindrical",
            fullWidth = w,
            fullHeight = h,
            croppedWidth = w,
            croppedHeight = h,
            croppedLeft = 0,
            croppedTop = 0,
        )
        return Pair(jpeg, info)
    }

    fun saveToMediaStore(jpeg: ByteArray, info: PanoInfo) {
        val prefix = if (sphereMode) "SPHERE" else "PANO"
        val contentValues = MediaStoreSaver.imageValues("${prefix}_${MediaStoreSaver.timestamp()}.jpg")
        val tagged = PanoXmp.injectXmp(jpeg, PanoXmp.buildGPanoXmp(info))
        MediaStoreSaver.saveJpegBytes(context.contentResolver, contentValues, tagged)
    }

    fun reset() {
        if (nativeHandle != 0L && StitchNative.isAvailable) StitchNative.free(nativeHandle)
        nativeHandle = 0L
        capturedFrames.clear()
        _frameCount.value = 0
        _sweepAngle.value = 0f
        _isSweeping.value = false
        _isStitching.value = false
        _currentAngle.value = 0f
        _currentPitch.value = 0f
        _sweepDirection.value = 0
        _guideDots.value = emptyList()
        accumulatedPitch = 0f
        accumulatedRoll = 0f
        pitchVelocity = 0f
        latestFrame = null
    }
}
