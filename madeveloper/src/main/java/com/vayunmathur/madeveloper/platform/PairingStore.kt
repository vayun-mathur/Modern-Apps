package com.vayunmathur.madeveloper.platform

import android.content.Context
import androidx.core.content.edit
import com.vayunmathur.madeveloper.domain.DevProtocol

/** Persists the pairing code and the server-issued push token for this device. */
class PairingStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The pairing code shown until the server pairs this device. */
    fun pairingCode(): String = prefs.getString(KEY_CODE, null) ?: DevProtocol.newPairingCode().also {
        prefs.edit { putString(KEY_CODE, it) }
    }

    /** The durable push token, or null while unpaired. */
    fun token(): String? = prefs.getString(KEY_TOKEN, null)

    /** The server-issued device id, or null while unpaired. */
    fun deviceId(): String? = prefs.getString(KEY_DEVICE, null)

    /** Stores the credentials from a paired message. */
    fun savePaired(token: String, deviceId: String) {
        prefs.edit { putString(KEY_TOKEN, token); putString(KEY_DEVICE, deviceId) }
    }

    /** Clears pairing so a new code is generated on next launch. */
    fun clear() { prefs.edit { clear() } }

    private companion object {
        const val PREFS = "dev_push"
        const val KEY_CODE = "pairing_code"
        const val KEY_TOKEN = "token"
        const val KEY_DEVICE = "device_id"
    }
}
