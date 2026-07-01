package com.vayunmathur.messages.util

import com.vayunmathur.messages.data.MessageSource

/**
 * A composer attachment/action the UI can offer. Used to gate the
 * attachment menu so it only shows entries the current conversation's
 * platform actually supports (see [mediaCapabilities]).
 */
enum class MediaCapability {
    /** Send a photo via the system photo picker. */
    IMAGE,

    /** Send an arbitrary file via ACTION_OPEN_DOCUMENT (any mime). */
    FILE,

    /** Create a poll (question + options + optional multi-select). */
    POLL,

    /** Share location as a FindFamily link (sent as a message). */
    LOCATION,
}

/**
 * Which [MediaCapability] entries are valid for a conversation on this
 * [MessageSource]. Mirrors MEDIA_FEATURES_CONTRACTS.md §1.
 *
 * - IMAGE: every platform's `sendMedia` accepts image mimes.
 * - FILE: every platform except Google Voice (Voice only sends a fixed
 *   set of inline image types — no arbitrary attachments).
 * - POLL: only platforms whose protocol supports polls. Google
 *   Messages / Voice (SMS/RCS) have no poll concept.
 * - LOCATION: universal — the payload is a FindFamily share URL sent as
 *   a normal text message, so any text-capable platform qualifies.
 */
fun MessageSource.mediaCapabilities(): Set<MediaCapability> = when (this) {
    MessageSource.MESSAGES_WEB -> setOf(
        MediaCapability.IMAGE,
        MediaCapability.FILE,
        MediaCapability.LOCATION,
    )
    MessageSource.VOICE -> setOf(
        MediaCapability.IMAGE,
        MediaCapability.LOCATION,
    )
    MessageSource.TELEGRAM,
    MessageSource.SIGNAL,
    MessageSource.WHATSAPP,
    MessageSource.MESSENGER,
    MessageSource.INSTAGRAM -> setOf(
        MediaCapability.IMAGE,
        MediaCapability.FILE,
        MediaCapability.POLL,
        MediaCapability.LOCATION,
    )
}
