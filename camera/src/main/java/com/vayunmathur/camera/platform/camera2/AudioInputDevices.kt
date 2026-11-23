package com.vayunmathur.camera.platform.camera2

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * A user-chosen physical audio input device (built-in mic, USB mic,
 * Bluetooth headset mic, ...), as opposed to a `MediaRecorder.AudioSource`
 * type. The internal audio source for video recording is always CAMCORDER;
 * this selection only steers which physical device feeds it.
 *
 * Persisted by device id + type: ids can be reused after a disconnect, so
 * the type is kept alongside as a sanity match. [AUTO] means "let the
 * system route" (no preferred device is set on the recorder).
 */
data class AudioInputDeviceSelection(val deviceId: Int? = null, val deviceType: Int? = null) {
    val isAuto: Boolean get() = deviceId == null

    /** Storage form: `auto` or `<id>:<type>`. */
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
data class RawAudioInputDevice(val id: Int, val type: Int, val productName: String?)

/** One row in the input-device picker: Auto first, then connected devices. */
data class AudioInputDeviceOption(
    val selection: AudioInputDeviceSelection,
    val label: String,
    val description: String? = null,
)

object AudioInputDevicePolicy {

    const val AUTO_LABEL = "Auto"
    const val AUTO_DESCRIPTION = "System default"

    /** Display label: the product name when the device reports one, else its type. */
    fun labelFor(type: Int, productName: String?): String =
        productName?.takeIf { it.isNotBlank() } ?: typeLabel(type)

    fun typeLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in microphone"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB device"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth headset"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE headset"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_TELEPHONY -> "Telephony"
        AudioDeviceInfo.TYPE_FM_TUNER -> "FM tuner"
        else -> "Microphone"
    }

    /** Picker rows: Auto (system routing) plus each connected input device. */
    fun options(devices: List<RawAudioInputDevice>): List<AudioInputDeviceOption> =
        listOf(
            AudioInputDeviceOption(
                AudioInputDeviceSelection.AUTO, AUTO_LABEL, AUTO_DESCRIPTION,
            ),
        ) + devices.map { device ->
            val type = typeLabel(device.type)
            val label = labelFor(device.type, device.productName)
            AudioInputDeviceOption(
                selection = AudioInputDeviceSelection(device.id, device.type),
                label = label,
                description = type.takeIf { it != label },
            )
        }

    /**
     * The effective selection given what is currently connected: Auto stays
     * Auto, a connected chosen device (id + type match) stays chosen, and a
     * disconnected chosen device silently falls back to Auto.
     */
    fun resolve(
        selection: AudioInputDeviceSelection,
        devices: List<RawAudioInputDevice>,
    ): AudioInputDeviceSelection {
        if (selection.isAuto) return AudioInputDeviceSelection.AUTO
        val present = devices.any { it.id == selection.deviceId && it.type == selection.deviceType }
        return if (present) selection else AudioInputDeviceSelection.AUTO
    }
}

/**
 * Applies a selection to a recorder through a [setPreferred] seam (the
 * recorder's `setPreferredDevice`, which returns false when the device
 * cannot be used). Generic over the device handle so unit tests can drive
 * it with fakes: production passes `AudioDeviceInfo`, tests pass
 * [RawAudioInputDevice].
 *
 * Never fails the caller: an unknown/disconnected device resolves to Auto,
 * and a rejected or throwing `setPreferred` is swallowed (returns the
 * resolved selection with [AppliedPreferredDevice.applied] false) so a
 * recording keeps going on the system-routed device — including when the
 * chosen device is disconnected mid-recording, where Android reroutes the
 * live `AudioRecord` itself and there is nothing to do here.
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
    ): AppliedPreferredDevice<T> {
        val resolved = AudioInputDevicePolicy.resolve(
            selection,
            devices.map { RawAudioInputDevice(idOf(it), typeOf(it), null) },
        )
        val target = if (resolved.isAuto) {
            null
        } else {
            devices.firstOrNull { idOf(it) == resolved.deviceId && typeOf(it) == resolved.deviceType }
        }
        val applied = runCatching { setPreferred(target) }.getOrDefault(false)
        return AppliedPreferredDevice(resolved, target, applied)
    }
}

/** Enumerates physical input devices via [AudioManager]. */
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

    /** The currently-effective selection (disconnected choice -> Auto). */
    fun resolve(context: Context, selection: AudioInputDeviceSelection): AudioInputDeviceSelection =
        AudioInputDevicePolicy.resolve(selection, enumerate(context))
}

fun AudioDeviceInfo.toRaw(): RawAudioInputDevice =
    RawAudioInputDevice(id = id, type = type, productName = productName?.toString())
