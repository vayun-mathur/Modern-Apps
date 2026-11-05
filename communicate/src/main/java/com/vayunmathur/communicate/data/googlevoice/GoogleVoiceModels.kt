package com.vayunmathur.communicate.data.googlevoice

/**
 * Internal DTOs for the Google Voice data layer, produced by [GoogleVoiceParser] from the
 * reverse-engineered protojson responses and mapped by the repository onto the shared
 * `Communicate*` models. Keeping them separate isolates the (fragile, positional) wire
 * shapes from the rest of the app.
 */

data class GvAccount(
    /** The account's primary Google Voice number, in whatever form the wire returned. */
    val phoneNumber: String?,
)

/** Folder/tab enum for `api2thread/list` (slot 0). Values observed 1–5 in capture 2. */
private const val FOLDER_ALL = 1
private const val FOLDER_INBOX = 2
private const val FOLDER_CALLS = 3
private const val FOLDER_VOICEMAIL = 4
private const val FOLDER_SPAM = 5

enum class GvFolder(val id: Int) {
    All(FOLDER_ALL),
    Inbox(FOLDER_INBOX),
    // 3/4/5 map to calls/voicemail/spam-style folders; exact mapping is unproven from the
    // wire (see voice-documentation.md "Folder Navigation Shape").
    Calls(FOLDER_CALLS),
    Voicemail(FOLDER_VOICEMAIL),
    Spam(FOLDER_SPAM),
}

data class GvMessage(
    val id: String,
    val threadId: String,
    val phoneNumber: String,
    val text: String,
    val timestampMillis: Long,
    val outgoing: Boolean,
    val read: Boolean,
    val mediaUrls: List<String> = emptyList(),
    val hasMedia: Boolean = mediaUrls.isNotEmpty(),
)

data class GvThread(
    val id: String,
    val phoneNumber: String,
    val displayName: String?,
    val snippet: String,
    val timestampMillis: Long,
    val unreadCount: Int,
    val messages: List<GvMessage> = emptyList(),
)

enum class GvCallType { Incoming, Outgoing, Missed, Voicemail, Unknown }

data class GvCall(
    val id: String,
    val phoneNumber: String,
    val displayName: String?,
    val type: GvCallType,
    val timestampMillis: Long,
    val durationSeconds: Long,
)

/**
 * One Google Voice voicemail from a folder-4 `api2thread/list` record.
 * [transcript] joins the word list at item slot 6 (null when the carrier
 * hasn't transcribed it yet); [audioUrl] is the item-slot-11 media URL;
 * [read] is the item-slot-13 flag (1 vs 8 in captures — treated as read
 * unless 8, verified against mark-read round-trips).
 */
data class GvVoicemail(
    val id: String,
    val phoneNumber: String,
    val timestampMillis: Long,
    val durationSeconds: Long,
    val transcript: String?,
    val audioUrl: String?,
    val read: Boolean,
)

/** SIP registration material from `sipregisterinfo/get`, consumed by the calling layer. */
data class GvSipRegisterInfo(
    val phoneNumber: String?,
    /** Opaque registration credential/token strings observed in the response tail. */
    val credentials: List<String>,
)
