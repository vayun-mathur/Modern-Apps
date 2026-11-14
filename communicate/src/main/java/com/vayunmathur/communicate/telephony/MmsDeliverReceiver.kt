package com.vayunmathur.communicate.telephony

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager
import com.vayunmathur.library.log.Log
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.notifications.ConversationSpace
import com.vayunmathur.communicate.notifications.ConversationTarget
import java.io.File

/**
 * Handles inbound MMS (WAP push) as the default SMS app: parse the `M-Notification.ind`, download
 * the full message via [SmsManager.downloadMultimediaMessage], then parse the retrieved
 * `M-Retrieve.conf` and insert it (sender + text + media parts) into the MMS provider so it shows in
 * the (group) thread. Best-effort / carrier-dependent — see the plan caveat.
 */
class MmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION -> handlePush(context, intent)
            ACTION_MMS_DOWNLOADED -> handleDownloaded(context, intent)
            else -> Unit
        }
    }

    private fun handlePush(context: Context, intent: Intent) {
        val pdu = intent.getByteArrayExtra("data") ?: return
        runCatching {
            val notif = MmsPduReader.parseNotification(pdu)
            val location = notif.contentLocation ?: run {
                Log.status(TAG, "MMS push had no content-location"); return
            }
            val dir = File(context.cacheDir, "mms").apply { mkdirs() }
            val file = File(dir, "in_${System.currentTimeMillis()}.pdu")
            file.createNewFile()
            val contentUri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.mmsfileprovider", file,
            )
            // The system MMS service (phone uid) writes the downloaded PDU into our file.
            context.grantUriPermission(
                "com.android.mms.service", contentUri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            val done = Intent(ACTION_MMS_DOWNLOADED).apply {
                setClass(context, MmsDeliverReceiver::class.java)
                putExtra(EXTRA_FILE, file.absolutePath)
                putExtra(EXTRA_LOCATION, location)
            }
            val pi = PendingIntent.getBroadcast(
                context, location.hashCode(), done,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            val sms = context.getSystemService(SmsManager::class.java) ?: run {
                Log.status(TAG, "no SmsManager"); return
            }
            sms.downloadMultimediaMessage(context, location, contentUri, null, pi)
            Log.dev(TAG, "MMS download triggered for $location")
        }.onFailure { Log.error(TAG, "handlePush failed", it) }
    }

    private fun handleDownloaded(context: Context, intent: Intent) {
        val path = intent.getStringExtra(EXTRA_FILE) ?: return
        val pending = goAsync()
        Thread {
            runCatching {
                val bytes = File(path).readBytes()
                val msg = MmsPduReader.parseRetrieved(bytes)
                insertInboundMms(context, msg)
                Log.dev(TAG, "MMS stored from=${msg.from} parts=${msg.parts.size}")
            }.onFailure { Log.error(TAG, "handleDownloaded failed", it) }
            runCatching { File(path).delete() }
            pending.finish()
        }.start()
    }

    private fun insertInboundMms(context: Context, msg: MmsPduReader.Retrieved) {
        val from = msg.from
        val threadId = from?.let {
            runCatching { Telephony.Threads.getOrCreateThreadId(context, it) }.getOrNull()
        }
        val values = ContentValues().apply {
            if (threadId != null) put(Telephony.Mms.THREAD_ID, threadId)
            put(Telephony.Mms.DATE, msg.dateSeconds)
            put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_INBOX)
            put(Telephony.Mms.READ, 0)
            put(Telephony.Mms.SEEN, 0)
            put(Telephony.Mms.MESSAGE_TYPE, MMS_TYPE_RETRIEVE_CONF) // M-Retrieve.conf
            msg.subject?.let { put(Telephony.Mms.SUBJECT, it) }
        }
        val mmsUri = context.contentResolver.insert(Telephony.Mms.CONTENT_URI, values) ?: return
        val mmsId = mmsUri.lastPathSegment ?: return
        insertMmsParts(context, msg, mmsId)
        // Sender addr row (FROM = 137).
        if (from != null) {
            insertMmsSender(context, msg, mmsId, from, threadId)
        }
    }

    /** Insert MMS parts (text inline, media via data stream). */
    private fun insertMmsParts(context: Context, msg: MmsPduReader.Retrieved, mmsId: String) {
        // Parts.
        for (p in msg.parts) {
            val pv = ContentValues().apply {
                put("mid", mmsId)
                put("ct", p.contentType)
                if (p.contentType == "text/plain") {
                    put("text", p.text.orEmpty())
                }
            }
            val partUri = context.contentResolver.insert(Uri.parse("content://mms/$mmsId/part"), pv)
            // Write binary media bytes into the part's data stream.
            if (partUri != null && !p.contentType.startsWith("text/") && p.data.isNotEmpty()) {
                runCatching {
                    context.contentResolver.openOutputStream(partUri)?.use { it.write(p.data) }
                }
            }
        }
    }

    /** Insert the sender addr row + notification. */
    private fun insertMmsSender(
        context: Context,
        msg: MmsPduReader.Retrieved,
        mmsId: String,
        from: String,
        threadId: Long?,
    ) {
        val av = ContentValues().apply {
            put("address", from)
            put("type", MMS_ADDR_TYPE_FROM)
            put("charset", MMS_CHARSET_UTF8)
        }
        context.contentResolver.insert(Uri.parse("content://mms/$mmsId/addr"), av)

        val textBody = msg.parts.firstOrNull { it.contentType == "text/plain" }?.text
        val body = textBody?.takeIf { it.isNotBlank() } ?: context.getString(R.string.media_message)
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
                address = from,
                threadId = threadId ?: -1L,
                personName = from,
            ),
            channelId = ConversationSpace.SIM_CHANNEL_ID,
            body = body,
            timestamp = System.currentTimeMillis(),
            smallIcon = R.mipmap.ic_launcher,
        )
    }

    /** MMS PDU type constants and logging tag for [MmsDeliverReceiver]. */
    companion object {
        private const val TAG = "MmsDeliverReceiver"
        private const val MMS_TYPE_RETRIEVE_CONF = 132
        private const val MMS_ADDR_TYPE_FROM = 137
        private const val MMS_CHARSET_UTF8 = 106
        private const val ACTION_MMS_DOWNLOADED = "com.vayunmathur.communicate.MMS_DOWNLOADED"
        private const val EXTRA_FILE = "file"
        private const val EXTRA_LOCATION = "location"
    }
}
