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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

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
 * Drives the panorama/photo-sphere sweep. Instead of a guide-dot system, it
 * captures frames *continuously* as the user pans — one frame per fixed increment
 * of device rotation — giving dense overlap for smooth stitching. Frames are fed
 * to the native [StitchNative] Rust stitcher (OpenCV-free).
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

    private val _currentAngle = MutableStateFlow(0f)
    val currentAngle = _currentAngle.asStateFlow()

    private val _currentPitch = MutableStateFlow(0f)
    val currentPitch = _currentPitch.asStateFlow()

    private var nativeHandle: Long = 0L
    private var accumulatedAngle = 0f
    private var accumulatedPitch = 0f
    private var accumulatedRoll = 0f
    private var lastTimestamp = 0L
    private var lastCaptureYaw = 0f
    private var lastCapturePitch = 0f
    private var angularVelocity = 0f
    private var pitchVelocity = 0f

    private var sphereMode = false

    @Volatile
    var latestFrame: Bitmap? = null

    /**
     * Invoked once when a sweep auto-completes (frame budget reached, or the
     * panorama passes a near-full rotation). The owner runs stitch + save.
     */
    var onSweepComplete: (() -> Unit)? = null

    companion object {
        // Continuous capture: one frame per this much rotation (deg). With a
        // ~67° FOV, an 18° step still gives ~73% overlap (plenty) while keeping
        // the frame count — and thus stitch time on low-end phones — bounded.
        private const val CAPTURE_STEP_DEG = 18f
        // Don't capture while whipping the phone (limits motion blur).
        private const val CAPTURE_MAX_VEL_DPS = 120f
        // Bound total frames so the stitch stays fast on low-end hardware.
        private const val MAX_FRAMES = 16
        // Frames are downscaled before stitching to bound memory over a long sweep.
        private const val FRAME_SCALE = 0.6f
        // Auto-finish a flat panorama once it has swept nearly all the way around.
        private const val PANO_MAX_ANGLE = 350f
    }

    fun startSweep(fullSphere: Boolean = false) {
        sphereMode = fullSphere
        accumulatedAngle = 0f
        accumulatedPitch = 0f
        accumulatedRoll = 0f
        lastCaptureYaw = 0f
        lastCapturePitch = 0f
        lastTimestamp = 0L
        angularVelocity = 0f
        pitchVelocity = 0f
        _frameCount.value = 0
        _sweepAngle.value = 0f
        _currentAngle.value = 0f
        _currentPitch.value = 0f

        if (nativeHandle != 0L && StitchNative.isAvailable) StitchNative.free(nativeHandle)
        nativeHandle = if (StitchNative.isAvailable) StitchNative.newSession(fullSphere) else 0L

        captureFrame() // seed with the starting frame

        _isSweeping.value = true
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stopSweep() {
        _isSweeping.value = false
        sensorManager.unregisterListener(this)
    }

    private fun bitmapToRgba(bmp: Bitmap): ByteArray {
        val src = if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
        val buf = ByteBuffer.allocate(src.width * src.height * 4)
        src.copyPixelsToBuffer(buf)
        if (src !== bmp) src.recycle()
        return buf.array()
    }

    private fun captureFrame() {
        val frame = latestFrame ?: return
        val handle = nativeHandle
        if (handle == 0L) return
        val w = (frame.width * FRAME_SCALE).toInt().coerceAtLeast(1)
        val h = (frame.height * FRAME_SCALE).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(frame, w, h, true)
        val rotMatrix = Matrix().apply { postRotate(90f) }
        val rotated = Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, rotMatrix, true)
        scaled.recycle()
        val rgba = bitmapToRgba(rotated)
        StitchNative.addFrame(handle, rgba, rotated.width, rotated.height, accumulatedAngle, accumulatedPitch, accumulatedRoll)
        rotated.recycle()
        _frameCount.value = _frameCount.value + 1
        lastCaptureYaw = accumulatedAngle
        lastCapturePitch = accumulatedPitch
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

            // Continuous capture: whenever the device has rotated far enough since
            // the last frame and isn't moving too fast.
            val movedYaw = Math.abs(accumulatedAngle - lastCaptureYaw)
            val movedPitch = Math.abs(accumulatedPitch - lastCapturePitch)
            val notTooFast = Math.abs(angularVelocity) < CAPTURE_MAX_VEL_DPS &&
                Math.abs(pitchVelocity) < CAPTURE_MAX_VEL_DPS
            if (notTooFast && _frameCount.value < MAX_FRAMES &&
                (movedYaw >= CAPTURE_STEP_DEG || movedPitch >= CAPTURE_STEP_DEG)
            ) {
                captureFrame()
            }
        }
        lastTimestamp = timestamp

        val reachedBudget = _frameCount.value >= MAX_FRAMES
        val fullLoop = !sphereMode && Math.abs(accumulatedAngle) > PANO_MAX_ANGLE
        if (reachedBudget || fullLoop) {
            stopSweep()
            onSweepComplete?.invoke()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * Runs the native stitcher over the captured frames. Returns the stitched
     * JPEG bytes plus its GPano geometry, or null on failure.
     */
    suspend fun stitch(): Pair<ByteArray, PanoInfo>? {
        val handle = nativeHandle
        if (handle == 0L || _frameCount.value < 2) return null
        _isStitching.value = true
        return withContext(Dispatchers.IO) {
            try {
                val jpeg = StitchNative.stitch(handle) ?: return@withContext null
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
                val w = opts.outWidth
                val h = opts.outHeight
                if (w <= 0 || h <= 0) return@withContext null
                val info = PanoInfo(
                    projectionType = if (sphereMode) "equirectangular" else "cylindrical",
                    fullWidth = w,
                    fullHeight = h,
                    croppedWidth = w,
                    croppedHeight = h,
                    croppedLeft = 0,
                    croppedTop = 0,
                )
                Pair(jpeg, info)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            } finally {
                _isStitching.value = false
            }
        }
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
        _frameCount.value = 0
        _sweepAngle.value = 0f
        _isSweeping.value = false
        _isStitching.value = false
        _currentAngle.value = 0f
        _currentPitch.value = 0f
        accumulatedPitch = 0f
        accumulatedRoll = 0f
        pitchVelocity = 0f
        latestFrame = null
    }
}
