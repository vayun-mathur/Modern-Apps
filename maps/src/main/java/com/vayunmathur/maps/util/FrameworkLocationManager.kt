package com.vayunmathur.maps.util
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
import com.vayunmathur.library.map.GeoPoint

class FrameworkLocationManager(context: Context) : SensorEventListener {
    companion object {
        /** Fastest fix cadence asked of either provider. */
        private const val LOCATION_MIN_TIME_MS = 1000L
        /** No distance floor: the fix-acceptance policy filters, not the OS. */
        private const val LOCATION_MIN_DISTANCE_M = 0f
    }
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // Sensor storage
    private var accelerometerReading = FloatArray(3)
    private var magnetometerReading = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    // Callback to pass GeoPoint, heading (compass), and speed. The heading is null until a
    // real one has been seen: "unknown" and "due north" are different things, and the map
    // draws the puck's bearing cone only for the second. Speed is m/s, 0 when the fix
    // carries none — same convention as the navigation service.
    private var onUpdate: ((GeoPoint, Float?, Float) -> Unit)? = null
    // Callback for magnetometer accuracy so the UI can prompt for calibration.
    private var onAccuracy: ((Int) -> Unit)? = null
    private var lastLocation: Location? = null
    /** What [acceptsFix] compares against. Kept beside [lastLocation] so the two never disagree. */
    private var lastFix: Fix? = null
    /**
     * In-progress stationary hold for the jitter gate. Null when no hold is
     * active (moving, or no fix yet). Decoupled from [lastFix]: a held fix
     * still proves GPS is alive (so provider freshness advances), but the
     * puck does not move for it.
     */
    private var positionHold: PositionHold? = null
    private var currentHeading: Float? = null
    /**
     * Last compass heading actually emitted to the UI. The emit gate compares
     * against this rather than the previous sensor sample: smoothing moves a
     * fraction of the remaining gap per sample, so during a slow turn each
     * step is sub-degree while the accumulated movement is real.
     */
    private var lastEmittedHeading: Float? = null
    /** Listener we registered with the OS so [stop] can unregister it. */
    private var registeredLocationListener: LocationListener? = null

    @SuppressLint("MissingPermission")
    fun startUpdates(
        onUpdateReceived: (GeoPoint, Float?, Float) -> Unit,
        onAccuracyReceived: (Int) -> Unit = {},
    ): LocationListener {
        this.onUpdate = onUpdateReceived
        this.onAccuracy = onAccuracyReceived

        // 1. Setup GPS Updates
        val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val incoming = Fix(
                    fromGps = location.provider == LocationManager.GPS_PROVIDER,
                    accuracyM = if (location.hasAccuracy()) location.accuracy else 0f,
                    elapsedRealtimeNanos = location.elapsedRealtimeNanos,
                )
                if (!acceptsFix(lastFix, incoming)) return
                lastFix = incoming
                lastLocation = location
                val point = GeoPoint(location.longitude, location.latitude)
                // Stationary-GPS jitter gate: fixes arriving inside their own
                // accuracy circle move the puck by noise, not by travel. Hold
                // the last emitted position until a fix clears the gate, so a
                // phone on a desk stops redrawing at 1 Hz. `lastFix` still
                // advances on held fixes, so provider freshness (the GPS-stale
                // escape hatch) keeps working while the puck stays put.
                val speedMps = if (location.hasSpeed()) location.speed else 0f
                val activeHold = positionHold
                if (activeHold != null) {
                    val sample = PositionSample(point, incoming.accuracyM, incoming.elapsedRealtimeNanos)
                    val kept = activeHold.coalesce(sample, speedMps)
                    if (kept != null) {
                        positionHold = kept
                        return
                    }
                }
                positionHold = PositionHold(
                    drawn = point,
                    average = point,
                    accuracyM = incoming.accuracyM,
                    sinceNanos = incoming.elapsedRealtimeNanos,
                )
                // If location has a GPS bearing, we prioritize it while moving
                val heading = if (location.hasBearing()) location.bearing else currentHeading
                // A GPS fix is authoritative: re-anchor the compass emit gate so
                // the next sensor sample compares against what the UI just drew
                // rather than a pre-fix heading.
                if (location.hasBearing()) lastEmittedHeading = location.bearing
                onUpdate?.invoke(point, heading, speedMps)
            }
            @Deprecated("Overrides deprecated LocationListener.onStatusChanged")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        registeredLocationListener = locationListener

        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                LOCATION_MIN_TIME_MS,
                LOCATION_MIN_DISTANCE_M,
                locationListener,
            )
        }
        if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                LOCATION_MIN_TIME_MS,
                LOCATION_MIN_DISTANCE_M,
                locationListener,
            )
        }

        // Emit the OS last-known fix immediately so the UI opens on the user's
        // current (or last known) location instead of waiting for the first
        // live fix. Any last-known beats no position at all, so no staleness
        // filter here — recency filtering is for the live stream only.
        lastKnownLocation()?.let { location ->
            lastFix = Fix(
                fromGps = location.provider == LocationManager.GPS_PROVIDER,
                accuracyM = if (location.hasAccuracy()) location.accuracy else 0f,
                elapsedRealtimeNanos = location.elapsedRealtimeNanos,
            )
            lastLocation = location
            val heading = if (location.hasBearing()) location.bearing else currentHeading
            val point = GeoPoint(location.longitude, location.latitude)
            // The cold-start seed always emits: there is no prior puck to hold.
            // It still opens a hold so the second fix gates against the first.
            positionHold = PositionHold(
                drawn = point,
                average = point,
                accuracyM = lastFix!!.accuracyM,
                sinceNanos = lastFix!!.elapsedRealtimeNanos,
            )
            val speedMps = if (location.hasSpeed()) location.speed else 0f
            onUpdate?.invoke(point, heading, speedMps)
        }

        // 2. Setup Sensor Updates (Compass)
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also { acc ->
            sensorManager.registerListener(this, acc, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.also { mag ->
            sensorManager.registerListener(this, mag, SensorManager.SENSOR_DELAY_UI)
        }

        return locationListener
    }

    /**
     * Tear down all OS-registered callbacks. MUST be called when the owning
     * scope (typically the ViewModel) is destroyed, or the GPS radio stays
     * powered and the accel/magnetometer listeners leak.
     */
    fun stop() {
        registeredLocationListener?.let { runCatching { locationManager.removeUpdates(it) } }
        registeredLocationListener = null
        runCatching { sensorManager.unregisterListener(this) }
        onUpdate = null
        onAccuracy = null
        // Otherwise a restart compares the first fix against one from the previous session and
        // can reject it: `elapsedRealtimeNanos` keeps counting while updates are unregistered,
        // so the old fix would look fresh enough to keep winning. The heading gate is reset
        // for the same reason: the first compass sample must always emit.
        lastFix = null
        lastLocation = null
        positionHold = null
        currentHeading = null
        lastEmittedHeading = null
    }

    /**
     * Best last-known position the OS has cached (GPS preferred, then network),
     * or null when neither provider has one. Called with location permission
     * already granted — the caller gates on it — so a SecurityException here
     * means the provider state changed mid-call and is treated as no fix.
     */
    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(): Location? {
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        )
        var best: Location? = null
        for (provider in providers) {
            val fix = usableLastKnown(provider)
            val current = best
            if (fix != null && (current == null || fix.elapsedRealtimeNanos > current.elapsedRealtimeNanos)) {
                best = fix
            }
        }
        return best
    }

    /**
     * The OS-cached fix for [provider], or null when the provider is off, has
     * no fix, or only has the (0,0) nothing-real sentinel. A SecurityException
     * (provider state changed mid-call) reads as no fix.
     */
    @SuppressLint("MissingPermission")
    private fun usableLastKnown(provider: String): Location? {
        if (!locationManager.isProviderEnabled(provider)) return null
        val fix = runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
            ?: return null
        // A (0,0) last-known is the OS having nothing real, not the Gulf of Guinea.
        if (fix.latitude == 0.0 && fix.longitude == 0.0) return null
        return fix
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            accelerometerReading = event.values.copyOf()
        } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            magnetometerReading = event.values.copyOf()
        }

        // Calculate Orientation
        if (SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerReading, magnetometerReading)) {
            SensorManager.getOrientation(rotationMatrix, orientationAngles)

            // Convert radians to degrees and normalize to 0-360
            val azimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
            val newHeading = (azimuth + 360) % 360

            // Low-pass filter, on the magnetometer path only: a GPS course is already
            // smooth and filtering it would lag the puck behind a turn. This used to be a
            // bare assignment under a comment claiming to smooth, which mattered less
            // while the puck was drawn in Compose a frame behind the map — that latency
            // was doing some incidental smoothing of its own, and drawing the puck in the
            // renderer takes it away.
            currentHeading = smoothHeading(currentHeading, newHeading)

            // Update UI if we have a location but the user is standing still.
            // Gated on movement since the last EMIT (not the last sample):
            // the sensor fires at ~60 ms whether or not the phone turned, and
            // an ungated emit pushes userPosition/userBearing through the page
            // (and wakes the renderer) at sensor rate even on a desk. Location
            // fixes bypass this — they always emit, subject to the jitter gate.
            // Emits the held position, not the raw last fix: a fix swallowed by
            // the jitter gate must not leak out through the compass path.
            lastLocation?.let {
                val moved = headingDelta(lastEmittedHeading, currentHeading ?: newHeading)
                if (!it.hasBearing() && moved >= HEADING_EMIT_DEGREES) {
                    lastEmittedHeading = currentHeading
                    val point = positionHold?.drawn ?: GeoPoint(it.longitude, it.latitude)
                    val speedMps = if (it.hasSpeed()) it.speed else 0f
                    onUpdate?.invoke(point, currentHeading, speedMps)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Only the magnetometer's accuracy reflects compass calibration state.
        if (sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) {
            onAccuracy?.invoke(accuracy)
        }
    }
}

/**
 * One low-pass step from [current] toward [target], both degrees clockwise from north.
 *
 * Interpolates along the **shortest arc**, so a compass crossing north moves 359° → 1° by
 * two degrees rather than spinning the puck's cone 358° the other way. A null [current] is
 * the first reading and is taken whole: easing in from an arbitrary starting angle would
 * sweep the cone across the screen on every cold start.
 */
internal fun smoothHeading(
    current: Float?,
    target: Float,
    alpha: Float = HEADING_SMOOTHING,
): Float {
    if (current == null) return target
    // +540 before the modulo so the operand is positive: Kotlin's Float `%` keeps the
    // sign of the dividend, which would leave this in -360..360 instead of -180..180.
    val delta = (target - current + HALF_CIRCLE_WRAP) % FULL_CIRCLE_DEG - HALF_CIRCLE_DEG
    return (current + delta * alpha + FULL_CIRCLE_DEG) % FULL_CIRCLE_DEG
}

/**
 * How much of each new compass reading to take. The magnetometer is registered at
 * `SENSOR_DELAY_UI` (~60 ms), so this is roughly a third of a second to settle — enough to
 * kill the jitter without the cone visibly trailing a deliberate turn.
 */
private const val HEADING_SMOOTHING = 0.15f

/**
 * Minimum smoothed-heading movement (degrees) that re-emits the compass update.
 * The sensor fires at ~60 ms even when the phone is sitting on a desk, and every
 * emission used to push `userPosition`/`userBearing` through the page and wake the
 * renderer — so a stationary phone recomposed at sensor rate for a heading that
 * had converged to a tenth of a degree. Below this delta the cone cannot move a
 * visible pixel, and the update is dropped.
 */
private const val HEADING_EMIT_DEGREES = 1.0f

/**
 * Shortest-arc distance between two headings, in degrees. Null [current] (the
 * first reading) counts as infinitely far: the initial fix must always emit.
 */
internal fun headingDelta(current: Float?, target: Float): Float {
    if (current == null) return Float.MAX_VALUE
    val delta = (target - current + HALF_CIRCLE_WRAP) % FULL_CIRCLE_DEG - HALF_CIRCLE_DEG
    return kotlin.math.abs(delta)
}

/** Full compass circle and its half, for the shortest-arc math. */
private const val FULL_CIRCLE_DEG = 360f
private const val HALF_CIRCLE_DEG = 180f
/** 3 × half circle: makes the modulo operand positive. */
private const val HALF_CIRCLE_WRAP = 540f

/**
 * The part of a fix that decides whether it is worth taking, independent of `android.location`
 * so the policy can be tested without a device.
 */
internal data class Fix(
    val fromGps: Boolean,
    /** Horizontal accuracy in metres, or `0` when the provider did not supply one. */
    val accuracyM: Float,
    /** Monotonic, unlike wall-clock time, so it survives a clock correction mid-drive. */
    val elapsedRealtimeNanos: Long,
)

/**
 * Whether [incoming] should replace [current] as the shown position.
 *
 * GPS and network are registered on the *same* listener, so without this every network fix
 * overwrote the GPS one that arrived a moment earlier. For a navigation app that is backwards:
 * network positions are typically hundreds of metres out against GPS's few, so the puck jumped
 * between the road and somewhere near the road, roughly once a second.
 *
 * The policy is therefore GPS-first rather than newest-wins, with two ways for a network fix to
 * still get through — otherwise a phone that loses sky view keeps showing the last rooftop it saw:
 *
 *  - **GPS has gone quiet** for [staleAfterNanos]. A stale fix is worse than a coarse one.
 *  - **Network is [ACCURACY_MARGIN]× more accurate**, which happens indoors where GPS degrades to
 *    a wide multipath estimate while wifi trilateration stays tight.
 *
 * Ages are measured between the two fixes rather than against "now", so this stays a pure
 * function of its arguments and a caller cannot get a different answer by asking later.
 */
internal fun acceptsFix(
    current: Fix?,
    incoming: Fix,
    staleAfterNanos: Long = GPS_STALE_NANOS,
): Boolean {
    if (current == null) return true
    if (incoming.fromGps) return true
    // Network replacing network: nothing to prefer, so take the fresher one.
    if (!current.fromGps) return true
    if (incoming.elapsedRealtimeNanos - current.elapsedRealtimeNanos >= staleAfterNanos) return true
    // `0` means the provider declined to say, which is not evidence of being better.
    if (incoming.accuracyM <= 0f || current.accuracyM <= 0f) return false
    return incoming.accuracyM * ACCURACY_MARGIN < current.accuracyM
}

/**
 * How long GPS may be silent before a network fix is taken instead.
 *
 * Both providers are requested at one second, so this is ten missed updates — long enough not to
 * flip providers on a single dropped fix under a bridge, short enough that walking into a building
 * updates the puck rather than stranding it at the door.
 */
private const val GPS_STALE_NANOS = 10_000_000_000L

/**
 * How much better a network fix must be to displace a fresh GPS one. Twice, not marginally: at
 * comparable accuracy GPS is the more trustworthy of the two, and swapping between providers of
 * similar quality would make the puck jitter for no gain.
 */
private const val ACCURACY_MARGIN = 2f

/**
 * One position the UI actually drew, carrying the ingredients of the jitter
 * gate ([PositionHold.coalesce]). Kept off the `android.location` type so the
 * policy tests without a device, mirroring [Fix].
 */
internal data class PositionSample(
    val point: GeoPoint,
    /** Horizontal accuracy in metres, or `0` when the provider did not supply one. */
    val accuracyM: Float,
    /** Monotonic, so a clock correction mid-stand does not reset the gate. */
    val elapsedRealtimeNanos: Long,
)

/**
 * An in-progress stationary hold: the puck stays on [drawn] while fixes
 * accumulate into [average]. Owned by `FrameworkLocationManager` (one per
 * session); [coalesce] itself is pure over its arguments so it tests without
 * a device.
 */
internal data class PositionHold(
    /** What the UI is currently drawing — never moves while held. */
    val drawn: GeoPoint,
    /** Running average of the fixes held since the last emit. */
    val average: GeoPoint,
    /** Tightest accuracy seen this stretch — only ever shrinks (see below). */
    val accuracyM: Float,
    /** When the hold started (monotonic nanos). */
    val sinceNanos: Long,
) {
    /**
     * Whether [incoming] should move the puck off [drawn].
     *
     * Returns the hold to keep (emitting nothing) or `null` to emit [incoming]
     * whole — no half-steps, which would read as drift. The rules, in order:
     *
     *  - **Moving.** At or above [MOVING_SPEED_MPS] the fix is travel, not
     *    noise — always emit. ([speedMps] is `0` when the provider did not
     *    supply one, which reads as stationary: with no speed there is no
     *    evidence either way, and the conservative answer is to hold.)
     *  - **Stationary timeout.** Past [STATIONARY_EMIT_NANOS] without moving,
     *    emit so the puck tracks slow filter convergence instead of freezing
     *    stale, and so a pathological hold (a provider that never reports
     *    speed while the user actually walks) degrades to minute-cadence
     *    stair-steps rather than a stuck puck. The caller starts a fresh hold
     *    on emit, so one re-emit does not reopen the gate for the next
     *    second's sample.
     *  - **Accuracy gate.** Below [MIN_ACCURACY_M] there is no usable circle
     *    to compare against — emit. `0`/negative accuracies land here, so a
     *    provider that declines to state accuracy can never pin the puck.
     *  - **Displacement.** Inside the gate radius of the running [average],
     *    accumulate and hold; outside it, emit. Compared against the average
     *    (not [drawn]) so zero-mean desk jitter converges instead of walking
     *    the puck around; the radius is `max(accuracy, [MIN_DISPLACEMENT_M])`
     *    so a 3 m-accuracy fix still needs real movement, capped at
     *    [MAX_GATE_RADIUS_M] so a 500 m network circle cannot freeze the puck
     *    for a whole indoor walk; shrink-only accuracy so a widening circle
     *    (fix degrading in a tunnel or urban canyon, not the user moving)
     *    never buys a bigger jitter budget.
     */
    fun coalesce(
        incoming: PositionSample,
        speedMps: Float = 0f,
    ): PositionHold? {
        if (speedMps >= MOVING_SPEED_MPS) return null
        if (incoming.elapsedRealtimeNanos - sinceNanos >= STATIONARY_EMIT_NANOS) return null
        if (accuracyM < MIN_ACCURACY_M || incoming.accuracyM < MIN_ACCURACY_M) return null
        val radius = minOf(maxOf(accuracyM, MIN_DISPLACEMENT_M), MAX_GATE_RADIUS_M)
        if (positionDistanceM(average, incoming.point) > radius) return null
        return copy(
            average = GeoPoint(
                longitude = (average.longitude + incoming.point.longitude) / 2.0,
                latitude = (average.latitude + incoming.point.latitude) / 2.0,
            ),
            accuracyM = minOf(accuracyM, incoming.accuracyM),
        )
    }
}

/** Equirectangular distance in metres — fine at the single-digit-metre gate scale. */
private fun positionDistanceM(a: GeoPoint, b: GeoPoint): Double {
    val dx = (a.longitude - b.longitude) * 111_320.0 *
        kotlin.math.cos(Math.toRadians((a.latitude + b.latitude) / 2.0))
    val dy = (a.latitude - b.latitude) * 110_540.0
    return kotlin.math.sqrt(dx * dx + dy * dy)
}

/** Below this accuracy there is no usable circle — the fix always moves the puck. */
private const val MIN_ACCURACY_M = 1f

/**
 * Minimum displacement (metres) that moves the puck even when the accuracy
 * circle is tighter. A 3 m-accuracy phone on a desk still wanders a metre or
 * two per fix; without a floor the puck follows it.
 */
private const val MIN_DISPLACEMENT_M = 5f

/**
 * Widest the jitter gate may open. A coarse network circle (hundreds of
 * metres) would otherwise pin the puck for a whole indoor walk; past this the
 * user is genuinely moving relative to the fix quality, so emit.
 */
private const val MAX_GATE_RADIUS_M = 50f

/**
 * Speed (m/s) at or above which a fix is travel, not noise. Walking pace is
 * ~1.4 m/s; half of that so a slow stroll still breaks the hold promptly.
 */
private const val MOVING_SPEED_MPS = 0.7f

/**
 * Longest a stationary hold may last before re-emitting. The puck must track
 * slow filter convergence rather than freeze; one minute is far beyond real
 * desk-jitter timescales while bounding the staleness when speed is
 * unavailable and the gate is the only thing moving the puck.
 */
private const val STATIONARY_EMIT_NANOS = 60_000_000_000L
