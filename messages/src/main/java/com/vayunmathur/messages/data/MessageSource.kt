package com.vayunmathur.messages.data

/**
 * Where a conversation originated. Today there is only one source —
 * SMS conversations relayed through the user's phone via the
 * Google-Messages-for-Web pairing. The enum is preserved so we don't
 * need a Room schema migration if we later add another source (e.g.
 * Signal-Desktop-style pairing) and so source-prefixed primary keys
 * keep their semantics ("msgs:<id>").
 */
enum class MessageSource {
    /** SMS conversations relayed through the user's phone via the
     *  Google-Messages-for-Web pairing. */
    MESSAGES_WEB,

    /** Conversations on the user's Google Voice number, accessed via
     *  cookie-based authentication against voice.google.com. */
    VOICE,

    /** Conversations on Telegram, accessed via MTProto. */
    TELEGRAM,

    /** Conversations on Signal, accessed via libsignal + WebSocket. */
    SIGNAL,

    /** Conversations on WhatsApp, accessed via WhatsApp Web protocol. */
    WHATSAPP,

    /** Conversations on Facebook Messenger, accessed via MQTT. */
    MESSENGER,

    /** Conversations on Instagram Direct, accessed via MQTT. */
    INSTAGRAM;

    /** Prefix for compound primary keys (e.g. "msgs:<id>"). */
    val idPrefix: String get() = when (this) {
        MESSAGES_WEB -> "msgs"
        VOICE -> "voice"
        TELEGRAM -> "tg"
        SIGNAL -> "sig"
        WHATSAPP -> "wa"
        MESSENGER -> "fb"
        INSTAGRAM -> "ig"
    }
}

/** Direction of a single [Message] relative to the local user. */
enum class MessageDirection { INCOMING, OUTGOING }

/**
 * Delivery state for an outgoing message. Inbound messages are always
 * stored with state = [DELIVERED].
 *
 * - [PENDING]   shown immediately when the user hits send, before the
 *               puppet WebView has confirmed the send went through.
 * - [SENT]      the web UI shows the message as accepted by the relay.
 * - [DELIVERED] the recipient's device acknowledged. Not all sources
 *               surface delivery receipts; treat absence as "stay at SENT".
 * - [FAILED]    sending failed; the row is kept so the user can retry.
 */
enum class MessageState { PENDING, SENT, DELIVERED, FAILED }
