package com.vayunmathur.communicate.data

/**
 * Which line a conversation, message, or call belongs to. The physical SIM is the
 * default so every existing construction site (system-provider reads) stays valid;
 * Google Voice rows are tagged explicitly when merged in by the repository.
 */
enum class CommunicateLine {
    Sim,
    GoogleVoice,
    WhatsApp,
    Signal,
    Rcs,
}

/**
 * A selectable outgoing line for the line picker: a specific physical SIM (by subscription id)
 * or Google Voice. Mirrors how the OS lets you pick which SIM to call/text from.
 */
sealed interface LineChoice {
    val label: String
    val category: CommunicateLine

    data class Sim(val subscriptionId: Int, override val label: String) : LineChoice {
        override val category get() = CommunicateLine.Sim
    }

    data object GoogleVoice : LineChoice {
        override val label = "Google Voice"
        override val category get() = CommunicateLine.GoogleVoice
    }

    data object WhatsApp : LineChoice {
        override val label = "WhatsApp"
        override val category get() = CommunicateLine.WhatsApp
    }

    data object Signal : LineChoice {
        override val label = "Signal"
        override val category get() = CommunicateLine.Signal
    }

    data object Rcs : LineChoice {
        override val label = "RCS"
        override val category get() = CommunicateLine.Rcs
    }
}

data class CommunicateContact(
    val id: Long,
    val name: String,
    val phoneNumber: String,
    val label: String,
)

enum class CommunicateCallType {
    Incoming,
    Outgoing,
    Missed,
    Rejected,
    Blocked,
    Voicemail,
    Unknown,
}

data class CommunicateCallLogEntry(
    val id: Long,
    val displayName: String?,
    val phoneNumber: String,
    val type: CommunicateCallType,
    val timestampMillis: Long,
    val durationSeconds: Long,
    val line: CommunicateLine = CommunicateLine.Sim,
    /** Physical SIM subscription id for SIM calls (from the call log), null if unknown/GV. */
    val subscriptionId: Int? = null,
)

/**
 * One visual-voicemail row, either carrier ([android.provider.VoicemailContract])
 * or Google Voice (folder-4 listing). [contentUri] addresses the audio: a
 * provider row URI for carrier rows, the media URL for GV rows ([audioHeaders]
 * then carries the session cookies the endpoint requires). [transcript] is the
 * carrier transcription or the GV word-list join, when supplied.
 */
data class VoicemailEntry(
    val id: Long,
    val phoneNumber: String,
    val timestampMillis: Long,
    val durationSeconds: Long,
    val contentUri: String,
    val mimeType: String?,
    val hasAudio: Boolean,
    val transcript: String?,
    val isRead: Boolean,
    /** Physical SIM subscription id when the provider tags the row, null if unknown/GV. */
    val subscriptionId: Int? = null,
    val line: CommunicateLine = CommunicateLine.Sim,
    /** GV media auth (Cookie); empty for carrier rows. */
    val audioHeaders: Map<String, String> = emptyMap(),
    /** GV record id (`c.<opaque>`) for mark-read/delete; null for carrier rows. */
    val remoteId: String? = null,
)

/**
 * A media item attached to a message. SIM MMS parts arrive as local `content://`
 * URIs; Google Voice media arrive as remote https URLs. [contentUri] holds whichever
 * form applies so the UI can load it uniformly.
 */
data class CommunicateAttachment(
    val contentUri: String,
    val mimeType: String,
    /**
     * The document's display name, where the picker gave us one.
     *
     * Needed for anything that is not an image or video: a recipient shows a document by name, so without it a
     * PDF arrives as an untitled blob.
     */
    val fileName: String? = null,
)

data class SmsThread(
    val threadId: Long,
    val address: String,
    val displayName: String?,
    val snippet: String,
    val timestampMillis: Long,
    val unreadCount: Int,
    val line: CommunicateLine = CommunicateLine.Sim,
    /**
     * Google Voice thread id (e.g. `t.<...>`). Null for SIM threads, which are keyed by
     * the Long [threadId] from the system provider. GV rows synthesize a stable Long
     * [threadId] (hash of [remoteId]) so list keys and navigation still work.
     */
    val remoteId: String? = null,
    val subscriptionId: Int? = null,
    /** WhatsApp (and future group-capable lines): true for group chats. */
    val isGroup: Boolean = false,
    /** Optional avatar URL/path for the thread (WhatsApp contact/group photo). */
    val avatarUrl: String? = null,
    /**
     * Group participants (addresses/JIDs). Empty for 1:1 threads. Used to render the
     * group subtitle ("Alice, Bob +N") and, for SIM/GV groups, to route sends.
     */
    val participants: List<String> = emptyList(),
    /** Explicit group name/subject when known (WhatsApp subject, MMS group name). */
    val groupTitle: String? = null,
)

/**
 * Delivery lifecycle of an outgoing message, rendered as ticks in the bubble.
 * [None] means "don't show a tick" (inbound messages, or lines without receipts).
 */
enum class MessageStatus {
    None,
    Sent,
    Delivered,
    Read,
    Failed,
}

data class SmsMessage(
    val id: Long,
    val threadId: Long,
    val address: String,
    val body: String,
    val timestampMillis: Long,
    val outgoing: Boolean,
    val read: Boolean,
    val line: CommunicateLine = CommunicateLine.Sim,
    val remoteId: String? = null,
    val attachments: List<CommunicateAttachment> = emptyList(),
    val subscriptionId: Int? = null,
    /**
     * Line-specific rich payload as JSON. For WhatsApp this is [WhatsAppServiceData]
     * (reactions/polls/edited/revoked/quoted/group). Null for SIM/GV.
     */
    val serviceData: String? = null,
    /**
     * Sender address/JID for inbound group messages, used to render a per-message
     * sender label. Null for 1:1 threads and outgoing messages.
     */
    val senderAddress: String? = null,
    /** Delivery status for outgoing messages (drives the sent/delivered/read ticks). */
    val status: MessageStatus = MessageStatus.None,
)
