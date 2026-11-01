package com.vayunmathur.auto.platform

import android.os.SystemClock
import com.vayunmathur.library.log.Log
import com.vayunmathur.auto.protocol.GalConnection
import com.vayunmathur.auto.protocol.GalService
import com.vayunmathur.auto.protocol.InboundSensor
import com.vayunmathur.auto.protocol.SensorCodec
import com.vayunmathur.auto.protocol.SensorRequestTracker
import com.vayunmathur.auto.protocol.SensorSnapshot
import com.vayunmathur.auto.protocol.gal.SensorEvent as GalSensorEvent
import com.vayunmathur.auto.protocol.gal.SensorType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How the channel answers "is it night": the head unit's NIGHT_MODE stream wins
 * when it has spoken, otherwise the phone's own night state decides.
 *
 * Maps seam: replace the lambda with the Maps/theme source and feed live
 * batches through [SensorChannel.postLiveEvents] -- the channel shape stays
 * the same, only the sources change.
 */
fun interface NightSource {
    /** The phone's own night state, consulted until the HU reports NIGHT_MODE. */
    fun phoneNight(): Boolean
}

/**
 * The sensor source channel: subscribes to the advertised sensor types on the ch7
 * grant and folds the head unit's batches into a last-known snapshot.
 *
 * The channel id comes from discovery, not from [GalService]: the DHU 2.0
 * discovery advertises the sensor source on service 1 (verified by decoding
 * the 0x6 payload: `Sensor{1} Sensor{10} Sensor{13}`, exactly the
 * `default.ini [sensors]` trio), while gearhead's `rro` numbers it 7.
 * Gearhead binds by payload (`jlf.a(xpa)` reads the sensor config out of
 * the service entry), and so do we -- [ProjectionService.openNext] passes
 * the id of whichever entry carries `sensor_source`.
 *
 * Bring-up needs nothing beyond the generic open: the sensor channel opens
 * in the wire-order pass like every other service, and subscribes go out on
 * the grant -- sending on a channel the head unit has not opened earns a
 * bare MessageError (0xff). One channel at a time is enforced by the pump's
 * sequential open order; subscriptions batch behind a single flush the same
 * way.
 *
 * Route by channel id: 0x8004 is SensorError here but MediaAck on media
 * channels and ACTION on ch14, so only sensor-channel traffic belongs to
 * [SensorCodec]. The 0x04 CONTROL frame flag does NOT mean "control channel"
 * (HANDOFF.md section 9) and never enters this decision.
 *
 * Full live values (maps-dev): feed phone GPS / fused speed through
 * [postLiveEvents] -- see [SensorSnapshot.withEvents] -- and read [snapshot]
 * for the car UI. The channel only subscribes; the snapshot is the value.
 */
class SensorChannel(
    private val connection: GalConnection,
    private val night: NightSource = NightSource { false },
    private val onEvent: (SensorEvent) -> Unit = {},
    /** Live-value seam: batches from phone sensors (maps-dev) fold in like HU batches. */
    private val onValues: (SensorSnapshot) -> Unit = {},
    /** Discovery-bound channel id (DHU 2.0: 1; gearhead rro: 7). */
    val channelId: Int = GalService.SENSOR_SOURCE.id,
    /**
     * Types the head unit advertised in discovery. Subscribing to anything else earns
     * STATUS_INVALID_SENSOR, and enum values newer than the HU's protocol fail its
     * required-field parse outright (DHU 2.0 rejects types 23-26).
     */
    private val subscriptions: List<SensorType> = SensorCodec.STUB_SUBSCRIPTIONS,
) {
    // (GalService import covers the default above.)

    private val _snapshot = MutableStateFlow(SensorSnapshot())
    /** Last-known sensor values; parked / speed 0 / night-unknown until batches move them. */
    val snapshot: StateFlow<SensorSnapshot> = _snapshot.asStateFlow()

    private var open = false

    /**
     * Tracks subscribes against the 2 s `rvb` timeout: each subscribe
     * marks, each 0x8002 answer clears, and unanswered subscribes surface as
     * synthesized errors on the next sweep (see [onMessage]).
     */
    private val requestTracker = SensorRequestTracker()

    /**
     * Subscribe send order: the 0x8002 answer (`xnv`/`xow`) carries no sensor
     * type, so answers attribute FIFO -- the head of this queue is what each
     * answer belongs to (the DHU answers in subscribe order, verified live).
     * Entries leave via answers here or via timeouts in [requestTracker];
     * both paths pop, so a type is never attributed twice.
     */
    private val pendingAnswers = ArrayDeque<SensorType>()

    /**
     * The grant arrived: subscribe to [subscriptions]. Called once per
     * channel open. Night follows the phone until the first NIGHT_MODE batch
     * -- the snapshot seeds from [night] now so the car UI has an answer
     * before the head unit streams anything.
     */
    fun onChannelOpen() {
        open = true
        val seeded = _snapshot.value.copy(isNight = night.phoneNight())
        _snapshot.value = seeded
        for (type in subscriptions) {
            val (requestType, payload) = SensorCodec.encodeSubscribe(type)
            connection.send(channelId, requestType, payload)
            requestTracker.markRequested(type, SystemClock.uptimeMillis())
            pendingAnswers.addLast(type)
            Log.status(TAG, "sensor subscribe: $type")
            onEvent(SensorEvent.Subscribed(type.name))
        }
    }

    /** One message for this channel; anything else is ignored, never misparsed. */
    fun onMessage(channelId: Int, type: Int, payload: ByteArray) {
        if (channelId != this.channelId) {
            Log.status(TAG, "ignoring 0x${type.toString(HEX_RADIX)} for channel $channelId")
            return
        }
        // Sweep subscribes the head unit never answered: each surfaces once
        // as a synthesized error (status TIMEOUT_STATUS), observed like a
        // head-unit error and never fatal to the bring-up. Timed-out entries
        // also leave the FIFO below, so a late answer never misattributes.
        for (timedOut in requestTracker.takeTimedOut(SystemClock.uptimeMillis())) {
            pendingAnswers.remove(timedOut.type)
            Log.status(TAG, "sensor subscribe timed out: ${timedOut.type}")
            onEvent(SensorEvent.SensorError(timedOut.type.name, timedOut.status))
        }
        when (val inbound = SensorCodec.decodeInbound(type, payload)) {
            is InboundSensor.Subscribed -> {
                // The answer carries no type (`xnv`/`xow` is status-only), so
                // it belongs to the oldest unanswered subscribe. An answer
                // with an empty queue is unsolicited: observed, never fatal.
                val answered = pendingAnswers.removeFirstOrNull()
                if (answered != null) {
                    requestTracker.markAnswered(answered)
                    Log.status(TAG, "sensor subscribed: $answered status=${inbound.status}")
                    onEvent(SensorEvent.SubscriptionAnswered(answered.name, inbound.status))
                } else {
                    Log.status(TAG, "unsolicited sensor answer status=${inbound.status}")
                    onEvent(SensorEvent.SubscriptionAnswered("UNSOLICITED", inbound.status))
                }
            }
            is InboundSensor.Readings -> {
                // A NIGHT_MODE event with a payload overrides the phone seed;
                // payload-less events keep last-known inside withEvents.
                val next = _snapshot.value.withEvents(inbound.events)
                _snapshot.value = next
                onValues(next)
                Log.dev(TAG, "sensor batch: ${inbound.events.size} events")
                onEvent(SensorEvent.BatchReceived(inbound.events.size))
            }
            is InboundSensor.Error -> {
                // Observed, never fatal: the bring-up and video carry on.
                Log.status(TAG, "sensor error: ${inbound.type} status=${inbound.status}")
                onEvent(SensorEvent.SensorError(inbound.type.name, inbound.status))
            }
            is InboundSensor.Observed -> Log.dev(TAG, "unhandled sensor message 0x${type.toString(HEX_RADIX)}")
        }
    }

    /**
     * Live-value seam (HANDOFF.md section 10, maps-dev): folds phone-side
     * batches -- `MapsGuidance.toSensorEvents(snapshot)` for the current fix
     * -- into the same last-known snapshot the head-unit batches fold into.
     * Posts before the grant are dropped, never queued: the grant-time phone
     * seed carries the state and the next live post overwrites it.
     */
    fun postLiveEvents(events: List<GalSensorEvent>) {
        if (!open) return
        val next = _snapshot.value.withEvents(events)
        _snapshot.value = next
        onValues(next)
        Log.dev(TAG, "sensor live batch: ${events.size} events")
        onEvent(SensorEvent.BatchReceived(events.size))
    }

    /** Unsubscribes [subscriptions]; the pump calls this on teardown before release. */
    fun release() {
        if (!open) return
        open = false
        // Best-effort: the socket may already be going away in teardown, and
        // an unsubscribe is courtesy, not protocol -- never fail the parting.
        for (type in subscriptions) {
            runCatching {
                val (requestType, payload) = SensorCodec.encodeUnsubscribe(type)
                connection.send(channelId, requestType, payload)
            }
        }
    }

    private companion object {
        const val TAG = "MaAuto.Sensors"

        /** Radix for hex message-id logging. */
        const val HEX_RADIX = 16
    }
}
