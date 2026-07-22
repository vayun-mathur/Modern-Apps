package com.vayunmathur.astronomy.domain.sensor

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import com.vayunmathur.astronomy.domain.engine.AltAz
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.*

data class DeviceOrientation(
    val azimuthTrueDeg: Double, // true-north yaw of device top
    val azimuthMagDeg: Double,
    val pitchDeg: Double,
    val rollDeg: Double,
    val accuracy: Int = SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
    val declinationDeg: Double = 0.0,
    // Full 3-axis device pointing: screen normal +Z in world ENU
    // Flat screen-up => +Z Up => alt +90 zenith, vertical => horizon 0°, flat down => nadir -90°
    // Az uses +Z projection onto horizontal plane (stable vs charging-port up/down)
    // Roll fixes backwards: viewRotation = roll (inverted) so content tracks phone
    val pointingAzTrueDeg: Double = azimuthTrueDeg,
    val pointingAltDeg: Double = 0.0,
    val viewRotationDeg: Double = 0.0
) {
    val pointingAzRad get() = Math.toRadians(pointingAzTrueDeg)
    val pointingAltRad get() = Math.toRadians(pointingAltDeg)
    val viewRotationRad get() = Math.toRadians(viewRotationDeg)
    val pointingAltAz get() = AltAz(pointingAzRad, pointingAltRad)
}

class OrientationManager(private val context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager?
    private val _orientation = MutableStateFlow<DeviceOrientation?>(null)
    val orientation: StateFlow<DeviceOrientation?> = _orientation
    private var declinationDeg = 0.0
    private var accel = FloatArray(3)
    private var mag = FloatArray(3)
    private var hasAccel = false
    private var hasMag = false
    private var smoothedAz = 0.0
    private var smoothedPitch = 0.0
    private var smoothedRoll = 0.0
    private var smoothedPointingAzMag = 0.0
    private var smoothedPointingAlt = 0.0
    private var first = true
    private val alpha = 0.15f
    private val alphaPointing = 0.15f
    private var accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
    private var running = false
    private var useRotationVector = true
    private var lastWorldMatrix = FloatArray(9)

    fun updateLocation(lat: Double, lon: Double, alt: Float = 0f) {
        declinationDeg = try {
            GeomagneticField(lat.toFloat(), lon.toFloat(), alt, System.currentTimeMillis()).declination.toDouble()
        } catch (_: Exception) { 0.0 }
    }

    fun start() {
        if (running) return
        running = true; first = true
        val rotVec = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotVec != null) {
            sensorManager.registerListener(this, rotVec, SensorManager.SENSOR_DELAY_GAME)
            useRotationVector = true
        } else {
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            useRotationVector = false
        }
    }

    fun stop() {
        if (!running) return
        running = false
        sensorManager.unregisterListener(this)
        hasAccel = false; hasMag = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> handleRotationVector(event.values)
            Sensor.TYPE_ACCELEROMETER -> { accel = event.values.clone(); hasAccel = true; if (!useRotationVector) tryFallback() }
            Sensor.TYPE_MAGNETIC_FIELD -> { mag = event.values.clone(); hasMag = true; if (!useRotationVector) tryFallback() }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, acc: Int) { accuracy = acc }

    @Suppress("DEPRECATION")
    private fun handleRotationVector(values: FloatArray) {
        val RdeviceToWorld = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(RdeviceToWorld, values)
        lastWorldMatrix = RdeviceToWorld.clone()

        val displayRot = windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        val remapped = FloatArray(9)
        when (displayRot) {
            Surface.ROTATION_0 -> SensorManager.remapCoordinateSystem(RdeviceToWorld, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(RdeviceToWorld, SensorManager.AXIS_Z, SensorManager.AXIS_MINUS_X, remapped)
            Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(RdeviceToWorld, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Z, remapped)
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(RdeviceToWorld, SensorManager.AXIS_MINUS_Z, SensorManager.AXIS_X, remapped)
            else -> SensorManager.remapCoordinateSystem(RdeviceToWorld, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
        }
        val o = FloatArray(3); SensorManager.getOrientation(remapped, o)
        val azMag = (Math.toDegrees(o[0].toDouble()) + 360) % 360
        val pitch = Math.toDegrees(o[1].toDouble()); val roll = Math.toDegrees(o[2].toDouble())

        val (pointAzMag, pointAlt) = pointingFromDeviceToWorld(RdeviceToWorld)

        if (first) {
            smoothedAz = azMag; smoothedPitch = pitch; smoothedRoll = roll
            smoothedPointingAzMag = pointAzMag; smoothedPointingAlt = pointAlt
            first = false
        } else {
            var d = azMag - smoothedAz; d = ((d + 540) % 360) - 180
            smoothedAz = (smoothedAz + d * alpha + 360) % 360
            smoothedPitch += (pitch - smoothedPitch) * alpha
            smoothedRoll += (roll - smoothedRoll) * alpha
            var dp = pointAzMag - smoothedPointingAzMag; dp = ((dp + 540) % 360) - 180
            smoothedPointingAzMag = (smoothedPointingAzMag + dp * alphaPointing + 360) % 360
            smoothedPointingAlt = smoothedPointingAlt + (pointAlt - smoothedPointingAlt) * alphaPointing
        }
        val trueAz = (smoothedAz + declinationDeg + 360) % 360
        val pointTrueAz = (smoothedPointingAzMag + declinationDeg + 360) % 360
        // Inversion fix: roll and altitude were backwards (move up shows down)
        // Alt: screen normal +Z gives correct anchor (flat 90, vertical 0). Keep true for numbers.
        // Roll: invert so sky rotates with device, not against.
        _orientation.value = DeviceOrientation(
            azimuthTrueDeg = trueAz,
            azimuthMagDeg = smoothedAz,
            pitchDeg = smoothedPitch,
            rollDeg = smoothedRoll,
            accuracy = accuracy,
            declinationDeg = declinationDeg,
            pointingAzTrueDeg = pointTrueAz,
            pointingAltDeg = smoothedPointingAlt,
            viewRotationDeg = -smoothedRoll
        )
    }

    // Screen-normal +Z pointing: flat up = +90 zenith, vertical = 0 horizon, flat down = -90 nadir
    // Stable vs charging-port up/down (uses column2, not Y)
    private fun pointingFromDeviceToWorld(R: FloatArray): Pair<Double, Double> {
        val east = R[2].toDouble()
        val north = R[5].toDouble()
        val up = R[8].toDouble()
        var az = Math.toDegrees(atan2(east, north)); az = (az + 360) % 360
        val alt = Math.toDegrees(asin(up.coerceIn(-1.0, 1.0)))
        return az to alt
    }

    @Suppress("DEPRECATION")
    private fun tryFallback() {
        if (!hasAccel || !hasMag) return
        val R = FloatArray(9); val I = FloatArray(9)
        if (!SensorManager.getRotationMatrix(R, I, accel, mag)) return
        lastWorldMatrix = R.clone()
        val displayRot = windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        val remapped = FloatArray(9)
        when (displayRot) {
            Surface.ROTATION_0 -> SensorManager.remapCoordinateSystem(R, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(R, SensorManager.AXIS_Z, SensorManager.AXIS_MINUS_X, remapped)
            Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(R, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Z, remapped)
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(R, SensorManager.AXIS_MINUS_Z, SensorManager.AXIS_X, remapped)
            else -> SensorManager.remapCoordinateSystem(R, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
        }
        val o = FloatArray(3); SensorManager.getOrientation(remapped, o)
        val azMag = (Math.toDegrees(o[0].toDouble()) + 360) % 360
        val pitch = Math.toDegrees(o[1].toDouble()); val roll = Math.toDegrees(o[2].toDouble())
        val (pAz, pAlt) = pointingFromDeviceToWorld(R)
        if (first) {
            smoothedAz = azMag; smoothedPitch = pitch; smoothedRoll = roll
            smoothedPointingAzMag = pAz; smoothedPointingAlt = pAlt
            first = false
        } else {
            var d = azMag - smoothedAz; d = ((d + 540) % 360) - 180
            smoothedAz = (smoothedAz + d * alpha + 360) % 360
            smoothedPitch += (pitch - smoothedPitch) * alpha
            smoothedRoll += (roll - smoothedRoll) * alpha
            var dp = pAz - smoothedPointingAzMag; dp = ((dp + 540) % 360) - 180
            smoothedPointingAzMag = (smoothedPointingAzMag + dp * alphaPointing + 360) % 360
            smoothedPointingAlt = smoothedPointingAlt + (pAlt - smoothedPointingAlt) * alphaPointing
        }
        val trueAz = (smoothedAz + declinationDeg + 360) % 360
        val pTrueAz = (smoothedPointingAzMag + declinationDeg + 360) % 360
        _orientation.value = DeviceOrientation(
            azimuthTrueDeg = trueAz,
            azimuthMagDeg = smoothedAz,
            pitchDeg = smoothedPitch,
            rollDeg = smoothedRoll,
            accuracy = accuracy,
            declinationDeg = declinationDeg,
            pointingAzTrueDeg = pTrueAz,
            pointingAltDeg = smoothedPointingAlt,
            viewRotationDeg = -smoothedRoll
        )
    }
}
