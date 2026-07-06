package com.vayunmathur.watch.watch.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.IBinder
import android.util.Log
import com.vayunmathur.watch.watch.R
import com.vayunmathur.watch.watch.ble.GattServerManager
import com.vayunmathur.watch.watch.data.MetricType
import com.vayunmathur.watch.watch.data.SensorDatabase
import com.vayunmathur.watch.watch.data.SensorRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that listens to the native heart-rate and step-counter
 * sensors, persists readings to Room, and hosts the BLE GATT server that serves
 * those readings to the paired phone.
 */
class SensorBackgroundService : Service(), SensorEventListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var sensorManager: SensorManager
    private lateinit var gattServer: GattServerManager

    private var lastHeartRateAt = 0L
    // The step counter reports a cumulative count since boot; we store deltas.
    private var lastStepCount = -1.0
    private var lastPressureAt = 0L
    // Current motion state, maintained via the one-shot trigger sensors.
    @Volatile private var isStationary = false

    override fun onCreate() {
        super.onCreate()
        val db = SensorDatabase.get(this)
        gattServer = GattServerManager(this, db.sensorDao(), scope)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        registerSensors()
        gattServer.start()
        return START_STICKY
    }

    private fun registerSensors() {
        sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        // Arm the stationary detector; the two trigger sensors re-arm each other.
        armStationaryDetect()
    }

    private fun armStationaryDetect() {
        sensorManager.getDefaultSensor(Sensor.TYPE_STATIONARY_DETECT)?.let {
            sensorManager.requestTriggerSensor(triggerListener, it)
        }
    }

    private fun armMotionDetect() {
        sensorManager.getDefaultSensor(Sensor.TYPE_MOTION_DETECT)?.let {
            sensorManager.requestTriggerSensor(triggerListener, it)
        }
    }

    private val triggerListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent) {
            val now = System.currentTimeMillis()
            when (event.sensor.type) {
                Sensor.TYPE_STATIONARY_DETECT -> {
                    isStationary = true
                    persist(SensorRecord(type = MetricType.Motion, timestamp = now, value = 1.0))
                    // Trigger sensors are one-shot; arm the opposite one.
                    armMotionDetect()
                }
                Sensor.TYPE_MOTION_DETECT -> {
                    isStationary = false
                    persist(SensorRecord(type = MetricType.Motion, timestamp = now, value = 0.0))
                    armStationaryDetect()
                }
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = System.currentTimeMillis()
        when (event.sensor.type) {
            Sensor.TYPE_HEART_RATE -> {
                val bpm = event.values.firstOrNull()?.toDouble() ?: return
                if (bpm <= 0.0) return
                // Sample roughly once per minute to conserve battery.
                if (now - lastHeartRateAt < HEART_RATE_INTERVAL_MS) return
                lastHeartRateAt = now
                persist(
                    SensorRecord(
                        type = MetricType.HeartRate,
                        timestamp = now,
                        value = bpm,
                        stationary = isStationary,
                    ),
                )
            }
            Sensor.TYPE_STEP_COUNTER -> {
                val cumulative = event.values.firstOrNull()?.toDouble() ?: return
                if (lastStepCount < 0.0) {
                    lastStepCount = cumulative
                    return
                }
                val delta = cumulative - lastStepCount
                lastStepCount = cumulative
                if (delta <= 0.0) return
                persist(
                    SensorRecord(
                        type = MetricType.Steps,
                        timestamp = now,
                        value = cumulative,
                        delta = delta,
                    ),
                )
            }
            Sensor.TYPE_PRESSURE -> {
                val hpa = event.values.firstOrNull()?.toDouble() ?: return
                if (hpa <= 0.0) return
                if (now - lastPressureAt < PRESSURE_INTERVAL_MS) return
                lastPressureAt = now
                persist(SensorRecord(type = MetricType.Pressure, timestamp = now, value = hpa))
            }
        }
    }

    private fun persist(record: SensorRecord) {
        val db = SensorDatabase.get(this)
        scope.launch(Dispatchers.IO) {
            try {
                db.sensorDao().insert(record)
            } catch (e: Exception) {
                Log.e(TAG, "insert failed", e)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        sensorManager.cancelTriggerSensor(
            triggerListener,
            sensorManager.getDefaultSensor(Sensor.TYPE_STATIONARY_DETECT),
        )
        sensorManager.cancelTriggerSensor(
            triggerListener,
            sensorManager.getDefaultSensor(Sensor.TYPE_MOTION_DETECT),
        )
        gattServer.stop()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "SensorBackgroundService"
        private const val CHANNEL_ID = "sensor_collection"
        private const val NOTIFICATION_ID = 1
        private const val HEART_RATE_INTERVAL_MS = 60_000L
        private const val PRESSURE_INTERVAL_MS = 30_000L

        fun start(context: Context) {
            val intent = Intent(context, SensorBackgroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SensorBackgroundService::class.java))
        }
    }
}
