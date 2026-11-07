package com.vayunmathur.games.kart.platform

import android.content.Context
import android.hardware.input.InputManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.vayunmathur.library.log.Log
import com.vayunmathur.sdk.controller.AXIS_BRAKE
import com.vayunmathur.sdk.controller.AXIS_COUNT
import com.vayunmathur.sdk.controller.AXIS_LEFT_X
import com.vayunmathur.sdk.controller.AXIS_LEFT_Y
import com.vayunmathur.sdk.controller.AXIS_RIGHT_X
import com.vayunmathur.sdk.controller.AXIS_RIGHT_Y
import com.vayunmathur.sdk.controller.AXIS_THROTTLE
import com.vayunmathur.sdk.controller.ControllerButton
import com.vayunmathur.sdk.controller.ControllerInput

private const val TAG = "GamepadDirect"

/** Axis deflection past this counts as a hat direction, for pads exposing the D-pad as axes. */
private const val HAT_THRESHOLD = 0.5f

/**
 * Physical gamepads plugged into the TV: enumeration, buttons, sticks, hot-plug.
 *
 * Each pad takes a seat through the same [ControllerHost] local-player path the phones use, so
 * downstream everything is indistinguishable: the race loop reads `host.inputs` and `host.lobby`
 * and never learns which seats are network and which are USB/Bluetooth. A pad plugged in
 * mid-race joins the lobby at once and drives from the next race; the running snapshot is fixed
 * at countdown end, so it never reshuffles under a live tick.
 *
 * Kept separate from the media-key dispatch path on purpose: this only ever consumes events from
 * devices it has seated, and only gamepad buttons/axes at that. Volume, navigation and media
 * keys fall through to the system untouched.
 */
class GamepadDirect(
    context: Context,
    private val onSeat: (playerId: String, name: String, present: Boolean) -> Unit,
    private val onInput: (ControllerInput) -> Unit,
) {

    private val inputManager: InputManager? =
        context.applicationContext.getSystemService(InputManager::class.java)

    private val lock = Any()
    /** deviceId → seat, so a disconnect frees exactly the seat its connect took. */
    private val seats = mutableMapOf<Int, String>()
    private val buttons = mutableMapOf<Int, Long>()
    private val axesCache = mutableMapOf<Int, FloatArray>()
    private val seqs = mutableMapOf<Int, Long>()

    /** Seats currently held by pads, for the race to snapshot at countdown end. */
    val playerIds: Set<String> get() = synchronized(lock) { seats.values.toSet() }

    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {
            addDevice(deviceId)
        }

        override fun onInputDeviceRemoved(deviceId: Int) {
            removeDevice(deviceId)
        }

        override fun onInputDeviceChanged(deviceId: Int) {
            val device = InputDevice.getDevice(deviceId)
            if (device == null || !isGamepad(device)) removeDevice(deviceId)
            else addDevice(deviceId)
        }
    }

    init {
        InputDevice.getDeviceIds().forEach { addDevice(it) }
        inputManager?.registerInputDeviceListener(deviceListener, null)
        if (inputManager == null) Log.status(TAG, "no InputManager - gamepads will not be seen")
    }

    fun close() {
        inputManager?.unregisterInputDeviceListener(deviceListener)
        synchronized(lock) { seats.keys.toList() }.forEach { removeDevice(it) }
    }

    /**
     * Feed a key event from the activity. True when a seated pad owned it.
     *
     * A press from an unknown device with a gamepad source seats it first, so a pad plugged in
     * between the listener firing and the first press still drives rather than dropping its first
     * input.
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        val bit = keyCodeToBit(event.keyCode) ?: return false
        var playerId = synchronized(lock) { seats[event.deviceId] }
        if (playerId == null) {
            val device = InputDevice.getDevice(event.deviceId)
            if (device == null || !isGamepad(device)) return false
            playerId = addDevice(event.deviceId) ?: return false
        }
        if (event.repeatCount > 0) return true
        val deviceId = event.deviceId
        when (event.action) {
            KeyEvent.ACTION_DOWN -> synchronized(lock) {
                buttons[deviceId] = (buttons[deviceId] ?: 0L) or bit
            }
            KeyEvent.ACTION_UP -> synchronized(lock) {
                buttons[deviceId] = (buttons[deviceId] ?: 0L) and bit.inv()
            }
            else -> return false
        }
        emit(deviceId, playerId)
        return true
    }

    /**
     * Feed a motion event from the activity. True when a seated pad owned it.
     *
     * Sticks arrive here; buttons ride along from the state the key path maintains, so a sample
     * always carries the whole pad, never half of it.
     */
    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_CLASS_JOYSTICK) == 0) return false
        var playerId = synchronized(lock) { seats[event.deviceId] }
        if (playerId == null) {
            val device = InputDevice.getDevice(event.deviceId)
            if (device == null || !isGamepad(device)) return false
            playerId = addDevice(event.deviceId) ?: return false
        }
        val device = InputDevice.getDevice(event.deviceId)
        val ranges: (Int) -> Float = { axis ->
            device?.getMotionRange(axis)?.let { range ->
                event.getAxisValue(axis).zeroWithin(range.flat)
            } ?: 0f
        }
        synchronized(lock) {
            axesCache[event.deviceId] = floatArrayOf(
                ranges(MotionEvent.AXIS_X),
                ranges(MotionEvent.AXIS_Y),
                ranges(MotionEvent.AXIS_Z),
                ranges(MotionEvent.AXIS_RZ),
                ranges(MotionEvent.AXIS_RTRIGGER),
                ranges(MotionEvent.AXIS_LTRIGGER),
            )
        }
        emit(event.deviceId, playerId)
        return true
    }

    private fun emit(deviceId: Int, playerId: String) {
        val (pressed, axes) = synchronized(lock) {
            (buttons[deviceId] ?: 0L) to
                (axesCache[deviceId]?.copyOf() ?: FloatArray(AXIS_COUNT))
        }
        val seq = synchronized(lock) {
            val next = (seqs[deviceId] ?: 0L) + 1
            seqs[deviceId] = next
            next
        }
        onInput(
            ControllerInput(
                playerId = playerId,
                seq = seq,
                buttons = pressed,
                axes = axes.toList(),
                hatX = hatOf(axes[AXIS_LEFT_X], pressed, ControllerButton.DPAD_LEFT, ControllerButton.DPAD_RIGHT),
                hatY = hatOf(axes[AXIS_LEFT_Y], pressed, ControllerButton.DPAD_UP, ControllerButton.DPAD_DOWN),
                clientTimestampMs = SystemClock.uptimeMillis(),
            ),
        )
    }

    private fun hatOf(
        axis: Float,
        pressed: Long,
        negative: ControllerButton,
        positive: ControllerButton,
    ): Int = when {
        pressed and negative.bit != 0L || axis < -HAT_THRESHOLD -> -1
        pressed and positive.bit != 0L || axis > HAT_THRESHOLD -> 1
        else -> 0
    }

    private fun addDevice(deviceId: Int): String? {
        val device = InputDevice.getDevice(deviceId) ?: return null
        if (!isGamepad(device)) return null
        synchronized(lock) {
            seats[deviceId]?.let { return it }
            val playerId = "pad-$deviceId"
            seats[deviceId] = playerId
            Log.status(TAG, "seated ${device.name} as $playerId")
            onSeat(playerId, device.name, true)
            return playerId
        }
    }

    private fun removeDevice(deviceId: Int) {
        val playerId = synchronized(lock) {
            buttons.remove(deviceId)
            axesCache.remove(deviceId)
            seqs.remove(deviceId)
            seats.remove(deviceId)
        } ?: return
        Log.status(TAG, "freed seat $playerId")
        onSeat(playerId, "", false)
    }

    companion object {
        /**
         * True for gamepads and joysticks only: keyboards, mice and remotes never take a seat, so
         * their keys always fall through to the system.
         */
        fun isGamepad(device: InputDevice): Boolean =
            (device.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (device.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

        /** A hardware value inside the device's own noise floor is rest, not input. */
        fun Float.zeroWithin(flat: Float): Float =
            if (kotlin.math.abs(this) <= flat) 0f else this

        /** `KeyEvent` buttons onto the same bitmask the phones send. */
        fun keyCodeToBit(keyCode: Int): Long? = when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> ControllerButton.A.bit
            KeyEvent.KEYCODE_BUTTON_B -> ControllerButton.B.bit
            KeyEvent.KEYCODE_BUTTON_X -> ControllerButton.X.bit
            KeyEvent.KEYCODE_BUTTON_Y -> ControllerButton.Y.bit
            KeyEvent.KEYCODE_BUTTON_L1 -> ControllerButton.LEFT_SHOULDER.bit
            KeyEvent.KEYCODE_BUTTON_R1 -> ControllerButton.RIGHT_SHOULDER.bit
            KeyEvent.KEYCODE_BUTTON_L2 -> ControllerButton.LEFT_TRIGGER.bit
            KeyEvent.KEYCODE_BUTTON_R2 -> ControllerButton.RIGHT_TRIGGER.bit
            KeyEvent.KEYCODE_BUTTON_START -> ControllerButton.START.bit
            KeyEvent.KEYCODE_BUTTON_SELECT -> ControllerButton.SELECT.bit
            KeyEvent.KEYCODE_BUTTON_THUMBL -> ControllerButton.LEFT_STICK.bit
            KeyEvent.KEYCODE_BUTTON_THUMBR -> ControllerButton.RIGHT_STICK.bit
            KeyEvent.KEYCODE_DPAD_UP -> ControllerButton.DPAD_UP.bit
            KeyEvent.KEYCODE_DPAD_DOWN -> ControllerButton.DPAD_DOWN.bit
            KeyEvent.KEYCODE_DPAD_LEFT -> ControllerButton.DPAD_LEFT.bit
            KeyEvent.KEYCODE_DPAD_RIGHT -> ControllerButton.DPAD_RIGHT.bit
            else -> null
        }

        /** Axis slots in [ControllerInput.axes], mirroring the SDK's fixed order. */
        @Suppress("unused")
        fun axisSlotFor(motionAxis: Int): Int? = when (motionAxis) {
            MotionEvent.AXIS_X -> AXIS_LEFT_X
            MotionEvent.AXIS_Y -> AXIS_LEFT_Y
            MotionEvent.AXIS_Z -> AXIS_RIGHT_X
            MotionEvent.AXIS_RZ -> AXIS_RIGHT_Y
            MotionEvent.AXIS_RTRIGGER -> AXIS_THROTTLE
            MotionEvent.AXIS_LTRIGGER -> AXIS_BRAKE
            else -> null
        }
    }
}
