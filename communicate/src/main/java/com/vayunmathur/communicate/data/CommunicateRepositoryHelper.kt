package com.vayunmathur.communicate.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.TelecomManager
import com.vayunmathur.communicate.data.signal.SignalClient
import com.vayunmathur.communicate.data.signal.placeCall
import com.vayunmathur.communicate.data.signal.placeGroupCall
import com.vayunmathur.library.ui.ExternalIntents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun CommunicateRepository.placeCall(context: Context, number: String) {
    placeCall(context, choice = null, number = number)
}

/**
 * Place a call from a chosen line. Google Voice routes through the self-managed account; a SIM
 * choice places via that SIM's [PhoneAccountHandle]; null uses the default outgoing account.
 */
fun CommunicateRepository.placeCall(context: Context, choice: LineChoice?, number: String) {
    if (number.isBlank()) return
    if (choice is LineChoice.GoogleVoice) {
        com.vayunmathur.communicate.telephony.GoogleVoiceTelecom.placeOutgoing(context, number)
        return
    }
    val uri = Uri.fromParts("tel", number, null)
    if (context.hasPermission(Manifest.permission.CALL_PHONE)) {
        try {
            val telecomManager = context.getSystemService(TelecomManager::class.java)
            val extras = Bundle()
            val handle = (choice as? LineChoice.Sim)?.let { phoneAccountHandleForSub(context, it.subscriptionId) }
                ?: telecomManager.getDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL)
            handle?.let { extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
            telecomManager.placeCall(uri, extras)
            return
        } catch (_: SecurityException) {
            // CALL_PHONE was revoked between the check above and the call:
            // fall through to ACTION_DIAL below.
        } catch (_: Exception) {
            // Fall through to ACTION_DIAL below.
        }
    }
    ExternalIntents.launch(context, Intent(Intent.ACTION_DIAL, uri))
}

/** Virtual (network-backed) lines that don't map to a physical SIM subscription. */
val CommunicateRepository.isVirtualLine get() = setOf(
    CommunicateLine.GoogleVoice,
    CommunicateLine.WhatsApp,
    CommunicateLine.Signal,
    CommunicateLine.Rcs)

/**
 * Place a voice call on [line] for a conversation.
 *
 * SIM and Google Voice go through Telecom; WhatsApp and Signal are in-app WebRTC calls addressed by
 * conversation id. Returns false when the line cannot place the call, so the caller can say so rather
 * than appear to succeed.
 */
suspend fun CommunicateRepository.placeCallForLine(
    context: Context,
    line: CommunicateLine,
    address: String,
    remoteId: String?,
    video: Boolean = false,
): Boolean = when (line) {
    CommunicateLine.Sim -> {
        placeCall(context, choice = null, number = address)
        true
    }
    CommunicateLine.GoogleVoice -> {
        placeCall(context, choice = LineChoice.GoogleVoice, number = address)
        true
    }
    CommunicateLine.WhatsApp -> {
        val target = remoteId ?: address
        if (target.isBlank()) false else { whatsAppPlaceCall(target, video); true }
    }
    CommunicateLine.Signal -> withContext(Dispatchers.IO) {
        // A group call goes to the SFU rather than to a single peer.
        if (remoteId != null && com.vayunmathur.communicate.data.signal.SignalProtocol.isGroupConversation(remoteId)) {
            return@withContext SignalClient.get(context).placeGroupCall(remoteId)
        }
        val target = remoteId?.takeIf { it.isNotBlank() } ?: toSignalRecipient(context, address)
        if (target.isBlank()) {
            false
        } else {
            SignalClient.get(context).placeCall(target, video)
            true
        }
    }
    // RCS is messaging-only in v1: no telephony or WebRTC call path.
    CommunicateLine.Rcs -> false
}

/** Whether [line] can place a call at all, so the UI can hide the affordance instead of failing. */
fun CommunicateRepository.canPlaceCall(line: CommunicateLine): Boolean = when (line) {
    CommunicateLine.Sim -> true
    CommunicateLine.GoogleVoice -> true
    CommunicateLine.WhatsApp -> com.vayunmathur.communicate.data.whatsapp.WhatsAppFeature.enabled
    CommunicateLine.Signal -> com.vayunmathur.communicate.data.signal.SignalFeature.enabled
    // RCS is messaging-only in v1.
    CommunicateLine.Rcs -> false
}

/**
 * Whether [line] can place a **video** call. Only the in-app WebRTC lines can; SIM and Google Voice hand
 * the call to Telecom, which carries audio only.
 */
fun CommunicateRepository.canPlaceVideoCall(line: CommunicateLine): Boolean = when (line) {
    CommunicateLine.WhatsApp -> com.vayunmathur.communicate.data.whatsapp.WhatsAppFeature.enabled
    CommunicateLine.Signal -> com.vayunmathur.communicate.data.signal.SignalFeature.enabled
    else -> false
}

/** Only Signal has group calling; it routes through the SFU rather than to individual peers. */
fun CommunicateRepository.canPlaceGroupCall(line: CommunicateLine): Boolean =
    line == CommunicateLine.Signal && com.vayunmathur.communicate.data.signal.SignalFeature.enabled
