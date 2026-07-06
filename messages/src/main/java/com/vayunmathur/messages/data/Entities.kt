package com.vayunmathur.messages.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One conversation row.
 *
 * The [id] is a string with a source prefix so that two conversations from
 * different sources can't collide (e.g. "msgs:thread_42" vs "voice:thread_42").
 * The puppet sets the prefix; the data layer only stores it.
 */
@Entity(tableName = "conversations")
data class Conversation(
    @PrimaryKey val id: String,
    val source: MessageSource,
    /** Display name surfaced by the underlying web app. May be a phone
     *  number if the user has no contact stored for the peer. */
    val peerName: String?,
    /** E.164-normalized number for one-on-one chats; null for groups. */
    @ColumnInfo(name = "peer_phone_e164") val peerPhoneE164: String?,
    @ColumnInfo(name = "avatar_url") val avatarUrl: String?,
    @ColumnInfo(name = "last_preview") val lastMessagePreview: String?,
    @ColumnInfo(name = "unread_count") val unreadCount: Int,
    /** Whether this thread has > 2 participants. Drives both the avatar
     *  treatment (group glyph vs single photo) and the row title format. */
    @ColumnInfo(name = "is_group") val isGroup: Boolean = false,
    /** Number of OTHER participants (i.e. excluding the local user). */
    @ColumnInfo(name = "participant_count") val participantCount: Int = 0,
    /** Conversation type as the relay reports it: "SMS", "RCS",
     *  or null/UNKNOWN. Used for the per-row chip. */
    @ColumnInfo(name = "conv_type") val conversationType: String? = null,
    /** Per-source identifier we need to pass back when SENDING a message
     *  on this conversation.
     *  - For [MessageSource.MESSAGES_WEB]: the conversation's
     *    `defaultOutgoingID` (Google's per-thread "my SIM" participantID,
     *    used as `MessagePayload.participantID` in SendMessageRequest).
     *  - For [MessageSource.VOICE]: unused — the thread ID is enough.
     *  Stored so we don't have to keep the full proto Conversation around. */
    @ColumnInfo(name = "outgoing_id") val outgoingId: String? = null,
    /** Service-specific data (JSON) for this conversation — lets each source store its own
     *  metadata (e.g. WhatsApp mute/pin/archive, group info) without a separate database. */
    @ColumnInfo(name = "service_data") val serviceData: String? = null,
)

/**
 * One message row.
 *
 * [id] is also source-prefixed because the underlying web apps assign
 * their own per-thread numeric ids that aren't unique across sources.
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = Conversation::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversation_id"), Index("timestamp")],
)
data class Message(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    val body: String,
    val direction: MessageDirection,
    val state: MessageState,
    /** Epoch-ms. */
    @ColumnInfo(name = "timestamp") val timestamp: Long,
    /** Display name of the sender (mainly useful when group messages
     *  arrive — for direct messages this matches the conversation peer). */
    @ColumnInfo(name = "sender_name") val senderName: String?,
    /** Stable per-sender id (platform-specific). Lets the UI coalesce
     *  consecutive same-sender bubbles in groups even when two
     *  participants share a display name. Null in 1:1 chats. */
    @ColumnInfo(name = "sender_id") val senderId: String? = null,
    /**
     * JSON-serialized list of [com.vayunmathur.messages.data.Reaction]
     * applied to this message (`[{"emoji":"❤️","count":2}, …]`). null
     * when none. Stored as a compact blob to avoid a second table for
     * what's effectively per-message metadata.
     */
    @ColumnInfo(name = "reactions_json") val reactionsJson: String? = null,
    /** Service-specific data (JSON) for this message — per-source metadata stored inline
     *  instead of in a separate database. */
    @ColumnInfo(name = "service_data") val serviceData: String? = null,
    /** JSON-serialized list of [MessageAttachment] rendered inline (received
     *  images / video / stickers / shares). null when the message has no
     *  media. Populated by the session manager from the source event. */
    @ColumnInfo(name = "media_json") val mediaJson: String? = null,
)

/**
 * One inline media attachment on a received (or sent) message. URL-based:
 * for sources whose media URLs are directly fetchable (e.g. Instagram's
 * signed CDN links) the UI loads [url]/[previewUrl] with Coil — no
 * download pipeline needed. [attachmentType] is one of
 * image | video | audio | sticker | file | share.
 */
@kotlinx.serialization.Serializable
data class MessageAttachment(
    /** Full/playable media URL (falls back to [previewUrl] if absent). */
    val url: String? = null,
    /** Thumbnail/preview URL (used for video posters, share cards). */
    val previewUrl: String? = null,
    val mimeType: String? = null,
    /** image | video | audio | sticker | file | share. */
    val attachmentType: String = "file",
    val fileName: String? = null,
    /** Title for share/XMA cards (reels, posts, links). */
    val title: String? = null,
    /** Tap target for share/XMA cards. */
    val actionUrl: String? = null,
    val width: Int = 0,
    val height: Int = 0,
)

/** Aggregated reaction on a message. The relay surfaces these per-emoji
 *  with a participant list; we collapse to "emoji + count" because v1
 *  doesn't need to attribute the reaction to a specific participant. */
@kotlinx.serialization.Serializable
data class Reaction(val emoji: String, val count: Int)
