package com.vayunmathur.communicate.data

import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat

/** The unified repository facade: routes SMS/MMS, Google Voice, WhatsApp, Signal and RCS traffic per line. */
object CommunicateRepository {
    // SIM/SMS+MMS, send dispatch, Google Voice, WhatsApp, Signal, delete routing,
    // polls and shared-contact dispatch live in the CommunicateRepository*.kt
    // split files as extension functions (split for file length); behavior identical.
}

internal fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

internal fun Int.toCommunicateCallType(): CommunicateCallType = when (this) {
    CallLog.Calls.INCOMING_TYPE -> CommunicateCallType.Incoming
    CallLog.Calls.OUTGOING_TYPE -> CommunicateCallType.Outgoing
    CallLog.Calls.MISSED_TYPE -> CommunicateCallType.Missed
    CallLog.Calls.REJECTED_TYPE -> CommunicateCallType.Rejected
    CallLog.Calls.BLOCKED_TYPE -> CommunicateCallType.Blocked
    CallLog.Calls.VOICEMAIL_TYPE -> CommunicateCallType.Voicemail
    else -> CommunicateCallType.Unknown
}
