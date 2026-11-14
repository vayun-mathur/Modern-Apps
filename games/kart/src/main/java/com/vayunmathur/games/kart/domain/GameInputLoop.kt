package com.vayunmathur.games.kart.domain

import com.vayunmathur.sdk.controller.AXIS_BRAKE
import com.vayunmathur.sdk.controller.AXIS_LEFT_X
import com.vayunmathur.sdk.controller.AXIS_LEFT_Y
import com.vayunmathur.sdk.controller.AXIS_THROTTLE
import com.vayunmathur.sdk.controller.ControllerButton
import com.vayunmathur.sdk.controller.ControllerInput

/**
 * One tick of kart control, normalized and game-ready.
 *
 * [throttle] 0..1 forward, [brake] 0..1, [steering] -1..1 (left negative), [drift] held,
 * [itemUse] edge-triggered by the game (the loop sets it from the button state; the game consumes
 * and clears it, so holding the button does not fire twice).
 */
data class KartControl(
    val throttle: Float = 0f,
    val steering: Float = 0f,
    val brake: Float = 0f,
    val drift: Boolean = false,
    val itemUse: Boolean = false,
) {
    /** Shared constants and helpers for KartControl. */
    companion object {
        val NEUTRAL = KartControl()
    }
}

/**
 * The fan-in: network phones and local gamepads arrive as generic [ControllerInput] and leave as
 * one [KartControl] per player per tick.
 *
 * Deadzones and the cubic curve match the voxels `Joystick` feel (0.15 deadzone, then
 * `0.2a + 0.8a³`), so a phone stick and a TV gamepad with the same deflection drive the same line.
 * The game reads the output on its own tick; samples are idempotent snapshots, so a missed frame
 * costs one tick of staleness and repairs itself.
 */
object GameInputLoop {

    /** Stick deflection below this is rest, not input. Matches the voxels move stick. */
    const val DEADZONE = 0.15f

    /**
     * Merge one sample into kart control.
     *
     * Steering comes from the left stick X; throttle prefers the right trigger axis, then the left
     * stick Y pushed up; brake prefers the left trigger axis, then stick Y pulled down. Trigger
     * axes are 0..1 pressed amounts (a centered stick leaves them at 0 = unpressed; gamepads that
     * report -1..1 normalize at their own layer). Drift is the right shoulder; item is A. D-pad
     * left/right nudge the steering when the stick is at rest, so a D-pad-only pad can corner.
     */
    fun normalize(input: ControllerInput): KartControl {
        val axes = input.axes
        val stickX = axes.getOrElse(AXIS_LEFT_X) { 0f }
        val stickY = axes.getOrElse(AXIS_LEFT_Y) { 0f }
        val throttleAxis = axes.getOrElse(AXIS_THROTTLE) { 0f }
        val brakeAxis = axes.getOrElse(AXIS_BRAKE) { 0f }

        var steering = curve(stickX)
        if (steering == 0f) {
            if (input.isPressed(ControllerButton.DPAD_LEFT)) steering = -0.6f
            if (input.isPressed(ControllerButton.DPAD_RIGHT)) steering = 0.6f
        }
        val throttle = maxOf(curve(throttleAxis.coerceIn(0f, 1f)), curve(-stickY))
        val brake = maxOf(curve(brakeAxis.coerceIn(0f, 1f)), curve(stickY))

        return KartControl(
            throttle = throttle.coerceIn(0f, 1f),
            steering = steering.coerceIn(-1f, 1f),
            brake = brake.coerceIn(0f, 1f),
            drift = input.isPressed(ControllerButton.RIGHT_SHOULDER) ||
                input.isPressed(ControllerButton.LEFT_SHOULDER),
            itemUse = input.isPressed(ControllerButton.A),
        )
    }

    /**
     * The voxels stick curve: deadzone, then `0.2a + 0.8a³` for fine control near center and full
     * throw at the edge.
     */
    fun curve(v: Float): Float {
        val a = kotlin.math.abs(v)
        if (a < DEADZONE) return 0f
        // Rescale so the curve starts at zero at the deadzone edge rather than jumping.
        val t = ((a - DEADZONE) / (1f - DEADZONE)).coerceIn(0f, 1f)
        val shaped = 0.2f * t + 0.8f * t * t * t
        return if (v < 0f) -shaped else shaped
    }
}
