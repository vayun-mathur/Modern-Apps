package com.vayunmathur.communicate.data.whatsapp

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Rich-feature payload serialized into `SmsMessage.serviceData` (and the cached-message row) so the
 * WhatsApp line can carry reactions, polls, edits, revokes, quoted replies and group metadata
 * through the SIM/GV-shaped models without changing those paths. The UI ([ConversationScreen])
 * parses this back out to render the extras.
 */
@Serializable
data class WhatsAppServiceData(
    val senderName: String? = null,
    val senderJid: String? = null,
    val reactions: List<Reaction> = emptyList(),
    val isEdited: Boolean = false,
    val isRevoked: Boolean = false,
    // Poll
    val pollQuestion: String? = null,
    val pollOptions: List<PollOptionData> = emptyList(),
    // Quoted reply
    val quotedMessageId: String? = null,
    val quotedBody: String? = null,
    val quotedSender: String? = null,
    // Group
    val isGroup: Boolean = false,
    val groupParticipantCount: Int = 0,
    // Media
    val mediaUrl: String? = null,
    val mediaMime: String? = null,
    val mediaName: String? = null,
) {
    fun serialize(): String = JSON.encodeToString(this)

    /** The JSON codec and parser entry point for [WhatsAppServiceData]. */
    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun parse(json: String?): WhatsAppServiceData? =
            if (json.isNullOrBlank()) null else runCatching {
                JSON.decodeFromString<WhatsAppServiceData>(json)
            }.getOrNull()
    }
}

/** One option of a WhatsApp poll, with its running vote tally. */
@Serializable
data class PollOptionData(
    val name: String,
    val voteCount: Int = 0,
    val voters: List<String> = emptyList(),
)
