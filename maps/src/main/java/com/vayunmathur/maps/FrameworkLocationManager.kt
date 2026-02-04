package com.vayunmathur.maps

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import org.maplibre.spatialk.geojson.Position

class FrameworkLocationManager(private val context: Context) : SensorEventListener {
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // Sensor storage
    private var accelerometerReading = FloatArray(3)
    private var magnetometerReading = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    // Callback to pass both Position and Heading (Compass)
    private var onUpdate: ((Position, Float) -> Unit)? = null
    private var lastLocation: Location? = null
    private var currentHeading: Float = 0f

    @SuppressLint("MissingPermission")
    fun startUpdates(onUpdateReceived: (Position, Float) -> Unit): LocationListener {
        this.onUpdate = onUpdateReceived

        // 1. Setup GPS Updates
        val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                lastLocation = location
                // If location has a GPS bearing, we prioritize it while moving
                val heading = if (location.hasBearing()) location.bearing else currentHeading
                onUpdate?.invoke(Position(location.longitude, location.latitude), heading)
            }
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }

        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener)
            println("GPS")
        }
        if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, locationListener)
            println("NET")
        }

        // 2. Setup Sensor Updates (Compass)
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also { acc ->
            sensorManager.registerListener(this, acc, SensorManager.SENSOR_DELAY_UI)
            println("ACCEL")
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.also { mag ->
            sensorManager.registerListener(this, mag, SensorManager.SENSOR_DELAY_UI)
            println("MAG")
        }

        return locationListener
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            accelerometerReading = event.values
        } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            magnetometerReading = event.values
        }

        // Calculate Orientation
        if (SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerReading, magnetometerReading)) {
            SensorManager.getOrientation(rotationMatrix, orientationAngles)

            // Convert radians to degrees and normalize to 0-360
            val azimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
            val newHeading = (azimuth + 360) % 360

            // Simple Low-Pass Filter to smooth jitter
            currentHeading = newHeading

            // Update UI if we have a location but the user is standing still
            lastLocation?.let {
                if (!it.hasBearing()) {
                    onUpdate?.invoke(Position(it.longitude, it.latitude), currentHeading)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun stopUpdates(listener: LocationListener) {
        locationManager.removeUpdates(listener)
        sensorManager.unregisterListener(this)
    }
}