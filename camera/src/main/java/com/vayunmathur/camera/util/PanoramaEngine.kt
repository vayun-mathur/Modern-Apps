package com.vayunmathur.camera.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.Rect
import org.opencv.features2d.FlannBasedMatcher
import org.opencv.features2d.SIFT
import org.opencv.imgproc.Imgproc
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    private val frames = mutableListOf<Bitmap>()
    private val frameOrientations = mutableListOf<Triple<Float, Float, Float>>()
    private var accumulatedAngle = 0f
    private var accumulatedPitch = 0f
    private var accumulatedRoll = 0f
    private var lastTimestamp = 0L
    private var lastCaptureAngle = 0f
    private var angularVelocity = 0f
    private var pitchVelocity = 0f
    private var rollVelocity = 0f

    private var sphereMode = false
    private val ALIGNMENT_THRESHOLD_DEGREES = 3f
    private val PITCH_THRESHOLD_DEGREES = 5f
    private val MAX_VELOCITY_DPS = 30f

    @Volatile
    var latestFrame: Bitmap? = null

    companion object {
        private const val FRAME_SCALE = 0.75f

        init {
            OpenCVLoader.initLocal()
        }
    }

    fun startSweep(fullSphere: Boolean = false) {
        sphereMode = fullSphere
        frames.clear()
        accumulatedAngle = 0f
        accumulatedPitch = 0f
        accumulatedRoll = 0f
        lastCaptureAngle = 0f
        lastTimestamp = 0L
        angularVelocity = 0f
        pitchVelocity = 0f
        rollVelocity = 0f
        _frameCount.value = 0
        _sweepAngle.value = 0f
        _sweepDirection.value = 0
        _currentAngle.value = 0f
        _currentPitch.value = 0f

        frameOrientations.clear()

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

        captureFrame()
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
        val w = (frame.width * FRAME_SCALE).toInt()
        val h = (frame.height * FRAME_SCALE).toInt()
        val scaled = Bitmap.createScaledBitmap(frame, w, h, true)
        val rotMatrix = Matrix().apply { postRotate(90f) }
        val rotated = Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, rotMatrix, true)
        scaled.recycle()
        frames.add(rotated)
        frameOrientations.add(Triple(accumulatedAngle, accumulatedPitch, accumulatedRoll))
        _frameCount.value = frames.size
        lastCaptureAngle = accumulatedAngle
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
            rollVelocity = rollRate
            accumulatedPitch += pitchRate * dt
            accumulatedAngle += yawRate * dt
            accumulatedRoll += rollRate * dt
            _currentPitch.value = accumulatedPitch
            _currentAngle.value = accumulatedAngle
            _sweepAngle.value = Math.abs(accumulatedAngle)

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
        if (allCaptured || (!sphereMode && Math.abs(accumulatedAngle) > 200f)) {
            stopSweep()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun checkDotAlignment() {
        val dots = _guideDots.value
        val currentAng = accumulatedAngle
        var captured = false

        _guideDots.value = dots.map { dot ->
            if (dot.state == GuideDotState.CAPTURED) return@map dot

            val angleDiff = Math.abs(currentAng - dot.targetAngle)
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

    suspend fun stitch(): Bitmap? {
        if (frames.size < 2) return null
        _isStitching.value = true
        return withContext(Dispatchers.IO) {
            try {
                if (sphereMode) stitchSphere() else stitchManual()
            } catch (e: Exception) {
                e.printStackTrace()
                null
            } finally {
                _isStitching.value = false
            }
        }
    }

    private fun estimateFocalLength(width: Int, hfovDegrees: Double = 67.0): Double {
        return (width / 2.0) / Math.tan(Math.toRadians(hfovDegrees / 2.0))
    }

    private fun projectCylindrical(src: Mat, f: Double): Mat {
        val h = src.rows()
        val w = src.cols()
        val cx = w / 2.0
        val cy = h / 2.0
        val dst = Mat.zeros(h, w, src.type())
        for (y in 0 until h) {
            for (x in 0 until w) {
                val theta = (x - cx) / f
                val hNorm = (y - cy) / f
                val srcX = f * Math.tan(theta) + cx
                val srcY = hNorm * (1.0 / Math.cos(theta)) * f + cy
                val sx = srcX.toInt()
                val sy = srcY.toInt()
                if (sx in 0 until w && sy in 0 until h) {
                    val pixel = src.get(sy, sx)
                    if (pixel != null) dst.put(y, x, *pixel)
                }
            }
        }
        return dst
    }

    private fun findTranslation(base: Mat, next: Mat, sift: SIFT, matcher: FlannBasedMatcher): Pair<Int, Int>? {
        val gray1 = Mat()
        val gray2 = Mat()
        Imgproc.cvtColor(base, gray1, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.cvtColor(next, gray2, Imgproc.COLOR_RGBA2GRAY)

        val kp1 = MatOfKeyPoint()
        val desc1 = Mat()
        val kp2 = MatOfKeyPoint()
        val desc2 = Mat()
        sift.detectAndCompute(gray1, Mat(), kp1, desc1)
        sift.detectAndCompute(gray2, Mat(), kp2, desc2)
        gray1.release()
        gray2.release()

        if (desc1.empty() || desc2.empty() || desc1.rows() < 10 || desc2.rows() < 10) return null

        val knnMatches = mutableListOf<MatOfDMatch>()
        matcher.knnMatch(desc2, desc1, knnMatches, 2)
        desc1.release()
        desc2.release()

        val goodMatches = knnMatches.mapNotNull { m ->
            val list = m.toList()
            if (list.size >= 2 && list[0].distance < 0.7f * list[1].distance) list[0] else null
        }
        if (goodMatches.size < 6) return null

        val kpList1 = kp1.toList()
        val kpList2 = kp2.toList()
        kp1.release()
        kp2.release()

        val dxList = goodMatches.map { m ->
            kpList1[m.trainIdx].pt.x - kpList2[m.queryIdx].pt.x
        }.sorted()
        val dyList = goodMatches.map { m ->
            kpList1[m.trainIdx].pt.y - kpList2[m.queryIdx].pt.y
        }.sorted()

        val medianDx = dxList[dxList.size / 2].toInt()
        val medianDy = dyList[dyList.size / 2].toInt()
        return Pair(medianDx, medianDy)
    }

    private fun stitchManual(): Bitmap? {
        return try {
            val mats = frames.map { bmp ->
                val mat = Mat()
                Utils.bitmapToMat(bmp, mat)
                mat
            }

            val f = estimateFocalLength(mats[0].cols())
            val cylFrames = mats.map { projectCylindrical(it, f) }
            mats.forEach { it.release() }

            val sift = SIFT.create(3000)
            val matcher = FlannBasedMatcher.create()

            // Compute pairwise translations
            val offsets = mutableListOf(Pair(0, 0))
            for (i in 1 until cylFrames.size) {
                val trans = findTranslation(cylFrames[i - 1], cylFrames[i], sift, matcher)
                if (trans == null) {
                    cylFrames.forEach { it.release() }
                    return null
                }
                val prev = offsets.last()
                offsets.add(Pair(prev.first + trans.first, prev.second + trans.second))
            }

            val h = cylFrames[0].rows()
            val w = cylFrames[0].cols()
            val minX = offsets.minOf { it.first }
            val maxX = offsets.maxOf { it.first } + w
            val minY = offsets.minOf { it.second }
            val maxY = offsets.maxOf { it.second } + h
            val canvasW = maxX - minX
            val canvasH = maxY - minY
            if (canvasW <= 0 || canvasH <= 0 || canvasW > 20000 || canvasH > 20000) {
                cylFrames.forEach { it.release() }
                return null
            }

            val canvas = Mat.zeros(canvasH, canvasW, cylFrames[0].type())
            val weightCanvas = Mat.zeros(canvasH, canvasW, CvType.CV_32FC1)

            for (i in cylFrames.indices) {
                val ox = offsets[i].first - minX
                val oy = offsets[i].second - minY
                val frame = cylFrames[i]

                // Build a weight map: higher in center, falls off at edges
                val fw = Mat.zeros(h, w, CvType.CV_32FC1)
                for (y in 0 until h) {
                    val wy = (Math.min(y, h - 1 - y).toFloat() / (h / 2f)).coerceIn(0f, 1f)
                    for (x in 0 until w) {
                        val px = frame.get(y, x) ?: continue
                        if (px[0] == 0.0 && px[1] == 0.0 && px[2] == 0.0) continue
                        val wx = (Math.min(x, w - 1 - x).toFloat() / (w / 2f)).coerceIn(0f, 1f)
                        fw.put(y, x, (wx * wy).toDouble())
                    }
                }

                for (y in 0 until h) {
                    val cy = oy + y
                    if (cy < 0 || cy >= canvasH) continue
                    for (x in 0 until w) {
                        val cx = ox + x
                        if (cx < 0 || cx >= canvasW) continue
                        val pixel = frame.get(y, x) ?: continue
                        if (pixel[0] == 0.0 && pixel[1] == 0.0 && pixel[2] == 0.0) continue
                        val fwVal = fw.get(y, x)[0].toFloat()
                        val existingWeight = weightCanvas.get(cy, cx)[0].toFloat()
                        val totalWeight = existingWeight + fwVal
                        if (totalWeight > 0f) {
                            val existing = canvas.get(cy, cx)
                            val blendR = (existing[0] * existingWeight + pixel[0] * fwVal) / totalWeight
                            val blendG = (existing[1] * existingWeight + pixel[1] * fwVal) / totalWeight
                            val blendB = (existing[2] * existingWeight + pixel[2] * fwVal) / totalWeight
                            val blendA = (existing[3] * existingWeight + pixel[3] * fwVal) / totalWeight
                            canvas.put(cy, cx, blendR, blendG, blendB, blendA)
                            weightCanvas.put(cy, cx, totalWeight.toDouble())
                        }
                    }
                }
                fw.release()
            }

            cylFrames.forEach { it.release() }
            weightCanvas.release()

            // Crop out black borders
            val grayCanvas = Mat()
            Imgproc.cvtColor(canvas, grayCanvas, Imgproc.COLOR_RGBA2GRAY)
            val thresh = Mat()
            Imgproc.threshold(grayCanvas, thresh, 1.0, 255.0, Imgproc.THRESH_BINARY)
            grayCanvas.release()

            // Find the largest interior rectangle by scanning rows/cols
            var top = 0; var bottom = canvasH - 1; var left = 0; var right = canvasW - 1
            outer@ for (y in 0 until canvasH) {
                for (x in 0 until canvasW) {
                    if (thresh.get(y, x)[0] == 0.0) { top = y + 1; continue@outer }
                }
                break
            }
            outer@ for (y in canvasH - 1 downTo top) {
                for (x in 0 until canvasW) {
                    if (thresh.get(y, x)[0] == 0.0) { bottom = y - 1; continue@outer }
                }
                break
            }
            outer@ for (x in 0 until canvasW) {
                for (y in top..bottom) {
                    if (thresh.get(y, x)[0] == 0.0) { left = x + 1; continue@outer }
                }
                break
            }
            outer@ for (x in canvasW - 1 downTo left) {
                for (y in top..bottom) {
                    if (thresh.get(y, x)[0] == 0.0) { right = x - 1; continue@outer }
                }
                break
            }
            thresh.release()

            val cropW = right - left + 1
            val cropH = bottom - top + 1
            val cropped = if (cropW > 10 && cropH > 10) {
                Mat(canvas, Rect(left, top, cropW, cropH)).clone()
            } else {
                canvas.clone()
            }
            canvas.release()

            val bitmap = Bitmap.createBitmap(cropped.cols(), cropped.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(cropped, bitmap)
            cropped.release()
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    private fun stitchSphere(): Bitmap? {
        if (frames.isEmpty() || frameOrientations.size != frames.size) return null

        val frameW = frames[0].width
        val frameH = frames[0].height
        val f = estimateFocalLength(frameH)
        val cx = frameW / 2.0
        val cy = frameH / 2.0

        val eqW = (frameH * 360.0 / 67.0).toInt().coerceIn(800, 4096)
        val eqH = eqW / 2

        val accumR = FloatArray(eqW * eqH)
        val accumG = FloatArray(eqW * eqH)
        val accumB = FloatArray(eqW * eqH)
        val accumW = FloatArray(eqW * eqH)

        for (i in frames.indices) {
            val frame = frames[i]
            val (yawDeg, pitchDeg, rollDeg) = frameOrientations[i]
            val yawRad = Math.toRadians(yawDeg.toDouble())
            val pitchRad = Math.toRadians(-pitchDeg.toDouble())
            val rollRad = Math.toRadians(-rollDeg.toDouble())
            val cosYaw = Math.cos(yawRad)
            val sinYaw = Math.sin(yawRad)
            val cosPitch = Math.cos(pitchRad)
            val sinPitch = Math.sin(pitchRad)
            val cosRoll = Math.cos(rollRad)
            val sinRoll = Math.sin(rollRad)

            val pixels = IntArray(frameW * frameH)
            frame.getPixels(pixels, 0, frameW, 0, 0, frameW, frameH)

            for (py in 0 until frameH) {
                val dy = -(py - cy) / f
                for (px in 0 until frameW) {
                    val dx = (px - cx) / f
                    val dz = 1.0
                    val len = Math.sqrt(dx * dx + dy * dy + dz * dz)
                    val ndx = dx / len; val ndy = dy / len; val ndz = dz / len

                    // Roll around Z axis
                    val zx = ndx * cosRoll - ndy * sinRoll
                    val zy = ndx * sinRoll + ndy * cosRoll
                    val zz = ndz

                    // Pitch around X axis
                    val rx = zx
                    val ry = zy * cosPitch - zz * sinPitch
                    val rz = zy * sinPitch + zz * cosPitch

                    // Yaw around Y axis
                    val wx = rx * cosYaw + rz * sinYaw
                    val wy = ry
                    val wz = -rx * sinYaw + rz * cosYaw

                    val worldYaw = Math.atan2(wx, wz)
                    val worldPitch = Math.asin(wy.coerceIn(-1.0, 1.0))

                    val eqX = ((worldYaw + Math.PI) / (2.0 * Math.PI) * eqW).toInt()
                    val eqY = ((Math.PI / 2.0 - worldPitch) / Math.PI * eqH).toInt()

                    if (eqX in 0 until eqW && eqY in 0 until eqH) {
                        val pixel = pixels[py * frameW + px]
                        val r = (pixel shr 16) and 0xFF
                        val g = (pixel shr 8) and 0xFF
                        val b = pixel and 0xFF

                        val edgeX = Math.min(px, frameW - 1 - px).toFloat() / (frameW / 2f)
                        val edgeY = Math.min(py, frameH - 1 - py).toFloat() / (frameH / 2f)
                        val w = (edgeX * edgeY).coerceIn(0f, 1f)

                        val idx = eqY * eqW + eqX
                        accumR[idx] += r * w
                        accumG[idx] += g * w
                        accumB[idx] += b * w
                        accumW[idx] += w
                    }
                }
            }
        }

        val outPixels = IntArray(eqW * eqH)
        for (i in outPixels.indices) {
            if (accumW[i] > 0f) {
                val r = (accumR[i] / accumW[i]).toInt().coerceIn(0, 255)
                val g = (accumG[i] / accumW[i]).toInt().coerceIn(0, 255)
                val b = (accumB[i] / accumW[i]).toInt().coerceIn(0, 255)
                outPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val full = Bitmap.createBitmap(eqW, eqH, Bitmap.Config.ARGB_8888)
        full.setPixels(outPixels, 0, eqW, 0, 0, eqW, eqH)

        // Crop to covered region
        var top = eqH; var bottom = 0; var left = eqW; var right = 0
        for (ey in 0 until eqH) {
            for (ex in 0 until eqW) {
                if (accumW[ey * eqW + ex] > 0f) {
                    if (ey < top) top = ey
                    if (ey > bottom) bottom = ey
                    if (ex < left) left = ex
                    if (ex > right) right = ex
                }
            }
        }
        if (right > left && bottom > top) {
            val cropped = Bitmap.createBitmap(full, left, top, right - left + 1, bottom - top + 1)
            full.recycle()
            return cropped
        }
        return full
    }

    fun saveToMediaStore(bitmap: Bitmap) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val prefix = if (sphereMode) "SPHERE" else "PANO"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "${prefix}_$timestamp.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
        }
        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
        ) ?: return
        context.contentResolver.openOutputStream(uri)?.use { os ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, os)
        }
    }

    fun reset() {
        frames.forEach { it.recycle() }
        frames.clear()
        frameOrientations.clear()
        _frameCount.value = 0
        _sweepAngle.value = 0f
        _isSweeping.value = false
        _isStitching.value = false
        _guideDots.value = emptyList()
        _currentAngle.value = 0f
        _currentPitch.value = 0f
        _sweepDirection.value = 0
        accumulatedPitch = 0f
        accumulatedRoll = 0f
        pitchVelocity = 0f
        rollVelocity = 0f
    }
}
