package com.vayunmathur.camera.platform.camera2

import android.media.AudioDeviceInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Physical audio input devices: type->label mapping, the Auto-first
 * picker list, preferred-device application through a fake seam, and
 * stale/disconnected fallback to Auto.
 */
class AudioInputDevicesTest {

    private val builtin = RawAudioInputDevice(1, AudioDeviceInfo.TYPE_BUILTIN_MIC, null)
    private val usb = RawAudioInputDevice(2, AudioDeviceInfo.TYPE_USB_DEVICE, "USB Mic Pro")
    private val usbNoName = RawAudioInputDevice(3, AudioDeviceInfo.TYPE_USB_HEADSET, "  ")
    private val bluetooth = RawAudioInputDevice(4, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, null)
    private val wired = RawAudioInputDevice(5, AudioDeviceInfo.TYPE_WIRED_HEADSET, null)

    @Test fun typeLabelsMapToPhysicalDevices() {
        assertEquals("Built-in microphone", AudioInputDevicePolicy.typeLabel(AudioDeviceInfo.TYPE_BUILTIN_MIC))
        assertEquals("USB device", AudioInputDevicePolicy.typeLabel(AudioDeviceInfo.TYPE_USB_DEVICE))
        assertEquals("USB headset", AudioInputDevicePolicy.typeLabel(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertEquals("Bluetooth headset", AudioInputDevicePolicy.typeLabel(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertEquals("Bluetooth LE headset", AudioInputDevicePolicy.typeLabel(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertEquals("Wired headset", AudioInputDevicePolicy.typeLabel(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertEquals("Microphone", AudioInputDevicePolicy.typeLabel(AudioDeviceInfo.TYPE_UNKNOWN))
    }

    @Test fun productNameWinsOverTypeLabel() {
        assertEquals("USB Mic Pro", AudioInputDevicePolicy.labelFor(usb.type, usb.productName))
        assertEquals("Built-in microphone", AudioInputDevicePolicy.labelFor(builtin.type, null))
        // A blank product name falls back to the type label.
        assertEquals("USB headset", AudioInputDevicePolicy.labelFor(usbNoName.type, usbNoName.productName))
    }

    @Test fun optionsAreAutoFirstThenDevicesById() {
        val options = AudioInputDevicePolicy.options(listOf(builtin, usb, bluetooth))
        assertEquals(4, options.size)
        assertTrue(options[0].selection.isAuto)
        assertEquals(AudioInputDevicePolicy.AUTO_LABEL, options[0].label)
        assertEquals(AudioInputDeviceSelection(1, builtin.type), options[1].selection)
        assertEquals("USB Mic Pro", options[2].label)
        assertEquals("USB device", options[2].description)
        assertEquals("Bluetooth headset", options[3].label)
        // No devices: the picker is just Auto.
        assertEquals(1, AudioInputDevicePolicy.options(emptyList()).size)
    }

    @Test fun selectedDeviceIsAppliedThroughTheSeam() {
        val calls = mutableListOf<RawAudioInputDevice?>()
        val result = PreferredAudioDeviceApplication.apply(
            selection = AudioInputDeviceSelection(usb.id, usb.type),
            devices = listOf(builtin, usb),
            idOf = { it.id },
            typeOf = { it.type },
            setPreferred = { device -> calls += device; true },
        )
        assertEquals(listOf<RawAudioInputDevice?>(usb), calls)
        assertEquals(usb, result.device)
        assertTrue(result.applied)
        assertEquals(AudioInputDeviceSelection(usb.id, usb.type), result.selection)
    }

    @Test fun autoClearsThePreferredDevice() {
        val calls = mutableListOf<RawAudioInputDevice?>()
        val result = PreferredAudioDeviceApplication.apply(
            selection = AudioInputDeviceSelection.AUTO,
            devices = listOf(builtin),
            idOf = { it.id },
            typeOf = { it.type },
            setPreferred = { device -> calls += device; true },
        )
        assertEquals(listOf<RawAudioInputDevice?>(null), calls)
        assertNull(result.device)
        assertTrue(result.selection.isAuto)
    }

    @Test fun disconnectedChosenDeviceFallsBackToAutoSilently() {
        // Chosen USB mic is gone (or its id was reused by a different type):
        // resolve and apply both land on Auto instead of failing.
        assertEquals(
            AudioInputDeviceSelection.AUTO,
            AudioInputDevicePolicy.resolve(AudioInputDeviceSelection(usb.id, usb.type), listOf(builtin)),
        )
        assertEquals(
            AudioInputDeviceSelection.AUTO,
            AudioInputDevicePolicy.resolve(
                AudioInputDeviceSelection(usb.id, AudioDeviceInfo.TYPE_WIRED_HEADSET),
                listOf(usb),
            ),
        )
        val calls = mutableListOf<RawAudioInputDevice?>()
        val result = PreferredAudioDeviceApplication.apply(
            selection = AudioInputDeviceSelection(usb.id, usb.type),
            devices = listOf(builtin, wired),
            idOf = { it.id },
            typeOf = { it.type },
            setPreferred = { device -> calls += device; true },
        )
        assertTrue(result.selection.isAuto)
        assertEquals(listOf<RawAudioInputDevice?>(null), calls)
    }

    @Test fun rejectedOrThrowingSetterNeverFailsApplication() {
        val rejected = PreferredAudioDeviceApplication.apply(
            selection = AudioInputDeviceSelection(usb.id, usb.type),
            devices = listOf(usb),
            idOf = { it.id },
            typeOf = { it.type },
            setPreferred = { false },
        )
        assertFalse(rejected.applied)
        assertEquals(usb, rejected.device)

        val throwing = PreferredAudioDeviceApplication.apply(
            selection = AudioInputDeviceSelection(usb.id, usb.type),
            devices = listOf(usb),
            idOf = { it.id },
            typeOf = { it.type },
            setPreferred = { throw IllegalStateException("device gone") },
        )
        assertFalse(throwing.applied)
    }

    @Test fun persistedSelectionRoundTripsAndStaleValuesFallBackToAuto() {
        val chosen = AudioInputDeviceSelection(7, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        assertEquals(chosen, AudioInputDeviceSelection.parse(chosen.encode()))
        assertEquals(AudioInputDeviceSelection.AUTO, AudioInputDeviceSelection.parse("auto"))
        assertEquals(AudioInputDeviceSelection.AUTO, AudioInputDeviceSelection.parse(null))
        // Stale values from the retired AudioSource-type setting.
        for (stale in listOf("CAMCORDER", "MIC", "VOICE_COMMUNICATION", "UNPROCESSED", "", "12", "x:y", "1:2:3")) {
            assertEquals(AudioInputDeviceSelection.AUTO, AudioInputDeviceSelection.parse(stale), stale)
        }
        assertTrue(AudioInputDeviceSelection.AUTO.isAuto)
        assertFalse(chosen.isAuto)
    }
}
