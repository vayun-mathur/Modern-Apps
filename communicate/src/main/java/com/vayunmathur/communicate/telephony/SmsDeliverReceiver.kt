package com.vayunmathur.communicate.telephony

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.vayunmathur.library.log.Log
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.notifications.ConversationSpace
import com.vayunmathur.communicate.notifications.ConversationTarget

/**
 * Handles inbound SMS as the default SMS app. Android delivers `SMS_DELIVER` only to the default
 * SMS app, which is then solely responsible for writing the message into the provider — nothing
 * else persists it, so dropping the broadcast loses the message entirely.
 *
 * Multipart messages arrive as several PDUs in one broadcast and are concatenated into a single row,
 * matching how [Telephony.Sms] is read back by the repository.
 */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val parts = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }
            .getOrNull()?.takeIf { it.isNotEmpty() } ?: run {
            Log.status(TAG, "SMS_DELIVER with no parsable messages")
            return
        }
        // Present for multi-SIM; absent on single-SIM devices.
        val subscriptionId = intent.getIntExtra("subscription", -1)
        val pending = goAsync()
        Thread {
            runCatching { insertInboundSms(context, parts, subscriptionId) }
                .onFailure { Log.error(TAG, "failed to store inbound SMS", it) }
            pending.finish()
        }.start()
    }

    private fun insertInboundSms(
        context: Context,
        parts: Array<android.telephony.SmsMessage>,
        subscriptionId: Int,
    ) {
        val first = parts.first()
        val address = first.displayOriginatingAddress ?: first.originatingAddress
        val body = parts.joinToString("") { it.displayMessageBody.orEmpty() }
        val threadId = address?.let {
            runCatching { Telephony.Threads.getOrCreateThreadId(context, it) }.getOrNull()
        }
        val values = ContentValues().apply {
            if (threadId != null) put(Telephony.Sms.THREAD_ID, threadId)
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            // DATE is receipt time; DATE_SENT is the timestamp the sender's network stamped.
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.DATE_SENT, first.timestampMillis)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.SEEN, 0)
            put(Telephony.Sms.PROTOCOL, first.protocolIdentifier)
            put(Telephony.Sms.REPLY_PATH_PRESENT, if (first.isReplyPathPresent) 1 else 0)
            first.serviceCenterAddress?.let { put(Telephony.Sms.SERVICE_CENTER, it) }
            if (subscriptionId >= 0) put(Telephony.Sms.SUBSCRIPTION_ID, subscriptionId)
        }
        context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
        Log.dev(TAG, "stored inbound SMS parts=${parts.size} thread=$threadId")

        if (address != null) {
            ConversationSpace.ensureIncomingChannel(
                context,
                ConversationSpace.SIM_CHANNEL_ID,
                context.getString(R.string.sms_incoming_channel_name),
                context.getString(R.string.sms_incoming_channel_desc),
            )
            ConversationSpace.notifyIncoming(
                context = context,
                target = ConversationTarget(
                    line = CommunicateLine.Sim,
                    address = address,
                    threadId = threadId ?: -1L,
                    personName = address,
                    subscriptionId = if (subscriptionId >= 0) subscriptionId else null,
                ),
                channelId = ConversationSpace.SIM_CHANNEL_ID,
                body = body.ifBlank { context.getString(R.string.new_message) },
                timestamp = System.currentTimeMillis(),
                smallIcon = R.mipmap.ic_launcher,
            )
        }
    }

    /** Logging tag for [SmsDeliverReceiver]. */
    companion object {
        private const val TAG = "SmsDeliverReceiver"
    }
}
