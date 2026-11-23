package com.vayunmathur.camera.platform.camera2

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * A user-chosen physical audio input device (built-in mic, USB mic,
 * Bluetooth headset mic, ...), as opposed to a `MediaRecorder.AudioSource`
 * type. The internal audio source for video recording is always CAMCORDER;
 * this selection only steers which physical device feeds it, through
 * `AudioRecord.setPreferredDevice` (see [PreferredAudioDeviceApplication]).
 *
 * Persisted by device id + type: ids can be reused after a disconnect, so
 * the type is kept alongside as a sanity match. [AUTO] is only the internal
 * "no explicit device chosen yet" sentinel (and the parse result for legacy
 * stored values); it is never a picker row — the picker lists only real
 * devices returned by the platform (see [AudioInputDevicePolicy.options]).
 */
data class AudioInputDeviceSelection(val deviceId: Int? = null, val deviceType: Int? = null) {
    val isAuto: Boolean get() = deviceId == null

    /** Storage form: `auto` (no explicit choice) or `<id>:<type>`. */
    fun encode(): String = if (isAuto) ENCODED_AUTO else "$deviceId:$deviceType"

    companion object {
        val AUTO = AudioInputDeviceSelection()
        private const val ENCODED_AUTO = "auto"

        /**
         * Parses a stored value. Anything that is not a well-formed
         * `<id>:<type>` pair — including stale values from the retired
         * AudioSource-type setting (`CAMCORDER`, `MIC`, ...) — falls back
         * to [AUTO] instead of crashing.
         */
        fun parse(stored: String?): AudioInputDeviceSelection {
            if (stored.isNullOrBlank() || stored == ENCODED_AUTO) return AUTO
            val parts = stored.split(":")
            if (parts.size != 2) return AUTO
            val id = parts[0].toIntOrNull() ?: return AUTO
            val type = parts[1].toIntOrNull() ?: return AUTO
            return AudioInputDeviceSelection(id, type)
        }
    }
}

/** Platform-neutral snapshot of one connected input device (for mapping/tests). */
data class RawAudioInputDevice(
    val id: Int,
    val type: Int,
    val productName: String?,
    val address: String = "",
)

/** One row in the input-device picker: one physical device, nothing else. */
data class AudioInputDeviceOption(
    val selection: AudioInputDeviceSelection,
    val label: String,
    val description: String? = null,
)

object AudioInputDevicePolicy {

    /** Display label: the product name when the device reports one, else its type. */
    fun labelFor(type: Int, productName: String?): String =
        productName?.trim()?.takeIf { it.isNotEmpty() } ?: typeLabel(type)

    fun typeLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in microphone"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB microphone"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB accessory"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth headset"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE headset"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_TELEPHONY -> "Telephony"
        AudioDeviceInfo.TYPE_FM_TUNER -> "FM tuner"
        else -> "Microphone"
    }

    /**
     * Most-specific-first rank for choosing a group's representative entry.
     * A USB headset endpoint also reported as a generic USB device should
     * be represented by the headset entry; a BLE headset beats its SCO
     * variant. Lower is more specific; ties break on the lowest device id.
     */
    private fun typePriority(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_BLE_HEADSET -> 0
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 1
        AudioDeviceInfo.TYPE_USB_HEADSET -> 2
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> 3
        AudioDeviceInfo.TYPE_USB_DEVICE -> 4
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> 5
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> 6
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> 7
        AudioDeviceInfo.TYPE_TELEPHONY -> 8
        AudioDeviceInfo.TYPE_FM_TUNER -> 9
        else -> 100
    }

    private fun representativeOf(group: List<RawAudioInputDevice>): RawAudioInputDevice =
        group.minWith(compareBy<RawAudioInputDevice> { typePriority(it.type) }.thenBy { it.id })

    /**
     * Physical-device identity, derived only from `AudioDeviceInfo`'s own
     * accessors. The platform defines one *port* per [AudioDeviceInfo]:
     * `getId()` is unique per enumerated port entry (runtime-assigned, so
     * duplicate ports of one endpoint have different ids and an id can be
     * reused after disconnect), `getType()` is that port's type, while
     * `getProductName()` (the human-readable endpoint name) and
     * `getAddress()` (the endpoint address — e.g. a Bluetooth MAC or USB
     * address, often empty for built-in devices) describe the endpoint
     * behind the ports. No platform API returns coalesced physical devices
     * or a stable per-physical handle, so physical identity follows the
     * endpoint identity those accessors define, strongest first: a
     * non-empty address identifies the endpoint (ports of one endpoint
     * share it even when only some report a product name); with no
     * address, a meaningful product name identifies it (built-in ports);
     * with neither, the port type keeps genuinely different unnamed
     * endpoints apart.
     */
    private data class PhysicalKey(val name: String, val address: String, val unnamedType: Int?)

    private fun physicalKeyOf(device: RawAudioInputDevice): PhysicalKey {
        val name = device.productName?.trim().orEmpty()
        val address = device.address.trim()
        return when {
            address.isNotEmpty() -> PhysicalKey("", address, null)
            name.isNotEmpty() -> PhysicalKey(name, "", null)
            else -> PhysicalKey("", "", device.type)
        }
    }

    /** Groups raw ports into one list per physical device, in first-seen order. */
    fun groupByPhysicalDevice(
        devices: List<RawAudioInputDevice>,
    ): List<List<RawAudioInputDevice>> {
        val groups = LinkedHashMap<PhysicalKey, MutableList<RawAudioInputDevice>>()
        for (device in devices) groups.getOrPut(physicalKeyOf(device)) { mutableListOf() } += device
        return groups.values.map { it.toList() }
    }

    /**
     * Picker rows: exactly one per physical device (no synthetic rows).
     * Each row's selection is its group's representative entry (the most
     * specific port type), and its label is the endpoint's product name —
     * never prefixed with an id or a count — falling back to the friendly
     * type label when no port in the group reports a meaningful name.
     */
    fun options(devices: List<RawAudioInputDevice>): List<AudioInputDeviceOption> =
        groupByPhysicalDevice(devices).map { group ->
            val representative = representativeOf(group)
            val label = labelFor(
                representative.type,
                representative.productName?.takeIf { it.isNotBlank() }
                    ?: group.firstOrNull { !it.productName.isNullOrBlank() }?.productName,
            )
            val type = typeLabel(representative.type)
            AudioInputDeviceOption(
                selection = AudioInputDeviceSelection(representative.id, representative.type),
                label = label,
                description = type.takeIf { it != label },
            )
        }

    /**
     * The effective selection given what is currently connected. A chosen
     * device stays chosen through its physical group: a stored `<id>:<type>`
     * naming any port of the device (e.g. a non-representative duplicate
     * written before grouping) resolves to the group's representative,
     * rather than demanding that exact port. A device with no live port —
     * or whose id was reused by a different type — yields [AUTO] (no
     * explicit choice), and the record-time fallback takes over.
     */
    fun resolve(
        selection: AudioInputDeviceSelection,
        devices: List<RawAudioInputDevice>,
    ): AudioInputDeviceSelection {
        if (selection.isAuto) return AudioInputDeviceSelection.AUTO
        val exact = devices.firstOrNull {
            it.id == selection.deviceId && it.type == selection.deviceType
        } ?: return AudioInputDeviceSelection.AUTO
        val group = groupByPhysicalDevice(devices).first { exact in it }
        val representative = representativeOf(group)
        return AudioInputDeviceSelection(representative.id, representative.type)
    }
}

/**
 * Applies a selection to a recorder through a [setPreferred] seam (the
 * recorder's `setPreferredDevice`, which returns false when the device
 * cannot be used). Generic over the device handle so unit tests can drive
 * it with fakes: production passes `AudioDeviceInfo`, tests pass
 * [RawAudioInputDevice].
 *
 * Never fails the caller: when there is no explicit choice, or the chosen
 * device has disconnected, the platform's current routed device (supplied
 * via [routedDevice], e.g. `AudioRecord.getRoutedDevice`) is used if one
 * is available, and otherwise no preference is set so the platform routes
 * the new recording itself. A rejected or throwing `setPreferred` is
 * swallowed (returns the resolved selection with
 * [AppliedPreferredDevice.applied] false) so a recording keeps going —
 * including when the chosen device is disconnected mid-recording, where
 * Android reroutes the live `AudioRecord` itself and there is nothing to
 * do here.
 */
data class AppliedPreferredDevice<T>(
    val selection: AudioInputDeviceSelection,
    val device: T?,
    val applied: Boolean,
)

object PreferredAudioDeviceApplication {
    fun <T> apply(
        selection: AudioInputDeviceSelection,
        devices: List<T>,
        idOf: (T) -> Int,
        typeOf: (T) -> Int,
        setPreferred: (T?) -> Boolean,
        nameOf: (T) -> String? = { null },
        addressOf: (T) -> String = { "" },
        routedDevice: (() -> T?)? = null,
    ): AppliedPreferredDevice<T> {
        fun rawOf(device: T) = RawAudioInputDevice(idOf(device), typeOf(device), nameOf(device), addressOf(device))
        val raws = devices.map(::rawOf)
        var resolved = AudioInputDevicePolicy.resolve(selection, raws)
        var target: T? = if (resolved.isAuto) {
            null
        } else {
            // Prefer the exact persisted port if it is still live (it is a
            // valid handle for the same physical device); otherwise apply
            // the group's representative port.
            devices.firstOrNull { idOf(it) == selection.deviceId && typeOf(it) == selection.deviceType }
                ?: devices.firstOrNull { idOf(it) == resolved.deviceId && typeOf(it) == resolved.deviceType }
        }
        if (target == null && routedDevice != null) {
            val routed = runCatching { routedDevice() }.getOrNull()
            if (routed != null) {
                val routedRaw = rawOf(routed)
                resolved = AudioInputDevicePolicy.resolve(
                    AudioInputDeviceSelection(routedRaw.id, routedRaw.type),
                    raws + routedRaw,
                )
                target = devices.firstOrNull { idOf(it) == routedRaw.id && typeOf(it) == routedRaw.type }
                    ?: devices.firstOrNull {
                        idOf(it) == resolved.deviceId && typeOf(it) == resolved.deviceType
                    }
                    ?: routed
            }
        }
        val applied = runCatching { setPreferred(target) }.getOrDefault(false)
        return AppliedPreferredDevice(resolved, target, applied)
    }
}

/**
 * Enumerates physical input devices via [AudioManager]. This remains the
 * platform enumeration for recording inputs: the API 31+ communication
 * device list (`getAvailableCommunicationDevices`/`setCommunicationDevice`)
 * is scoped to communication mode (`MODE_IN_COMMUNICATION` voice/video
 * calls), not to a CAMCORDER `AudioRecord`, so it does not apply here.
 * Change notification is `AudioDeviceCallback` (used by SettingsPage).
 */
object AudioInputDevices {
    fun enumerateInfos(context: Context): List<AudioDeviceInfo> {
        val manager = context.getSystemService(AudioManager::class.java) ?: return emptyList()
        return runCatching {
            manager.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.isSource }
        }.getOrDefault(emptyList())
    }

    fun enumerate(context: Context): List<RawAudioInputDevice> =
        enumerateInfos(context).map { it.toRaw() }

    fun options(context: Context): List<AudioInputDeviceOption> =
        AudioInputDevicePolicy.options(enumerate(context))

    /** The currently-effective selection (disconnected choice -> no explicit choice). */
    fun resolve(context: Context, selection: AudioInputDeviceSelection): AudioInputDeviceSelection =
        AudioInputDevicePolicy.resolve(selection, enumerate(context))
}

fun AudioDeviceInfo.toRaw(): RawAudioInputDevice = RawAudioInputDevice(
    id = id,
    type = type,
    productName = productName?.toString(),
    address = address,
)
