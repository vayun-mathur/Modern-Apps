package com.vayunmathur.emergency.platform

import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.telephony.emergency.EmergencyNumber
import com.vayunmathur.library.log.Log
import androidx.core.content.getSystemService

private const val TAG = "EmergencyNumberLookup"

/**
 * Picks the number the SOS countdown is about to dial.
 *
 * Mirrors `EmergencyNumberUtils.getPoliceNumber`: the user override (see [GestureProvider])
 * wins; otherwise the first police-category number from the SIM emergency-number list
 * (database-sourced entries preferred); otherwise the `112` fallback. Needs
 * `READ_PHONE_STATE` for `getEmergencyNumberList` - without the grant (or without
 * telephony at all) this returns the fallback rather than failing.
 */
class EmergencyNumberLookup(private val context: Context) {

    fun policeNumber(override: String?): String {
        if (!override.isNullOrBlank()) return override
        return defaultPoliceNumber()
    }

    fun defaultPoliceNumber(): String {
        val numbers = readPoliceNumbers() ?: return FALLBACK_NUMBER
        if (numbers.isEmpty()) return FALLBACK_NUMBER
        // Database-sourced entries are well-categorised; prefer them like SettingsLib does.
        val preferred = numbers.firstOrNull {
            EmergencyNumber.EMERGENCY_NUMBER_SOURCE_DATABASE in it.emergencyNumberSources
        } ?: numbers.first()
        return preferred.number.ifBlank { FALLBACK_NUMBER }
    }

    private fun readPoliceNumbers(): List<EmergencyNumber>? {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            return null
        }
        if (!hasPhoneStateGrant()) return null
        val telecom = context.getSystemService<TelephonyManager>() ?: return null
        val lists = runCatching {
            telecom.getEmergencyNumberList(EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_POLICE)
        }.getOrElse {
            Log.status(TAG, "could not read the emergency-number list", it)
            return null
        }
        if (lists.isNullOrEmpty()) return null
        val subId = SubscriptionManager.getDefaultSubscriptionId()
        return lists[subId] ?: lists.values.firstOrNull().orEmpty()
    }

    private fun hasPhoneStateGrant(): Boolean {
        val result = context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)
        return result == PackageManager.PERMISSION_GRANTED
    }

    /** Shared fallback emergency number for [EmergencyNumberLookup]. */
    companion object {
        /** SettingsLib `FALL_BACK_NUMBER`: the GSM emergency number, reachable everywhere. */
        const val FALLBACK_NUMBER = "112"
    }
}
