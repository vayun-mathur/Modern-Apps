package com.vayunmathur.library.sensor

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/** A fused device-orientation sample: heading, pitch, roll and the view-pointing direction. */
data class DeviceOrientation(
    val azimuthTrueDeg: Double, // true-north yaw of device top
    val azimuthMagDeg: Double,
    val pitchDeg: Double,
    val rollDeg: Double,
    val accuracy: Int = SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
    val declinationDeg: Double = 0.0,
    // "Window" model: the view looks where the BACK of the phone points, i.e. the
    // device -Z axis (the camera's optical axis), expressed in world ENU. Hold the
    // phone up like a pane of glass and what is drawn matches what's behind it (and
    // what the AR camera sees). Flat screen-up => back faces the ground => nadir;
    // vertical => horizon; back facing the zenith => alt +90.
    // viewRotationDeg is the true roll about that -Z axis, so world-anchored content
    // stays fixed as the phone rolls.
    val pointingAzTrueDeg: Double = azimuthTrueDeg,
    val pointingAltDeg: Double = 0.0,
    val viewRotationDeg: Double = 0.0
) {
    val pointingAzRad get() = Math.toRadians(pointingAzTrueDeg)
    val pointingAltRad get() = Math.toRadians(pointingAltDeg)
    val viewRotationRad get() = Math.toRadians(viewRotationDeg)
}

/** Fuses rotation-vector and magnetic sensors into [DeviceOrientation] samples. */
class OrientationManager(private val context: Context) : SensorEventListener {
    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager?
    private val _orientation = MutableStateFlow<DeviceOrientation?>(null)
    val orientation: StateFlow<DeviceOrientation?> = _orientation
    private var declinationDeg = 0.0
    private var accel = FloatArray(SENSOR_VECTOR_SIZE)
    private var mag = FloatArray(SENSOR_VECTOR_SIZE)
    private var hasAccel = false
    private var hasMag = false
    private var smoothedAz = 0.0
    private var smoothedPitch = 0.0
    private var smoothedRoll = 0.0
    private var smoothedPointingAzMag = 0.0
    private var smoothedPointingAlt = 0.0
    private var smoothedViewRot = 0.0
    private var first = true
    private val alpha = 0.15f
    private val alphaPointing = 0.15f
    private var accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
    private var running = false
    private var useRotationVector = true
    private var lastWorldMatrix = FloatArray(ROTATION_MATRIX_SIZE)

    fun updateLocation(lat: Double, lon: Double, alt: Float = 0f) {
        declinationDeg = try {
            GeomagneticField(
                lat.toFloat(),
                lon.toFloat(),
                alt,
                System.currentTimeMillis()
            ).declination.toDouble()
        } catch (_: Exception) { 0.0 }
    }

    fun start() {
        if (running) return
        running = true
        first = true
        val rotVec = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotVec != null) {
            sensorManager.registerListener(this, rotVec, SensorManager.SENSOR_DELAY_GAME)
            useRotationVector = true
        } else {
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
            sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
            useRotationVector = false
        }
    }

    fun stop() {
        if (!running) return
        running = false
        sensorManager.unregisterListener(this)
        hasAccel = false
        hasMag = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> handleRotationVector(event.values)
            Sensor.TYPE_ACCELEROMETER -> {
                accel = event.values.clone()
                hasAccel = true
                if (!useRotationVector) tryFallback()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                mag = event.values.clone()
                hasMag = true
                if (!useRotationVector) tryFallback()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, acc: Int) { accuracy = acc }

    @Suppress("DEPRECATION")
    private fun handleRotationVector(values: FloatArray) {
        val deviceToWorld = FloatArray(ROTATION_MATRIX_SIZE)
        SensorManager.getRotationMatrixFromVector(deviceToWorld, values)
        lastWorldMatrix = deviceToWorld.clone()

        val displayRot = windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        val remapped = FloatArray(ROTATION_MATRIX_SIZE)
        remapForDisplay(deviceToWorld, displayRot, remapped)
        val orientationAngles = FloatArray(SENSOR_VECTOR_SIZE)
        SensorManager.getOrientation(remapped, orientationAngles)
        val azMag = deviceTopHeadingDeg(deviceToWorld)
        val pitch = Math.toDegrees(orientationAngles[1].toDouble())
        val roll = Math.toDegrees(orientationAngles[2].toDouble())

        val (pointAzMag, pointAlt) = pointingFromDeviceToWorld(deviceToWorld)
        val viewRot = viewRotationFromDeviceToWorld(deviceToWorld)

        if (first) {
            smoothedAz = azMag
            smoothedPitch = pitch
            smoothedRoll = roll
            smoothedPointingAzMag = pointAzMag
            smoothedPointingAlt = pointAlt
            smoothedViewRot = if (viewRot.isNaN()) 0.0 else viewRot
            first = false
        } else {
            smoothAngles(azMag, pitch, roll, pointAzMag, pointAlt, viewRot)
        }
        emitOrientation()
    }

    private fun smoothAngles(
        azMag: Double,
        pitch: Double,
        roll: Double,
        pointAzMag: Double,
        pointAlt: Double,
        viewRot: Double
    ) {
        smoothedAz = smoothHeading(smoothedAz, azMag, alpha)
        smoothedPitch += (pitch - smoothedPitch) * alpha
        smoothedRoll += (roll - smoothedRoll) * alpha
        smoothedPointingAzMag = smoothHeading(smoothedPointingAzMag, pointAzMag, alphaPointing)
        smoothedPointingAlt += (pointAlt - smoothedPointingAlt) * alphaPointing
        if (!viewRot.isNaN()) {
            smoothedViewRot += angleDelta(smoothedViewRot, viewRot) * alpha
        }
    }

    private fun emitOrientation() {
        val trueAz = wrapPositive(smoothedAz + declinationDeg)
        val pointTrueAz = wrapPositive(smoothedPointingAzMag + declinationDeg)
        _orientation.value = DeviceOrientation(
            azimuthTrueDeg = trueAz,
            azimuthMagDeg = smoothedAz,
            pitchDeg = smoothedPitch,
            rollDeg = smoothedRoll,
            accuracy = accuracy,
            declinationDeg = declinationDeg,
            pointingAzTrueDeg = pointTrueAz,
            pointingAltDeg = smoothedPointingAlt,
            viewRotationDeg = smoothedViewRot
        )
    }

    private fun smoothHeading(current: Double, target: Double, smoothing: Float): Double {
        return wrapPositive(current + angleDelta(current, target) * smoothing)
    }

    // Window model: the view forward is the back of the phone = device -Z axis (the
    // camera's optical axis) in world ENU. -Z is the negated third column of the
    // device->world matrix. Back facing the zenith => alt +90, horizon => 0, ground
    // => -90. Uses the whole axis (not Y) so it's stable to charging-port up/down.
    // Flat-compass heading: azimuth of the device +Y (top edge) projected onto the
    // horizontal plane, taken straight from the device->world matrix. Matches the
    // "true-north yaw of device top" contract and, like the pointing* values, is
    // robust to tilt — unlike getOrientation()[0], which gimbal-locks near vertical
    // and was additionally being read off the AR "window" remap (correct only for a
    // phone held up, not a flat compass). This is why the sky view pointed accurately
    // while the compass drifted.
    private fun deviceTopHeadingDeg(matrix: FloatArray): Double {
        val east = matrix[1].toDouble()
        val north = matrix[4].toDouble()
        return wrapPositive(Math.toDegrees(atan2(east, north)))
    }

    private fun pointingFromDeviceToWorld(matrix: FloatArray): Pair<Double, Double> {
        val east = -matrix[2].toDouble()
        val north = -matrix[5].toDouble()
        val up = -matrix[MATRIX_UP_Z].toDouble()
        val az = wrapPositive(Math.toDegrees(atan2(east, north)))
        val alt = Math.toDegrees(asin(up.coerceIn(-1.0, 1.0)))
        return az to alt
    }

    // Roll about the viewing axis, returned as the rotation (deg) to apply to the
    // projected sky so celestial-up lines up with the phone's physical up. Derived
    // straight from the rotation matrix (getOrientation's "roll" is about device Y,
    // which is the wrong axis once the phone is held vertical to look at the sky).
    // Returns NaN near the zenith/nadir where roll is undefined.
    private fun viewRotationFromDeviceToWorld(matrix: FloatArray): Double {
        // Camera forward = device -Z (out the back, toward the sky).
        val forwardX = -matrix[2].toDouble()
        val forwardY = -matrix[5].toDouble()
        val forwardZ = -matrix[MATRIX_UP_Z].toDouble()
        // Device screen "up" = +Y column (top edge of the phone), already ⟂ to f.
        val upX = matrix[1].toDouble()
        val upY = matrix[4].toDouble()
        val upZ = matrix[7].toDouble()
        // Celestial-up reference: world zenith (0,0,1) projected into screen plane.
        val zenithDotForward = forwardZ // world zenith · f
        var projX = -zenithDotForward * forwardX
        var projY = -zenithDotForward * forwardY
        var projZ = 1.0 - zenithDotForward * forwardZ
        val projLen = sqrt(projX * projX + projY * projY + projZ * projZ)
        // Looking near straight up/down: roll undefined.
        if (projLen < ROLL_UNDEFINED_EPSILON) return Double.NaN
        projX /= projLen
        projY /= projLen
        projZ /= projLen
        val dot = projX * upX + projY * upY + projZ * upZ
        // (P_up × u) · f => signed angle of device-up from celestial-up about f.
        val crossX = projY * upZ - projZ * upY
        val crossY = projZ * upX - projX * upZ
        val crossZ = projX * upY - projY * upX
        val crossDotForward = crossX * forwardX + crossY * forwardY + crossZ * forwardZ
        val rollRad = atan2(crossDotForward, dot)
        // Rotate the drawn sky by -roll so it stays fixed to the world as the phone rolls.
        return Math.toDegrees(-rollRad)
    }

    @Suppress("DEPRECATION")
    private fun tryFallback() {
        if (!hasAccel || !hasMag) return
        val rotation = FloatArray(ROTATION_MATRIX_SIZE)
        val inclination = FloatArray(ROTATION_MATRIX_SIZE)
        if (!SensorManager.getRotationMatrix(rotation, inclination, accel, mag)) return
        lastWorldMatrix = rotation.clone()
        val displayRot = windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        val remapped = FloatArray(ROTATION_MATRIX_SIZE)
        remapForDisplay(rotation, displayRot, remapped)
        val orientationAngles = FloatArray(SENSOR_VECTOR_SIZE)
        SensorManager.getOrientation(remapped, orientationAngles)
        val azMag = deviceTopHeadingDeg(rotation)
        val pitch = Math.toDegrees(orientationAngles[1].toDouble())
        val roll = Math.toDegrees(orientationAngles[2].toDouble())
        val (pointAz, pointAlt) = pointingFromDeviceToWorld(rotation)
        val viewRot = viewRotationFromDeviceToWorld(rotation)
        if (first) {
            smoothedAz = azMag
            smoothedPitch = pitch
            smoothedRoll = roll
            smoothedPointingAzMag = pointAz
            smoothedPointingAlt = pointAlt
            smoothedViewRot = if (viewRot.isNaN()) 0.0 else viewRot
            first = false
        } else {
            smoothAngles(azMag, pitch, roll, pointAz, pointAlt, viewRot)
        }
        emitOrientation()
    }

    private fun remapForDisplay(source: FloatArray, displayRot: Int, out: FloatArray) {
        when (displayRot) {
            Surface.ROTATION_0 -> remapAxes(source, SensorManager.AXIS_X, SensorManager.AXIS_Z, out)
            Surface.ROTATION_90 -> remapAxes(
                source,
                SensorManager.AXIS_Z,
                SensorManager.AXIS_MINUS_X,
                out
            )
            Surface.ROTATION_180 -> remapAxes(
                source,
                SensorManager.AXIS_MINUS_X,
                SensorManager.AXIS_MINUS_Z,
                out
            )
            Surface.ROTATION_270 -> remapAxes(
                source,
                SensorManager.AXIS_MINUS_Z,
                SensorManager.AXIS_X,
                out
            )
            else -> remapAxes(source, SensorManager.AXIS_X, SensorManager.AXIS_Z, out)
        }
    }

    private fun remapAxes(source: FloatArray, x: Int, y: Int, out: FloatArray) {
        SensorManager.remapCoordinateSystem(source, x, y, out)
    }

    private fun angleDelta(from: Double, to: Double): Double {
        return ((to - from + ANGLE_WRAP_OFFSET_DEG) % FULL_CIRCLE_DEG) - HALF_CIRCLE_DEG
    }

    private fun wrapPositive(deg: Double): Double {
        return ((deg % FULL_CIRCLE_DEG) + FULL_CIRCLE_DEG) % FULL_CIRCLE_DEG
    }

    /** Sensor-vector sizes and angle constants for the fusion math. */
    companion object {
        private const val ROTATION_MATRIX_SIZE = 9
        private const val SENSOR_VECTOR_SIZE = 3
        private const val MATRIX_UP_Z = 8
        private const val FULL_CIRCLE_DEG = 360.0
        private const val HALF_CIRCLE_DEG = 180.0
        private const val ANGLE_WRAP_OFFSET_DEG = 540.0
        private const val ROLL_UNDEFINED_EPSILON = 1e-6
    }
}
