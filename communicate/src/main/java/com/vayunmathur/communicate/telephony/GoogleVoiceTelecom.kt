package com.vayunmathur.communicate.telephony

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager

/**
 * Registers Google Voice as a **self-managed** [PhoneAccount] so the OS treats it like another
 * SIM/line: it becomes selectable for outgoing calls and gets system call-audio focus and,
 * for inbound, the incoming-call surface — all routed to [GoogleVoiceConnectionService].
 */
object GoogleVoiceTelecom {

    private const val ACCOUNT_ID = "google_voice_line"

    fun handle(context: Context): PhoneAccountHandle =
        PhoneAccountHandle(
            ComponentName(context.applicationContext, GoogleVoiceConnectionService::class.java),
            ACCOUNT_ID,
        )

    /** Register (or refresh) the self-managed account, labelled with the GV number. */
    fun registerPhoneAccount(context: Context, label: String) {
        val tm = context.getSystemService(TelecomManager::class.java) ?: return
        val account = PhoneAccount.builder(handle(context), label)
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
            .addSupportedUriScheme(PhoneAccount.SCHEME_TEL)
            .addSupportedUriScheme(PhoneAccount.SCHEME_SIP)
            .build()
        runCatching { tm.registerPhoneAccount(account) }
    }

    fun unregisterPhoneAccount(context: Context) {
        val tm = context.getSystemService(TelecomManager::class.java) ?: return
        runCatching { tm.unregisterPhoneAccount(handle(context)) }
    }

    /** Place an outgoing GV call through Telecom so it routes to our ConnectionService. */
    fun placeOutgoing(context: Context, number: String) {
        val tm = context.getSystemService(TelecomManager::class.java) ?: return
        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle(context))
        }
        val uri = Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null)
        try {
            tm.placeCall(uri, extras)
        } catch (_: SecurityException) {
            // Self-managed account not registered yet or CALL_PHONE revoked:
            // the Telecom bind simply does not happen.
        }
    }

    /** Surface an inbound SIP INVITE to the system as an incoming self-managed call. */
    fun addIncoming(context: Context, from: String) {
        val tm = context.getSystemService(TelecomManager::class.java) ?: return
        val extras = Bundle().apply {
            putParcelable(
                TelecomManager.EXTRA_INCOMING_CALL_ADDRESS,
                Uri.fromParts(PhoneAccount.SCHEME_TEL, from, null),
            )
        }
        runCatching { tm.addNewIncomingCall(handle(context), extras) }
    }
}
