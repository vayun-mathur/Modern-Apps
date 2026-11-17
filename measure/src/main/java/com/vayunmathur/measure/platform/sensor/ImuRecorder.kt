package com.vayunmathur.measure.platform.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Collects gyroscope and accelerometer samples for the VIO engine.
 *
 * Prefers the **uncalibrated** variants: the engine estimates and removes bias itself,
 * and the calibrated sensors apply their own time-varying correction whose changes look
 * like real motion to a dead-reckoning integrator.
 *
 * [SAMPLING_PERIOD_US] requests 400 Hz (the hardware maximum on the ICM45631 in recent
 * Pixels) as an explicit period rather than `SENSOR_DELAY_FASTEST` (0 µs): the explicit
 * form is the documented contract for high-rate sampling and, together with the
 * `HIGH_SAMPLING_RATE_SENSORS` manifest permission, avoids the `SecurityException`
 * the platform throws for a 0 µs request without that permission. Registration is
 * still treated as fallible — a device or policy that refuses it leaves the recorder
 * stopped ([isActive] false) and the caller degrades to Limited tracking instead of
 * crashing. Samples are drained in batches rather than pushed individually because
 * per-sample JNI overhead at that rate would exceed the integration work itself.
 */
class ImuRecorder(context: Context) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val lock = Any()

    /** Interleaved `[tNs, gx, gy, gz, ax, ay, az]`, matching the JNI packing. */
    private val pending = ArrayList<Double>(VALUES_PER_SAMPLE * 512)

    private var lastGyro = FloatArray(3)
    private var haveGyro = false

    @Volatile
    private var running = false

    /** True while both sensors are registered; read cross-thread by the frame pump. */
    val isActive: Boolean get() = running

    private var sampleCount = 0L
    private var firstSampleNs = 0L
    private var lastSampleNs = 0L

    /**
     * Register both sensors at [SAMPLING_PERIOD_US]. Returns false (and leaves the
     * recorder stopped) when a sensor is missing or registration is refused —
     * including the `SecurityException` for high-rate sampling without permission —
     * so the caller can degrade to Limited tracking rather than crash.
     */
    fun start(): Boolean {
        if (running) return true
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (gyro == null || accel == null) return false

        val registered = try {
            sensorManager.registerListener(this, gyro, SAMPLING_PERIOD_US) &&
                sensorManager.registerListener(this, accel, SAMPLING_PERIOD_US)
        } catch (_: SecurityException) {
            false
        }
        if (!registered) {
            // Drop a half-registered pair: one sensor alone cannot be fused.
            runCatching { sensorManager.unregisterListener(this) }
            return false
        }
        running = true
        return true
    }

    fun stop() {
        if (!running) return
        sensorManager.unregisterListener(this)
        running = false
        synchronized(lock) { pending.clear() }
        haveGyro = false
    }

    /** Effective sample rate since the first sample, for the diagnostics screen. */
    fun rateHz(): Double {
        val span = (lastSampleNs - firstSampleNs) * NS_TO_SECONDS
        return if (span > MIN_SPAN_SECONDS) sampleCount / span else 0.0
    }

    fun lastTimestampNs(): Long = lastSampleNs

    /** Take everything queued so far, leaving the buffer empty. */
    fun drain(): DoubleArray = synchronized(lock) {
        if (pending.isEmpty()) return DoubleArray(0)
        val out = DoubleArray(pending.size)
        for (i in pending.indices) out[i] = pending[i]
        pending.clear()
        out
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED, Sensor.TYPE_GYROSCOPE -> {
                lastGyro[0] = event.values[0]
                lastGyro[1] = event.values[1]
                lastGyro[2] = event.values[2]
                haveGyro = true
            }

            Sensor.TYPE_ACCELEROMETER_UNCALIBRATED, Sensor.TYPE_ACCELEROMETER -> {
                // The accelerometer drives sample emission, pairing each reading with
                // the most recent gyro value. The two sensors are not synchronised, and
                // interpolating between gyro samples at 400 Hz would buy accuracy far
                // below the noise floor.
                if (!haveGyro) return
                synchronized(lock) {
                    if (pending.size >= MAX_PENDING_VALUES) {
                        // Camera stalled or screen off: drop the oldest half rather
                        // than grow without bound.
                        pending.subList(0, pending.size / 2).clear()
                    }
                    pending.add(event.timestamp.toDouble())
                    pending.add(lastGyro[0].toDouble())
                    pending.add(lastGyro[1].toDouble())
                    pending.add(lastGyro[2].toDouble())
                    pending.add(event.values[0].toDouble())
                    pending.add(event.values[1].toDouble())
                    pending.add(event.values[2].toDouble())
                }
                if (sampleCount == 0L) firstSampleNs = event.timestamp
                lastSampleNs = event.timestamp
                sampleCount++
            }
        }
    }

    private companion object {
        /** 400 Hz (2 500 µs): the explicit high-rate period; never 0 (`FASTEST`). */
        const val SAMPLING_PERIOD_US = 2_500
        const val VALUES_PER_SAMPLE = 7
        /** ~20 s of headroom at 400 Hz. */
        const val MAX_PENDING_VALUES = VALUES_PER_SAMPLE * 8000
        /** Nanoseconds to seconds. */
        const val NS_TO_SECONDS = 1e-9
        /** Minimum span before the rate estimate is meaningful. */
        const val MIN_SPAN_SECONDS = 0.1
    }
}
