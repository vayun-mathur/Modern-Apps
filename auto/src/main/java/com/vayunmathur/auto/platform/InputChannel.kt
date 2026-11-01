package com.vayunmathur.auto.platform

import com.vayunmathur.library.log.Log
import com.vayunmathur.auto.protocol.GalConnection
import com.vayunmathur.auto.protocol.GalMessage
import com.vayunmathur.auto.protocol.InputCodec
import com.vayunmathur.auto.protocol.InputEvents
import com.vayunmathur.auto.protocol.InputKey
import com.vayunmathur.auto.protocol.InputPointer
import com.vayunmathur.auto.protocol.gal.Service

/**
 * A touch frame scaled to car-display pixels, ready to inject.
 *
 * [action] already IS the `MotionEvent` action: the head unit numbers touch
 * actions exactly as `MotionEvent` does (DOWN 0, UP 1, MOVE 2, POINTER_DOWN 5,
 * POINTER_UP 6), so it passes through verbatim and only the POINTER_DOWN/UP
 * variants need the index shifted in at injection time.
 */
data class ScaledTouch(
    val action: Int,
    val pointers: List<InputPointer>,
    val actionIndex: Int,
)

/**
 * The input source channel: turns head-unit touch, keys and scroll into car
 * UI events once the head unit grants the channel.
 *
 * Bring-up needs nothing beyond the generic open: ch8 opens in the wire-order
 * pass like every other service, and the binding request goes out on the
 * grant -- sending on a channel the head unit has not opened earns a bare
 * MessageError (0xff). Injection itself gates on the arbitrated input flag
 * (projected video WITH input focus): a `PROJECTED_NO_INPUT_FOCUS` head unit
 * still streams video but its input must be dropped, as must everything while
 * the car shows its own UI.
 *
 * Route by channel id: 0x8001 is aliased across services (media start, sensor
 * request, messaging threads), so only ch8 traffic belongs to [InputCodec].
 * The 0x04 CONTROL frame flag does NOT mean "control channel" (HANDOFF.md
 * section 9) and never enters this decision.
 */
class InputChannel(
    private val service: Service,
    private val connection: GalConnection,
    /** Arbitrated input flag; the service reads it off the session's focus state. */
    private val isInputAllowed: () -> Boolean = { false },
    /** Current car-display size in pixels, or null before video streaming starts. */
    private val displaySize: () -> Pair<Int, Int>? = { null },
    /** Fire-and-forget observations for the phone status screen; never gates. */
    private val onEvent: (InputEvent) -> Unit = {},
    /** Injects one scaled touch frame; returns whether it was consumed. */
    private val touchSink: (ScaledTouch) -> Boolean = { false },
    /** Injects one key press or release; returns whether it was consumed. */
    private val keySink: (InputKey) -> Boolean = { false },
    /** Injects one scroll tick; returns whether it was consumed. */
    private val scrollSink: (delta: Int) -> Boolean = { false },
) {
    val channelId: Int get() = service.id

    /** Head-unit touchscreen size, or the DHU default when undiscovered. */
    private val huSize: Pair<Int, Int> =
        service.inputSource.touchscreenList.firstOrNull()?.let { it.width to it.height }
            ?: (InputCodec.DEFAULT_HU_WIDTH to InputCodec.DEFAULT_HU_HEIGHT)

    /**
     * Whether the binding echo has gone out. Set in [requestBinding], read in
     * [onMessage] to log (not crash on) traffic that arrives with no owner
     * bound yet -- e.g. a head unit that sends before the grant path runs.
     */
    @Volatile
    private var bound = false

    /** Input focus was lost after binding; the next regain re-binds. */
    private var parked = false

    /**
     * The grant arrived: echo the discovery-advertised keycodes back so the
     * head unit knows which keys we handle. Called once per channel open;
     * [onInputFocusChanged] re-sends it after a park.
     */
    fun requestBinding() {
        val keycodes = service.inputSource.keycodesSupportedList
        val (type, payload) = InputCodec.encodeKeyBinding(keycodes)
        connection.send(channelId, type, payload)
        bound = true
        Log.status(TAG, "input channel open; bound ${keycodes.size} keycodes")
        onEvent(InputEvent.BindingRequested(keycodes.size))
    }

    /**
     * Re-binds only when input returns after NO_INPUT_FOCUS: a head unit that parked
     * input may need the echo to resume reports. Audio/nav focus changes leave
     * [allowed] unchanged and must not re-send.
     */
    fun onInputFocusChanged(allowed: Boolean) {
        if (!bound) return
        if (!allowed) {
            parked = true
        } else if (parked) {
            parked = false
            requestBinding()
        }
    }

    /**
     * Phone -> HU: one input-stream feedback event (`xjs`, 0x8004) from the
     * discovery-advertised set. Sent after the phone consumes an input report
     * section (see the inject branches in [onMessage]): `ikb.n` sends one per
     * `xji` event, so each successful inject echoes the whole advertised set.
     * Never sent before the binding echo -- the head unit has not opened the
     * dialogue yet -- and never on a failed inject (nothing was consumed).
     */
    fun sendFeedback(feedbackEvent: Int) {
        if (!bound) return
        val (type, payload) = InputCodec.encodeFeedback(feedbackEvent)
        connection.send(channelId, type, payload)
        Log.dev(TAG, "input feedback sent: $feedbackEvent")
        onEvent(InputEvent.FeedbackSent(feedbackEvent))
    }

    /**
     * Echoes every discovery-advertised feedback event once, after one
     * successfully injected report section. Empty when the head unit
     * advertised no feedback set -- then feedback is a no-op and injection
     * behaves exactly as before.
     */
    private fun sendInjectFeedback() {
        val advertised = service.inputSource.feedbackEventsSupportedList
        if (advertised.isEmpty()) return
        for (event in advertised) sendFeedback(event)
    }

    /** One message for this channel; anything else is ignored, never misparsed. */
    fun onMessage(channelId: Int, type: Int, payload: ByteArray) {
        if (channelId != this.channelId) {
            Log.status(TAG, "ignoring 0x${type.toString(HEX_RADIX)} for channel $channelId")
            return
        }
        if (!bound) {
            // Traffic before the grant's binding echo: the head unit is
            // talking on a channel we never bound (or the grant path never
            // ran). Log, don't crash -- the binding goes out on the grant and
            // on the focus flap back to input-allowed.
            Log.status(TAG, "ch8 traffic before binding (0x${type.toString(HEX_RADIX)}); dropping")
            onEvent(InputEvent.DroppedNoFocus)
            return
        }
        if (type == GalMessage.Input.KEY_BINDING_RESPONSE) {
            onBindingResponse(payload)
            return
        }
        val events = InputCodec.decodeInbound(type, payload)
        if (events == null) {
            Log.dev(TAG, "unhandled input message 0x${type.toString(HEX_RADIX)}")
            return
        }
        if (!isInputAllowed()) {
            Log.dev(TAG, "dropping ${events.describe()} without input focus")
            onEvent(InputEvent.DroppedNoFocus)
            return
        }
        val display = displaySize()
        if (display == null) {
            Log.dev(TAG, "dropping ${events.describe()} with no car display yet")
            onEvent(InputEvent.DroppedNoFocus)
            return
        }
        injectAll(events, display.first, display.second)
    }

    private fun onBindingResponse(payload: ByteArray) {
        val status = runCatching { InputCodec.decodeKeyBindingResponse(payload) }.getOrNull()
        Log.status(TAG, "key binding response: ${status?.status}")
        onEvent(InputEvent.BindingAnswered(status?.status ?: BINDING_UNKNOWN_STATUS))
    }

    private fun injectAll(
        events: InputEvents,
        displayWidth: Int,
        displayHeight: Int,
    ) {
        injectTouches(events, displayWidth, displayHeight)
        injectKeys(events)
        injectAbsolutes(events)
        injectScrolls(events)
    }

    private fun injectTouches(
        events: InputEvents,
        displayWidth: Int,
        displayHeight: Int,
    ) {
        events.touches.forEach { touch ->
            val scaled = ScaledTouch(
                action = touch.action.number,
                pointers = touch.pointers.map {
                    InputCodec.scalePointer(
                        it,
                        huWidth = huSize.first,
                        huHeight = huSize.second,
                        displayWidth = displayWidth,
                        displayHeight = displayHeight,
                    )
                },
                actionIndex = touch.actionIndex,
            )
            if (touchSink(scaled)) {
                onEvent(InputEvent.Touch(scaled.action, scaled.pointers.size))
                sendInjectFeedback()
            }
        }
    }

    private fun injectKeys(events: InputEvents) {
        events.keys.forEach { key ->
            // Volume keys are consumed, never injected: gearhead's car home
            // swallows KEYCODE_VOLUME_UP/DOWN (its dispatchKeyEvent returns
            // true for 24/25) because the head unit owns its speaker volume.
            // Injecting them would double-handle volume the HU already manages.
            if (key.keycode == VOLUME_UP || key.keycode == VOLUME_DOWN) {
                onEvent(InputEvent.VolumeKey(key.keycode, key.down))
            } else if (keySink(key)) {
                onEvent(InputEvent.Key(key.keycode, key.down))
                sendInjectFeedback()
            }
        }
    }

    private fun injectAbsolutes(events: InputEvents) {
        // Tap-as-select rides the absolute section: keycode 65541, value 1
        // presses DPAD_CENTER and anything else releases it (`jar.java`).
        events.absolutes.forEach { (keycode, value) ->
            if (keycode == InputCodec.TAP_SELECT_KEYCODE) {
                val key = InputKey(InputCodec.KEYCODE_DPAD_CENTER, value == TAP_PRESS_VALUE)
                if (keySink(key)) {
                    onEvent(InputEvent.Key(key.keycode, key.down))
                    sendInjectFeedback()
                }
            } else {
                Log.dev(TAG, "ignoring absolute event keycode=$keycode value=$value")
            }
        }
    }

    private fun injectScrolls(events: InputEvents) {
        events.scrolls.forEach { (_, delta) ->
            if (scrollSink(delta)) {
                onEvent(InputEvent.Scroll(delta))
                sendInjectFeedback()
            }
        }
    }

    private companion object {
        const val TAG = "MaAuto.Input"

        /** Radix for hex message-id logging. */
        const val HEX_RADIX = 16

        /** Consumed, never injected: the head unit owns its speaker volume. */
        const val VOLUME_UP = 24
        const val VOLUME_DOWN = 25

        /** Tap-as-select press value; anything else releases DPAD_CENTER. */
        const val TAP_PRESS_VALUE = 1

        /** Binding status when the head unit's answer fails to parse. */
        const val BINDING_UNKNOWN_STATUS = -1

        private fun InputEvents.describe(): String =
            "input report (${touches.size} touch, ${keys.size} keys, " +
                "${scrolls.size} scroll, ${absolutes.size} abs)"
    }
}
