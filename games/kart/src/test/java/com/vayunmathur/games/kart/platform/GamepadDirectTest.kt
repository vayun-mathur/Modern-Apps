package com.vayunmathur.games.kart.platform

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.kart.platform.GamepadDirect.Companion.axisSlotFor
import com.vayunmathur.games.kart.platform.GamepadDirect.Companion.isGamepad
import com.vayunmathur.games.kart.platform.GamepadDirect.Companion.keyCodeToBit
import com.vayunmathur.games.kart.platform.GamepadDirect.Companion.zeroWithin
import com.vayunmathur.sdk.controller.AXIS_BRAKE
import com.vayunmathur.sdk.controller.AXIS_LEFT_X
import com.vayunmathur.sdk.controller.AXIS_LEFT_Y
import com.vayunmathur.sdk.controller.AXIS_RIGHT_X
import com.vayunmathur.sdk.controller.AXIS_RIGHT_Y
import com.vayunmathur.sdk.controller.AXIS_THROTTLE
import com.vayunmathur.sdk.controller.ControllerButton
import com.vayunmathur.sdk.controller.ControllerInput
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.InputDeviceBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class GamepadDirectTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private val inputManager get() = ctx.getSystemService(InputManager::class.java)

    private fun gamepad(id: Int, name: String = "Pad $id"): InputDevice =
        InputDeviceBuilder.newBuilder()
            .setId(id)
            .setName(name)
            .setSources(InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK)
            .addMotionRange(MotionEvent.AXIS_X, InputDevice.SOURCE_JOYSTICK, -1f, 1f, 0.1f, 0f, 1f)
            .addMotionRange(MotionEvent.AXIS_Y, InputDevice.SOURCE_JOYSTICK, -1f, 1f, 0.1f, 0f, 1f)
            .addMotionRange(MotionEvent.AXIS_Z, InputDevice.SOURCE_JOYSTICK, -1f, 1f, 0.1f, 0f, 1f)
            .addMotionRange(MotionEvent.AXIS_RZ, InputDevice.SOURCE_JOYSTICK, -1f, 1f, 0.1f, 0f, 1f)
            .addMotionRange(MotionEvent.AXIS_RTRIGGER, InputDevice.SOURCE_JOYSTICK, 0f, 1f, 0f, 0f, 1f)
            .addMotionRange(MotionEvent.AXIS_LTRIGGER, InputDevice.SOURCE_JOYSTICK, 0f, 1f, 0f, 0f, 1f)
            .build()

    private fun key(action: Int, code: Int, deviceId: Int, repeat: Int = 0) =
        KeyEvent(0L, 0L, action, code, repeat, 0, deviceId, 0)

    @Test fun companionMappings() {
        assertEquals(ControllerButton.A.bit, keyCodeToBit(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(ControllerButton.B.bit, keyCodeToBit(KeyEvent.KEYCODE_BUTTON_B))
        assertEquals(ControllerButton.X.bit, keyCodeToBit(KeyEvent.KEYCODE_BUTTON_X))
        assertEquals(ControllerButton.Y.bit, keyCodeToBit(KeyEvent.KEYCODE_BUTTON_Y))
        assertEquals(ControllerButton.LEFT_SHOULDER.bit, keyCodeToBit(KeyEvent.KEYCODE_BUTTON_L1))
        assertEquals(ControllerButton.RIGHT_SHOULDER.bit, keyCodeToBit(KeyEvent.KEYCODE_BUTTON_R1))
        assertEquals(ControllerButton.LEFT_TRIGGER.bit, keyCodeToBit(KeyEvent.KEYCODE_BUTTON_L2))
        assertEquals(ControllerButton.RIGHT_TRIGGER.bit, keyCodeToBit(KeyEvent.KEYCODE_BUTTON_R2))
        assertEquals(ControllerButton.START.bit, keyCodeToBit(KeyEvent.KEYCODE_BUTTON_START))
        assertEquals(ControllerButton.SELECT.bit, keyCodeToBit(KeyEvent.KEYCODE_BUTTON_SELECT))
        assertEquals(ControllerButton.LEFT_STICK.bit, keyCodeToBit(KeyEvent.KEYCODE_BUTTON_THUMBL))
        assertEquals(ControllerButton.RIGHT_STICK.bit, keyCodeToBit(KeyEvent.KEYCODE_BUTTON_THUMBR))
        assertEquals(ControllerButton.DPAD_UP.bit, keyCodeToBit(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(ControllerButton.DPAD_DOWN.bit, keyCodeToBit(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(ControllerButton.DPAD_LEFT.bit, keyCodeToBit(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(ControllerButton.DPAD_RIGHT.bit, keyCodeToBit(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertNull(keyCodeToBit(KeyEvent.KEYCODE_VOLUME_UP))
        assertNull(keyCodeToBit(KeyEvent.KEYCODE_A))

        assertEquals(AXIS_LEFT_X, axisSlotFor(MotionEvent.AXIS_X))
        assertEquals(AXIS_LEFT_Y, axisSlotFor(MotionEvent.AXIS_Y))
        assertEquals(AXIS_RIGHT_X, axisSlotFor(MotionEvent.AXIS_Z))
        assertEquals(AXIS_RIGHT_Y, axisSlotFor(MotionEvent.AXIS_RZ))
        assertEquals(AXIS_THROTTLE, axisSlotFor(MotionEvent.AXIS_RTRIGGER))
        assertEquals(AXIS_BRAKE, axisSlotFor(MotionEvent.AXIS_LTRIGGER))
        assertNull(axisSlotFor(MotionEvent.AXIS_HAT_X))

        assertTrue(isGamepad(gamepad(41)))
        val keyboard = InputDeviceBuilder.newBuilder().setId(42).setName("kbd")
            .setSources(InputDevice.SOURCE_KEYBOARD).build()
        assertFalse(isGamepad(keyboard))

        assertEquals(0f, 0.05f.zeroWithin(0.1f))
        assertEquals(0f, (-0.1f).zeroWithin(0.1f))
        assertEquals(0.5f, 0.5f.zeroWithin(0.1f))
        assertEquals(-0.5f, (-0.5f).zeroWithin(0.1f))
    }

    @Test fun seatedPadKeysMotionAndRemoval() {
        val seats = mutableListOf<Triple<String, String, Boolean>>()
        val inputs = mutableListOf<ControllerInput>()
        Shadows.shadowOf(inputManager).addInputDevice(gamepad(7, "Pro Pad"))
        val direct = GamepadDirect(ctx, { id, name, present -> seats += Triple(id, name, present) }, { inputs += it })
        try {
            assertEquals(listOf(Triple("pad-7", "Pro Pad", true)), seats)
            assertEquals(setOf("pad-7"), direct.playerIds)

            // Unknown key falls through; known key seats/emits.
            assertFalse(direct.onKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP, 7)))
            assertTrue(direct.onKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, 7)))
            assertEquals(1, inputs.size)
            assertEquals("pad-7", inputs.last().playerId)
            assertEquals(1L, inputs.last().seq)
            assertTrue(inputs.last().isPressed(ControllerButton.A))

            // Repeat is consumed but does not re-emit.
            assertTrue(direct.onKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, 7, repeat = 1)))
            assertEquals(1, inputs.size)

            assertTrue(direct.onKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, 7)))
            assertTrue(inputs.last().isPressed(ControllerButton.A))
            assertTrue(inputs.last().isPressed(ControllerButton.DPAD_LEFT))
            assertEquals(-1, inputs.last().hatX)
            assertTrue(direct.onKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A, 7)))
            assertFalse(inputs.last().isPressed(ControllerButton.A))
            // Unmapped action type is rejected after seating check path.
            assertFalse(direct.onKeyEvent(key(99, KeyEvent.KEYCODE_BUTTON_B, 7)))

            // Motion: non-joystick source rejected, joystick sample emits axes with flat deadzone.
            val pointerProps = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
            val coords = arrayOf(MotionEvent.PointerCoords().apply {
                setAxisValue(MotionEvent.AXIS_X, 0.05f) // inside flat -> 0
                setAxisValue(MotionEvent.AXIS_Y, -0.8f)
                setAxisValue(MotionEvent.AXIS_RTRIGGER, 0.7f)
            })
            val touch = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_MOVE, 1, pointerProps, coords, 0, 0, 1f, 1f, 7, 0, InputDevice.SOURCE_TOUCHSCREEN, 0, 0, 0)!!
            assertFalse(direct.onGenericMotionEvent(touch))
            touch.recycle()
            val stick = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_MOVE, 1, pointerProps, coords, 0, 0, 1f, 1f, 7, 0, InputDevice.SOURCE_JOYSTICK, 0, 0, 0)!!
            assertTrue(direct.onGenericMotionEvent(stick))
            stick.recycle()
            val motion = inputs.last()
            assertEquals(0f, motion.axes[AXIS_LEFT_X])
            assertEquals(-0.8f, motion.axes[AXIS_LEFT_Y])
            assertEquals(0.7f, motion.axes[AXIS_THROTTLE])
            assertEquals(-1, motion.hatY)
            assertTrue(motion.seq > inputs[inputs.size - 2].seq)
        } finally {
            direct.close()
        }
        assertTrue(seats.contains(Triple("pad-7", "", false)))
        assertEquals(emptySet(), direct.playerIds)
    }

    @Test fun unseatedAndKeyboardDevicesFallThrough() {
        val seats = mutableListOf<Triple<String, String, Boolean>>()
        val inputs = mutableListOf<ControllerInput>()
        val direct = GamepadDirect(ctx, { id, name, present -> seats += Triple(id, name, present) }, { inputs += it })
        try {
            // No device registered at all.
            assertFalse(direct.onKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, 999)))
            // A keyboard device never takes a seat.
            Shadows.shadowOf(inputManager).addInputDevice(
                InputDeviceBuilder.newBuilder().setId(55).setName("kbd")
                    .setSources(InputDevice.SOURCE_KEYBOARD).build(),
            )
            assertFalse(direct.onKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, 55)))
            val coords = arrayOf(MotionEvent.PointerCoords())
            val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0 })
            val ev = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_MOVE, 1, props, coords, 0, 0, 1f, 1f, 55, 0, InputDevice.SOURCE_JOYSTICK, 0, 0, 0)!!
            assertFalse(direct.onGenericMotionEvent(ev))
            ev.recycle()
            assertTrue(seats.isEmpty())
            assertTrue(inputs.isEmpty())
            assertEquals(emptySet(), direct.playerIds)
        } finally {
            direct.close()
        }
    }

    @Test fun lateSeatingViaFirstKeyAndMotionFromUnknownPad() {
        val seats = mutableListOf<Triple<String, String, Boolean>>()
        val inputs = mutableListOf<ControllerInput>()
        val direct = GamepadDirect(ctx, { id, name, present -> seats += Triple(id, name, present) }, { inputs += it })
        try {
            // Device known to the system but GamepadDirect was built before it appeared;
            // the first key press seats it instead of dropping the input.
            Shadows.shadowOf(inputManager).addInputDevice(gamepad(9, "Late Pad"))
            // addInputDevice may notify the listener (seating it); either path must end seated.
            assertTrue(direct.onKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_R1, 9)))
            assertTrue(direct.playerIds.contains("pad-9"))
            assertTrue(inputs.last().isPressed(ControllerButton.RIGHT_SHOULDER))
        } finally {
            direct.close()
        }
        assertTrue(seats.any { it.first == "pad-9" && !it.third })
    }
}
