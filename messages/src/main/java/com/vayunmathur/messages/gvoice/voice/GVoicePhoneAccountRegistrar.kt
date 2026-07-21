package com.vayunmathur.messages.gvoice.voice

import android.content.ComponentName
import android.content.Context
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log

/**
 * Registers the Google Voice phone account with Android Telecom framework.
 * This enables the app to place and receive calls via the system Telecom API,
 * integrating with the native dialer UI and in-call screen.
 */
object GVoicePhoneAccountRegistrar {

    private const val TAG = "GVoicePhoneAccountRegistrar"
    private const val ACCOUNT_ID = "gvoice_account"

    /**
     * Registers the PhoneAccount with TelecomManager.
     * Call this on app startup or when user enables calling.
     */
    fun register(context: Context) {
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager

        val componentName = ComponentName(context, GVoiceConnectionService::class.java)
        val phoneAccountHandle = PhoneAccountHandle(componentName, ACCOUNT_ID)

        val phoneAccount = PhoneAccount.builder(phoneAccountHandle, "Google Voice")
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
            .build()

        telecomManager.registerPhoneAccount(phoneAccount)
        Log.i(TAG, "PhoneAccount registered: $phoneAccountHandle")
    }

    /**
     * Unregisters the PhoneAccount. Call on app shutdown or when user disables calling.
     */
    fun unregister(context: Context) {
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val componentName = ComponentName(context, GVoiceConnectionService::class.java)
        val phoneAccountHandle = PhoneAccountHandle(componentName, ACCOUNT_ID)

        telecomManager.unregisterPhoneAccount(phoneAccountHandle)
        Log.i(TAG, "PhoneAccount unregistered: $phoneAccountHandle")
    }

    /**
     * Returns the PhoneAccountHandle for this app's Google Voice account.
     */
    fun getPhoneAccountHandle(context: Context): PhoneAccountHandle {
        val componentName = ComponentName(context, GVoiceConnectionService::class.java)
        return PhoneAccountHandle(componentName, ACCOUNT_ID)
    }
}
