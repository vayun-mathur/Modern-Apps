package com.vayunmathur.madeveloper.platform

import android.content.Context
import androidx.core.content.edit

/**
 * Persists the server-issued pairing credentials for this device.
 *
 * Registration (`POST /dev/register`) is the only source of a pairing code:
 * older builds generated a code locally and stored it under the same key
 * without a token, so a stored code with no token is stale and the caller
 * registers fresh, overwriting it.
 */
class PairingStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The server-issued pairing code, or null while this device is unregistered. */
    fun pairingCode(): String? = prefs.getString(KEY_CODE, null)?.takeIf { it.isNotBlank() }

    /** The durable push token, or null while unregistered. */
    fun token(): String? = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }

    /** The server-issued device id, or null while unregistered. */
    fun deviceId(): String? = prefs.getString(KEY_DEVICE, null)?.takeIf { it.isNotBlank() }

    /** Stores the credentials returned by `/dev/register`. */
    fun saveRegistration(deviceId: String, pushToken: String, pairingCode: String) {
        prefs.edit {
            putString(KEY_CODE, pairingCode)
            putString(KEY_TOKEN, pushToken)
            putString(KEY_DEVICE, deviceId)
        }
    }

    /** Clears pairing so the device registers fresh on next launch. */
    fun clear() { prefs.edit { clear() } }

    private companion object {
        const val PREFS = "dev_push"
        const val KEY_CODE = "pairing_code"
        const val KEY_TOKEN = "token"
        const val KEY_DEVICE = "device_id"
    }
}
