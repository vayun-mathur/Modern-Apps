package com.vayunmathur.camera.platform.camera2

import android.media.AudioDeviceInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Physical audio input devices: type->label mapping, one picker row per
 * physical device (duplicate platform ports grouped), preferred-device
 * application through a fake seam, and stale/disconnected fallback.
 */
class AudioInputDevicesTest {

    private val builtin = RawAudioInputDevice(1, AudioDeviceInfo.TYPE_BUILTIN_MIC, null)
    private val usb = RawAudioInputDevice(2, AudioDeviceInfo.TYPE_USB_DEVICE, "USB Mic Pro")
    private val usbNoName = RawAudioInputDevice(3, AudioDeviceInfo.TYPE_USB_HEADSET, "  ")
    private val bluetooth = RawAudioInputDevice(4, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, null)
    private val wired = RawAudioInputDevice(5, AudioDeviceInfo.TYPE_WIRED_HEADSET, null)

    @Test fun typeLabelsMapToPhysicalDevices() {
        assertEquals("Built-in microphone", AudioInputDevicePolicy.typeLabel(AudioDeviceInfo.TYPE_BUILTIN_MIC))
        assertEquals("USB microphone", AudioInputDevicePolicy.typeLabel(AudioDeviceInfo.TYPE_USB_DEVICE))
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

    @Test fun optionsListOnlyRealDevicesInEnumerationOrder() {
        val options = AudioInputDevicePolicy.options(listOf(builtin, usb, bluetooth))
        // No synthetic rows: exactly the three real devices, in order.
        assertEquals(3, options.size)
        assertTrue(options.none { it.selection.isAuto })
        assertEquals(AudioInputDeviceSelection(1, builtin.type), options[0].selection)
        assertEquals("USB Mic Pro", options[1].label)
        assertEquals("USB microphone", options[1].description)
        assertEquals("Bluetooth headset", options[2].label)
        // No devices: the picker is empty, not a synthetic default row.
        assertEquals(0, AudioInputDevicePolicy.options(emptyList()).size)
    }

    @Test fun duplicatePortsOfOnePhysicalDeviceCollapseToOneRow() {
        // A Pixel-class phone reports several built-in mic ports, all with
        // the same product name and no distinguishing address: five ports,
        // one physical device, one row labelled with the name alone.
        val pixelPorts = (10..14).map {
            RawAudioInputDevice(it, AudioDeviceInfo.TYPE_BUILTIN_MIC, "Pixel 9 Pro", "")
        }
        val pixelOptions = AudioInputDevicePolicy.options(pixelPorts)
        assertEquals(1, pixelOptions.size)
        assertEquals("Pixel 9 Pro", pixelOptions[0].label)
        assertFalse(pixelOptions[0].label.first().isDigit())

        // A USB endpoint reported as both a generic USB device and a USB
        // headset (same name + USB address) is one row, represented by the
        // more specific headset port.
        val usbGeneric = RawAudioInputDevice(20, AudioDeviceInfo.TYPE_USB_DEVICE, "USB Mic Pro", "card=1;device=0")
        val usbHeadset = RawAudioInputDevice(21, AudioDeviceInfo.TYPE_USB_HEADSET, "USB Mic Pro", "card=1;device=0")
        val usbOptions = AudioInputDevicePolicy.options(listOf(usbGeneric, usbHeadset))
        assertEquals(1, usbOptions.size)
        assertEquals(
            AudioInputDeviceSelection(21, AudioDeviceInfo.TYPE_USB_HEADSET, "USB Mic Pro", "card=1;device=0"),
            usbOptions[0].selection,
        )
        assertEquals("USB Mic Pro", usbOptions[0].label)
        assertEquals("USB headset", usbOptions[0].description)

        // A Bluetooth endpoint reported as SCO and BLE (same name + MAC)
        // is one row, represented by the BLE port.
        val sco = RawAudioInputDevice(30, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Buds", "AA:BB:CC:DD:EE:FF")
        val ble = RawAudioInputDevice(31, AudioDeviceInfo.TYPE_BLE_HEADSET, "Buds", "AA:BB:CC:DD:EE:FF")
        val btOptions = AudioInputDevicePolicy.options(listOf(sco, ble))
        assertEquals(1, btOptions.size)
        assertEquals(
            AudioInputDeviceSelection(31, AudioDeviceInfo.TYPE_BLE_HEADSET, "Buds", "AA:BB:CC:DD:EE:FF"),
            btOptions[0].selection,
        )
    }

    @Test fun portsOfOneDeviceMergeWhenOnlySomeCarryAnAddress() {
        // Known trap: one physical endpoint, the SCO port reports the MAC
        // while its BLE port reports no address (and vice versa for USB).
        // Grouping on address alone splits these into duplicate rows.
        val sco = RawAudioInputDevice(30, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Buds", "AA:BB:CC:DD:EE:FF")
        val bleNoAddress = RawAudioInputDevice(31, AudioDeviceInfo.TYPE_BLE_HEADSET, "Buds", "")
        assertEquals(1, AudioInputDevicePolicy.options(listOf(sco, bleNoAddress)).size)
        val usbAddressed = RawAudioInputDevice(40, AudioDeviceInfo.TYPE_USB_DEVICE, "Desk Mic", "card=1;device=0")
        val usbHeadsetNoAddress = RawAudioInputDevice(41, AudioDeviceInfo.TYPE_USB_HEADSET, "Desk Mic", "")
        val usbOptions = AudioInputDevicePolicy.options(listOf(usbAddressed, usbHeadsetNoAddress))
        assertEquals(1, usbOptions.size)
        assertEquals("card=1;device=0", usbOptions[0].selection.address)
        // Same name in different endpoint families is not one device.
        val builtinNamed = RawAudioInputDevice(50, AudioDeviceInfo.TYPE_BUILTIN_MIC, "Desk Mic", "")
        assertEquals(2, AudioInputDevicePolicy.options(listOf(builtinNamed, usbHeadsetNoAddress)).size)
    }

    @Test fun sameNameDevicesAtDistinctAddressesStaySeparateRows() {
        val first = RawAudioInputDevice(20, AudioDeviceInfo.TYPE_USB_DEVICE, "USB Mic Pro", "card=1;device=0")
        val second = RawAudioInputDevice(21, AudioDeviceInfo.TYPE_USB_DEVICE, "USB Mic Pro", "card=2;device=0")
        assertEquals(2, AudioInputDevicePolicy.options(listOf(first, second)).size)
        val btFirst = RawAudioInputDevice(30, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Buds", "AA:BB:CC:DD:EE:01")
        val btSecond = RawAudioInputDevice(31, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Buds", "AA:BB:CC:DD:EE:02")
        assertEquals(2, AudioInputDevicePolicy.options(listOf(btFirst, btSecond)).size)
        // Unnamed devices of different types are different endpoints, even
        // with no address to distinguish them.
        assertEquals(2, AudioInputDevicePolicy.options(listOf(builtin, wired)).size)
    }

    @Test fun groupLabelUsesAnyPortNameAndNeverAddsAPrefix() {
        // The representative (headset) port reports no name; its duplicate
        // generic port does. The row still gets the real product name.
        val generic = RawAudioInputDevice(20, AudioDeviceInfo.TYPE_USB_DEVICE, "Desk Mic", "card=1;device=0")
        val headset = RawAudioInputDevice(21, AudioDeviceInfo.TYPE_USB_HEADSET, " ", "card=1;device=0")
        val options = AudioInputDevicePolicy.options(listOf(generic, headset))
        assertEquals(1, options.size)
        assertEquals("Desk Mic", options[0].label)
        for (option in AudioInputDevicePolicy.options(listOf(builtin, usb, bluetooth))) {
            assertFalse(option.label.first().isDigit(), option.label)
            assertFalse(option.label.contains(option.selection.deviceId.toString()), option.label)
        }
    }

    @Test fun storedDuplicatePortSelectionResolvesToItsPhysicalGroup() {
        val usbGeneric = RawAudioInputDevice(20, AudioDeviceInfo.TYPE_USB_DEVICE, "USB Mic Pro", "card=1;device=0")
        val usbHeadset = RawAudioInputDevice(21, AudioDeviceInfo.TYPE_USB_HEADSET, "USB Mic Pro", "card=1;device=0")
        val devices = listOf(builtin, usbGeneric, usbHeadset)
        // Stored before grouping, naming the non-representative generic port.
        val stored = AudioInputDeviceSelection(usbGeneric.id, usbGeneric.type)
        assertEquals(
            AudioInputDeviceSelection(usbHeadset.id, usbHeadset.type, "USB Mic Pro", "card=1;device=0"),
            AudioInputDevicePolicy.resolve(stored, devices),
        )
        // At record start the group resolves back to a live port of the same
        // device, preferring the exact persisted port while it is present.
        val calls = mutableListOf<RawAudioInputDevice?>()
        val result = PreferredAudioDeviceApplication.apply(
            selection = stored,
            devices = devices,
            idOf = { it.id },
            typeOf = { it.type },
            setPreferred = { device -> calls += device; true },
            nameOf = { it.productName },
            addressOf = { it.address },
        )
        assertEquals(
            AudioInputDeviceSelection(usbHeadset.id, usbHeadset.type, "USB Mic Pro", "card=1;device=0"),
            result.selection,
        )
        assertEquals(usbGeneric, result.device)
        assertEquals(listOf<RawAudioInputDevice?>(usbGeneric), calls)
        assertTrue(result.applied)
    }

    @Test fun disconnectedChoiceLeavesNoPreferenceSoTheSystemRoutes() {
        // Absence of a pick is the system default: a gone device must not be
        // silently replaced by pinning whatever happens to be routed.
        val calls = mutableListOf<RawAudioInputDevice?>()
        val result = PreferredAudioDeviceApplication.apply(
            selection = AudioInputDeviceSelection(usb.id, usb.type),
            devices = listOf(builtin, wired),
            idOf = { it.id },
            typeOf = { it.type },
            setPreferred = { device -> calls += device; true },
        )
        assertEquals(AudioInputDeviceSelection.AUTO, result.selection)
        assertNull(result.device)
        assertEquals(listOf<RawAudioInputDevice?>(null), calls)
    }

    @Test fun storedPickReResolvesByIdentityWhenPortIdsChurn() {
        // Persisted with the endpoint identity but a stale port id (ids are
        // runtime-assigned): the same physical device under a new id wins.
        val stored = AudioInputDeviceSelection(99, AudioDeviceInfo.TYPE_BLE_HEADSET, "Buds", "AA:BB:CC:DD:EE:FF")
        val renumbered = RawAudioInputDevice(7, AudioDeviceInfo.TYPE_BLE_HEADSET, "Buds", "AA:BB:CC:DD:EE:FF")
        val resolved = AudioInputDevicePolicy.resolve(stored, listOf(builtin, renumbered))
        assertEquals(7, resolved.deviceId)
        assertEquals(AudioDeviceInfo.TYPE_BLE_HEADSET, resolved.deviceType)
        // A different endpoint reusing the name at another address does not match.
        val other = RawAudioInputDevice(8, AudioDeviceInfo.TYPE_BLE_HEADSET, "Buds", "AA:BB:CC:DD:EE:01")
        assertEquals(
            AudioInputDeviceSelection.AUTO,
            AudioInputDevicePolicy.resolve(stored, listOf(builtin, other)),
        )
    }

    @Test fun routedReadbackVerifiesThePickStuck() {
        val sco = RawAudioInputDevice(30, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Buds", "AA:BB:CC:DD:EE:FF")
        val ble = RawAudioInputDevice(31, AudioDeviceInfo.TYPE_BLE_HEADSET, "Buds", "AA:BB:CC:DD:EE:FF")
        val devices = listOf(builtin, sco, ble)
        val picked = AudioInputDevicePolicy.options(devices).first { it.label == "Buds" }.selection
        // Routed to a sibling port of the picked endpoint: stuck.
        assertTrue(AudioInputDevicePolicy.isRoutedTo(picked, devices, sco))
        // Routed to the built-in mic instead: the pick did not stick.
        assertFalse(AudioInputDevicePolicy.isRoutedTo(picked, devices, builtin))
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
        // This fake seam passes no endpoint identity, so none is resolved.
        assertEquals(AudioInputDeviceSelection(usb.id, usb.type), result.selection)
    }

    @Test fun noExplicitChoiceClearsThePreferredDevice() {
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
        // resolve and apply both land on no explicit choice instead of failing.
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
        // Endpoint identity (name + address) survives the storage round-trip.
        val identified = AudioInputDeviceSelection(
            7, AudioDeviceInfo.TYPE_BLE_HEADSET, "Buds: Pro", "AA:BB:CC:DD:EE:FF",
        )
        assertEquals(identified, AudioInputDeviceSelection.parse(identified.encode()))
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
