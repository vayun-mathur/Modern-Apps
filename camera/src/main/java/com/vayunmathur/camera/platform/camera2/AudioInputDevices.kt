package com.vayunmathur.camera.platform.camera2

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.vayunmathur.library.log.Log
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * A user-chosen physical audio input device (built-in mic, USB mic,
 * Bluetooth headset mic, ...), as opposed to a `MediaRecorder.AudioSource`
 * type. The internal audio source for video recording is always CAMCORDER;
 * this selection only steers which physical device feeds it, through
 * `AudioRecord.setPreferredDevice` (see [PreferredAudioDeviceApplication]).
 *
 * Persisted by port id + type plus the endpoint identity (product name and
 * address) of its physical group: port ids are runtime-assigned and can be
 * reused or renumbered after a disconnect/reboot, so a stored pick is
 * re-resolved against the live enumeration by endpoint identity when its
 * exact port is gone (see [AudioInputDevicePolicy.resolve]). [AUTO] is only
 * the internal "no explicit device chosen" sentinel (and the parse result
 * for legacy stored values); it is never a picker row — the picker lists
 * only real devices returned by the platform (see
 * [AudioInputDevicePolicy.options]), and no explicit choice is shown by no
 * row being selected.
 */
data class AudioInputDeviceSelection(
    val deviceId: Int? = null,
    val deviceType: Int? = null,
    val productName: String? = null,
    val address: String = "",
) {
    val isAuto: Boolean get() = deviceId == null

    /**
     * Storage form: `auto` (no explicit choice), `<id>:<type>` (legacy), or
     * `<id>:<type>:<url-encoded name>:<url-encoded address>`.
     */
    fun encode(): String {
        if (isAuto) return ENCODED_AUTO
        val name = productName?.trim().orEmpty()
        val addr = address.trim()
        return if (name.isEmpty() && addr.isEmpty()) {
            "$deviceId:$deviceType"
        } else {
            "$deviceId:$deviceType:${encodePart(name)}:${encodePart(addr)}"
        }
    }

    companion object {
        val AUTO = AudioInputDeviceSelection()
        private const val ENCODED_AUTO = "auto"

        private fun encodePart(value: String): String =
            URLEncoder.encode(value, Charsets.UTF_8.name())

        private fun decodePart(value: String): String =
            runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrDefault(value)

        /**
         * Parses a stored value. Anything that is not a well-formed
         * `<id>:<type>` pair — including stale values from the retired
         * AudioSource-type setting (`CAMCORDER`, `MIC`, ...) — falls back
         * to [AUTO] instead of crashing.
         */
        fun parse(stored: String?): AudioInputDeviceSelection {
            if (stored.isNullOrBlank() || stored == ENCODED_AUTO) return AUTO
            val parts = stored.split(":")
            if (parts.size != 2 && parts.size != 4) return AUTO
            val id = parts[0].toIntOrNull() ?: return AUTO
            val type = parts[1].toIntOrNull() ?: return AUTO
            if (parts.size == 2) return AudioInputDeviceSelection(id, type)
            return AudioInputDeviceSelection(
                id,
                type,
                decodePart(parts[2]).takeIf { it.isNotBlank() },
                decodePart(parts[3]),
            )
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
     * Endpoint family for physical grouping. One physical endpoint can be
     * reported under several port types (a Bluetooth headset as SCO and
     * BLE, a USB headset as a generic USB device and a USB headset); types
     * within one family can be ports of the same endpoint, types across
     * families cannot.
     */
    private enum class EndpointFamily { BLUETOOTH, USB, WIRED, BUILTIN, OTHER }

    private fun familyOf(type: Int): EndpointFamily = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> EndpointFamily.BLUETOOTH
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> EndpointFamily.USB
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> EndpointFamily.WIRED
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> EndpointFamily.BUILTIN
        else -> EndpointFamily.OTHER
    }

    private fun normalizedName(device: RawAudioInputDevice): String =
        device.productName?.trim().orEmpty()

    /**
     * Whether [device] can be a port of the physical endpoint [group]
     * already holds. A non-empty address identifies an endpoint on its own
     * (ports of one endpoint share it), so conflicting addresses veto a
     * merge; without a conflict, a shared address merges, and otherwise a
     * shared non-empty product name merges ports of one endpoint family —
     * including the common case where only some of an endpoint's ports
     * carry an address (e.g. a Bluetooth SCO port reports the MAC while
     * its BLE port reports none). Unnamed, addressless ports can only be
     * told apart by type, so they merge only with same-type ports that are
     * equally anonymous.
     */
    private fun canMerge(device: RawAudioInputDevice, group: List<RawAudioInputDevice>): Boolean {
        val deviceAddress = device.address.trim()
        val groupAddresses = group.map { it.address.trim() }.filter { it.isNotEmpty() }.toSet()
        if (deviceAddress.isNotEmpty() && groupAddresses.isNotEmpty() && deviceAddress !in groupAddresses) {
            return false
        }
        if (deviceAddress.isNotEmpty() && deviceAddress in groupAddresses) return true
        val deviceName = normalizedName(device)
        if (deviceName.isNotEmpty()) {
            return group.any {
                normalizedName(it) == deviceName && familyOf(it.type) == familyOf(device.type)
            }
        }
        if (deviceAddress.isEmpty()) {
            return group.any {
                it.type == device.type && normalizedName(it).isEmpty() && it.address.trim().isEmpty()
            }
        }
        return false
    }

    /** Groups raw ports into one list per physical device, in first-seen order. */
    fun groupByPhysicalDevice(
        devices: List<RawAudioInputDevice>,
    ): List<List<RawAudioInputDevice>> {
        val groups = mutableListOf<MutableList<RawAudioInputDevice>>()
        for (device in devices) {
            val matching = groups.indices.filter { canMerge(device, groups[it]) }
            if (matching.isEmpty()) {
                groups += mutableListOf(device)
            } else {
                val target = groups[matching.first()]
                target += device
                // A port bridging two groups (e.g. an addressed port whose
                // name also matches an addressless group) unites them.
                for (index in matching.drop(1).sortedDescending()) {
                    target += groups[index]
                    groups.removeAt(index)
                }
            }
        }
        return groups.map { it.toList() }
    }

    /** The picker/record selection for one physical group. */
    fun selectionFor(group: List<RawAudioInputDevice>): AudioInputDeviceSelection {
        val representative = representativeOf(group)
        val name = representative.productName?.takeIf { it.isNotBlank() }
            ?: group.firstOrNull { !it.productName.isNullOrBlank() }?.productName
        val address = group.firstOrNull { it.address.isNotBlank() }?.address?.trim().orEmpty()
        return AudioInputDeviceSelection(representative.id, representative.type, name, address)
    }

    private fun bestName(group: List<RawAudioInputDevice>): String? =
        group.firstOrNull { !it.productName.isNullOrBlank() }?.productName?.trim()

    private fun groupAddress(group: List<RawAudioInputDevice>): String =
        group.firstOrNull { it.address.isNotBlank() }?.address?.trim().orEmpty()

    /**
     * Picker rows: exactly one per physical device (no synthetic rows).
     * Each row's selection is its group's representative entry (the most
     * specific port type), and its label is the endpoint's product name —
     * never prefixed with an id or a count — falling back to the friendly
     * type label when no port in the group reports a meaningful name. When
     * two distinct endpoints share one label, the description carries the
     * endpoint address so the rows stay distinguishable.
     */
    fun options(devices: List<RawAudioInputDevice>): List<AudioInputDeviceOption> {
        val groups = groupByPhysicalDevice(devices)
        val built = groups.map { group ->
            val representative = representativeOf(group)
            val label = labelFor(
                representative.type,
                representative.productName?.takeIf { it.isNotBlank() }
                    ?: group.firstOrNull { !it.productName.isNullOrBlank() }?.productName,
            )
            AudioInputDeviceOption(
                selection = selectionFor(group),
                label = label,
                description = typeLabel(representative.type).takeIf { it != label },
            )
        }
        val labelCounts = built.groupingBy { it.label }.eachCount()
        return built.map { option ->
            if ((labelCounts[option.label] ?: 0) < 2) return@map option
            val hint = option.selection.address.takeIf { it.isNotEmpty() }
                ?: option.selection.deviceId?.let { "device $it" }
                ?: return@map option
            option.copy(
                description = listOfNotNull(option.description, hint).joinToString(" · "),
            )
        }
    }

    /**
     * The effective selection given what is currently connected. A chosen
     * device stays chosen through its physical group: a stored selection
     * naming any port of the device (e.g. a non-representative duplicate
     * written before grouping) resolves to the group's representative,
     * rather than demanding that exact port. When the exact port is gone
     * (ids are runtime-assigned and churn), the stored endpoint identity
     * (address, else name within its family, with no conflicting address)
     * re-resolves to the same physical device under its new port. A device
     * with no live port yields [AUTO] (no explicit choice), and the
     * record-time system route takes over.
     */
    fun resolve(
        selection: AudioInputDeviceSelection,
        devices: List<RawAudioInputDevice>,
    ): AudioInputDeviceSelection {
        if (selection.isAuto) return AudioInputDeviceSelection.AUTO
        val groups = groupByPhysicalDevice(devices)
        val exactGroup = groups.firstOrNull { group ->
            group.any { it.id == selection.deviceId && it.type == selection.deviceType }
        }
        if (exactGroup != null) return selectionFor(exactGroup)
        val wantedAddress = selection.address.trim()
        val wantedName = selection.productName?.trim().orEmpty()
        if (wantedAddress.isEmpty() && wantedName.isEmpty()) {
            return AudioInputDeviceSelection.AUTO
        }
        val candidates = groups.filter { group ->
            val address = groupAddress(group)
            val addressConflict =
                wantedAddress.isNotEmpty() && address.isNotEmpty() && address != wantedAddress
            when {
                addressConflict -> false
                wantedAddress.isNotEmpty() && address == wantedAddress -> true
                else -> wantedName.isNotEmpty() && bestName(group) == wantedName &&
                    group.any { familyOf(it.type) == familyOf(selection.deviceType ?: -1) }
            }
        }
        return candidates.singleOrNull()?.let { selectionFor(it) }
            ?: AudioInputDeviceSelection.AUTO
    }

    /**
     * Whether [routed] (e.g. `AudioRecord.getRoutedDevice()` after recording
     * starts) is a port of the physical device [selection] names. Used for
     * an honest readback of whether a pick actually stuck: the setter
     * accepting a device does not by itself prove the route followed it.
     */
    fun isRoutedTo(
        selection: AudioInputDeviceSelection,
        devices: List<RawAudioInputDevice>,
        routed: RawAudioInputDevice,
    ): Boolean {
        val resolved = resolve(selection, devices)
        if (resolved.isAuto) return false
        val all = if (devices.any { it.id == routed.id && it.type == routed.type }) {
            devices
        } else {
            devices + routed
        }
        val routedGroup = groupByPhysicalDevice(all).firstOrNull { group ->
            group.any { it.id == routed.id && it.type == routed.type }
        } ?: return false
        return selectionFor(routedGroup) == resolved
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
 * device has disconnected, no preference is set (a null device clears any
 * preference) so the platform routes the new recording itself — absence of
 * a pick *is* the system default, never a substituted device. A rejected
 * or throwing `setPreferred` is swallowed (returns the resolved selection
 * with [AppliedPreferredDevice.applied] false) so a recording keeps going —
 * including when the chosen device is disconnected mid-recording, where
 * Android reroutes the live `AudioRecord` itself and there is nothing to
 * do here. Whether the route actually followed an applied preference is
 * verified separately against the routed device once recording starts
 * (see [AudioInputDevicePolicy.isRoutedTo]).
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
    ): AppliedPreferredDevice<T> {
        fun rawOf(device: T) = RawAudioInputDevice(idOf(device), typeOf(device), nameOf(device), addressOf(device))
        val raws = devices.map(::rawOf)
        val resolved = AudioInputDevicePolicy.resolve(selection, raws)
        val target: T? = if (resolved.isAuto) {
            null
        } else {
            // Prefer the exact persisted port if it is still live (it is a
            // valid handle for the same physical device); otherwise apply
            // the group's representative port.
            devices.firstOrNull { idOf(it) == selection.deviceId && typeOf(it) == selection.deviceType }
                ?: devices.firstOrNull { idOf(it) == resolved.deviceId && typeOf(it) == resolved.deviceType }
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

    fun options(context: Context): List<AudioInputDeviceOption> {
        val raw = enumerate(context)
        val options = AudioInputDevicePolicy.options(raw)
        // Dev breadcrumb: the raw ports the platform enumerates vs the one
        // row per physical device the picker shows, so a wrong list can be
        // compared against the hardware from logcat alone.
        Log.dev(
            "AudioInputDevices",
            "raw=[${raw.joinToString("; ") {
                "id=${it.id} type=${it.type} name=${it.productName} address=${it.address}"
            }}] grouped=[${options.joinToString("; ") {
                "id=${it.selection.deviceId} type=${it.selection.deviceType} label=${it.label}"
            }}]",
        )
        return options
    }

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
