package com.vayunmathur.communicate.telephony

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract.PhoneLookup
import android.provider.Settings
import android.telecom.PhoneAccount
import com.vayunmathur.communicate.domain.DndCallers
import com.vayunmathur.communicate.domain.DndMode
import com.vayunmathur.communicate.domain.DndPolicy
import com.vayunmathur.communicate.domain.RingConditions
import com.vayunmathur.communicate.domain.RingerMode
import com.vayunmathur.communicate.domain.dndAllowsCall
import com.vayunmathur.library.log.Log

/**
 * What [CallRinger] read from the system for one incoming call.
 *
 * @property customRingtone the caller's contact ringtone (`CUSTOM_RINGTONE`), or null for the default.
 */
internal data class RingEnvironment(
    val conditions: RingConditions,
    val customRingtone: Uri?,
)

/**
 * Reads the ringer switch, ring volume, "vibrate for calls", Do Not Disturb and the caller's contact
 * for [CallRinger]. Every read degrades to the permissive default when the platform refuses it, so a
 * missing permission costs a feature (the contact ringtone) rather than the ring itself.
 */
internal object RingEnvironmentReader {
    private const val TAG = "RingEnvironment"

    /**
     * Android 13 retired `VIBRATE_WHEN_RINGING`: the ring vibration intensity (0 = off) is what the
     * "vibrate for calls" switch writes now, and what Telecom's `SystemSettingsUtil` reads.
     */
    private const val RING_VIBRATION_INTENSITY = "ring_vibration_intensity"

    private class CallerContact(val customRingtone: Uri?, val starred: Boolean)

    fun read(context: Context, request: RingRequest): RingEnvironment {
        val audio = context.getSystemService(AudioManager::class.java)
        val contact = request.number?.let { lookUpContact(context, it) }
        val conditions = RingConditions(
            ringerMode = ringerMode(audio),
            ringVolumeAudible = audio == null || audio.getStreamVolume(AudioManager.STREAM_RING) > 0,
            vibrateWhenRinging = vibrateWhenRinging(context),
            callerAllowedByDnd = callerAllowedByDnd(context, request.number, contact),
            otherCallLive = request.callWaiting,
        )
        return RingEnvironment(conditions, contact?.customRingtone)
    }

    private fun ringerMode(audio: AudioManager?): RingerMode = when (audio?.ringerMode) {
        AudioManager.RINGER_MODE_SILENT -> RingerMode.Silent
        AudioManager.RINGER_MODE_VIBRATE -> RingerMode.Vibrate
        else -> RingerMode.Normal
    }

    private fun vibrateWhenRinging(context: Context): Boolean {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { Settings.System.getInt(resolver, RING_VIBRATION_INTENSITY) }
                .getOrNull()
                ?.let { return it != 0 }
        }
        @Suppress("DEPRECATION")
        val legacyKey = Settings.System.VIBRATE_WHEN_RINGING
        // Unreadable either way: vibrate. On Android 13+ the ringtone-usage vibration is still
        // dropped by the system when the user turned ring vibration off.
        return runCatching { Settings.System.getInt(resolver, legacyKey) != 0 }.getOrDefault(true)
    }

    /**
     * The caller's contact row via `PhoneLookup`, for the custom ringtone and the starred flag. An
     * empty `CUSTOM_RINGTONE` means "default", as in Telephony's `CallerInfo`.
     */
    private fun lookUpContact(context: Context, number: String): CallerContact? = runCatching {
        val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val projection = arrayOf(PhoneLookup.CUSTOM_RINGTONE, PhoneLookup.STARRED)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            CallerContact(
                customRingtone = cursor.getString(0)?.takeIf { it.isNotBlank() }?.let(Uri::parse),
                starred = cursor.getInt(1) != 0,
            )
        }
    }.onFailure {
        Log.status(TAG, "contact lookup for the ringtone failed", it)
    }.getOrNull()

    /**
     * Do Not Disturb for this caller. With a number on Android 13+ the platform decides
     * (`matchesCallFilter`), which is the only check that knows repeat callers; otherwise the active
     * policy is applied to what the contact lookup found.
     */
    private fun callerAllowedByDnd(context: Context, number: String?, contact: CallerContact?): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return true
        if (number != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { nm.matchesCallFilter(Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null)) }
                .onFailure { Log.status(TAG, "matchesCallFilter unavailable; applying the DND policy", it) }
                .getOrNull()
                ?.let { return it }
        }
        return dndAllowsCall(dndPolicy(nm), isContact = contact != null, isStarred = contact?.starred == true)
    }

    private fun dndPolicy(nm: NotificationManager): DndPolicy {
        val mode = when (nm.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> DndMode.PriorityOnly
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> DndMode.AlarmsOnly
            NotificationManager.INTERRUPTION_FILTER_NONE -> DndMode.TotalSilence
            else -> DndMode.Off
        }
        if (mode != DndMode.PriorityOnly) return DndPolicy(mode, callsAllowed = false, callers = DndCallers.None)
        val policy = runCatching { nm.notificationPolicy }.getOrNull()
            ?: return DndPolicy(mode, callsAllowed = false, callers = DndCallers.None)
        val callers = when (policy.priorityCallSenders) {
            NotificationManager.Policy.PRIORITY_SENDERS_ANY -> DndCallers.Anyone
            NotificationManager.Policy.PRIORITY_SENDERS_CONTACTS -> DndCallers.Contacts
            NotificationManager.Policy.PRIORITY_SENDERS_STARRED -> DndCallers.Starred
            else -> DndCallers.None
        }
        val callsAllowed = (policy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_CALLS) != 0
        return DndPolicy(mode, callsAllowed, callers)
    }
}
