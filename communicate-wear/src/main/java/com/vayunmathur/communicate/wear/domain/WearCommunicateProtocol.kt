package com.vayunmathur.communicate.wear.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Phone <-> watch protocol for the Wear OS Communicate companion.
 *
 * SINGLE SOURCE OF TRUTH, DELIBERATELY DUPLICATED: Gradle cannot let one
 * application module depend on another, so the wear module
 * (`:communicate-wear`, applicationId `com.vayunmathur.communicate`) carries
 * a copy of this file under `com.vayunmathur.communicate.wear.domain`.
 * Change both copies together; each side's unit tests pin the same golden
 * JSON so a one-sided edit fails loudly.
 *
 * The watch mirrors the phone app's navigation, not a glance card:
 * conversation list (all lines the phone merges: SIM / Google Voice /
 * WhatsApp / Signal / RCS), one thread (paged), call log, and voicemail
 * indicators. All repository work and all decryption stay on the phone;
 * the watch only receives bounded snapshots/pages, caches them for offline
 * reading, and sends ops the phone executes.
 *
 * Page mapping (phone [com.vayunmathur.communicate.Route] -> watch):
 * - `Messages` -> conversation list. Mirrored.
 * - `Conversation(threadId, ...)` -> thread view. Mirrored, paged.
 * - `CallLogs` -> call log. Mirrored (read-only; no watch calling).
 * - Voicemail (inside the phone dialer flow) -> voicemail indicators:
 *   caller, time, duration, read state and a capped transcript. Audio
 *   playback, content URIs and GV audio auth headers NEVER cross.
 * - `Dialer` -> OMITTED: no watch telephony/audio route is guaranteed, so
 *   the watch never offers dialling or answer-on-watch.
 * - `Accounts`, registration wizards, `RcsStatus`/`RcsVerify`,
 *   `RcsGroupMembers`, `Call` -> OMITTED: calling setup, line registration,
 *   safety-number verification, group management, media viewing and full
 *   settings do not fit a wrist and stay phone-only.
 * - Archive/mute -> OMITTED: the phone UI offers neither (only the
 *   underlying WhatsApp/Signal DBs know the flags), so the watch offers
 *   no op the phone UI does not.
 *
 * Privacy choices (stated, not implied):
 * - Disappearing messages: a thread/message flagged disappearing is
 *   EXCLUDED in content — snippet and body cross as empty, media is not
 *   even counted, and only a `contentExcluded` marker remains so the
 *   watch can say why. Persisting their plaintext in a watch cache would
 *   defeat the feature. The phone detects this today for WhatsApp
 *   conversations (`ephemeralExpiration > 0`); other lines surface no
 *   unified flag, so their flag is false until they do.
 * - E2EE lines (WhatsApp/Signal/RCS-MLS): the phone is the endpoint and
 *   decrypts; capped plaintext then crosses the cert-bound WearLink
 *   session to the owner's same-signed watch, like the phone's own
 *   notification text. No keys, no serviceData JSON, no media URLs, no
 *   attachment bytes ever cross — media is a label ("Photo") only.
 *   Revoked messages cross with an empty body.
 * - New-message alerts are small messages: display name + capped snippet
 *   only when not disappearing (empty snippet then), never a full page.
 *
 * Transport (WearLink, :library:wearlink — RFCOMM, no Play services):
 * - Data items: [SNAPSHOT_PATH] phone -> watch, [WATCH_STATE_PATH] back.
 * - Messages: [OP_PATH] watch -> phone, [OP_RESULT_PATH] phone -> watch,
 *   [REQUEST_SYNC_PATH] / [REQUEST_THREAD_PATH] watch -> phone,
 *   [THREAD_PAGE_PATH] / [NEW_MESSAGE_PATH] / [INCOMING_CALL_PATH]
 *   phone -> watch. Delivery needs a live session; nothing wakes a dead
 *   phone process, and the watch resends pending ops on reconnect.
 */
object WearCommunicateProtocol {
    /** WearLink appId the phone requires in the peer handshake. */
    const val CAPABILITY = "communicate_wear_companion"

    const val SNAPSHOT_PATH = "/communicate/snapshot"
    const val WATCH_STATE_PATH = "/communicate/watch-state"
    const val OP_PATH = "/communicate/op"
    const val OP_RESULT_PATH = "/communicate/op-result"
    const val REQUEST_SYNC_PATH = "/communicate/request-sync"
    const val REQUEST_THREAD_PATH = "/communicate/request-thread"
    const val THREAD_PAGE_PATH = "/communicate/thread-page"
    const val NEW_MESSAGE_PATH = "/communicate/new-message"
    const val INCOMING_CALL_PATH = "/communicate/incoming-call"

    const val MAX_THREADS = 50
    const val MAX_CALLS = 50
    const val MAX_VOICEMAILS = 30
    const val MAX_JSON_BYTES = 90_000
    const val MAX_TITLE_CHARS = 80
    const val MAX_SNIPPET_CHARS = 120
    const val MAX_BODY_CHARS = 1_000
    const val MAX_TRANSCRIPT_CHARS = 200
    const val MAX_PAGE_MESSAGES = 20
    const val MAX_CACHED_MESSAGES_PER_THREAD = 100
    const val MAX_CACHED_THREADS = 10
    const val MAX_REPLY_CHARS = 1_000
    const val MAX_ALERT_SNIPPET_CHARS = 80

    /** Canned quick replies; free text (dictation/IME) is also accepted. */
    val CANNED_REPLIES = listOf(
        "On my way",
        "Call you later",
        "Can't talk right now",
        "OK",
        "Thanks",
    )

    val json: Json = Json { ignoreUnknownKeys = true }
}

/** Line names mirror the phone's `CommunicateLine` enum names exactly. */
@Serializable
enum class WearCommLine { Sim, GoogleVoice, WhatsApp, Signal, Rcs }

/** One conversation in the merged list, trimmed to watch size. */
@Serializable
data class WearThread(
    /** Stable watch key: `"<line>:<remoteId or threadId>"`. */
    val key: String,
    val threadId: Long = 0L,
    val line: WearCommLine = WearCommLine.Sim,
    val address: String = "",
    val remoteId: String? = null,
    val subscriptionId: Int? = null,
    val displayName: String = "",
    /** Capped snippet; empty when [contentExcluded]. */
    val snippet: String = "",
    val timestampMillis: Long = 0L,
    val unreadCount: Int = 0,
    val isGroup: Boolean = false,
    val groupTitle: String? = null,
    val participants: List<String> = emptyList(),
    /** True when the phone flagged disappearing messages for this thread. */
    val disappearing: Boolean = false,
    /** True when content was withheld (disappearing); snippet/body are empty. */
    val contentExcluded: Boolean = false,
)

/** One message in a thread page. Media is a label only; no URL/bytes cross. */
@Serializable
data class WearMessage(
    val messageKey: String,
    val threadKey: String = "",
    /** Capped body; empty when [contentExcluded] or revoked. */
    val body: String = "",
    val timestampMillis: Long = 0L,
    val outgoing: Boolean = false,
    val read: Boolean = true,
    val senderLabel: String? = null,
    /** Display label for an attachment ("Photo", "Video", "File"), or null. */
    val mediaLabel: String? = null,
    val status: String = "None",
    val disappearing: Boolean = false,
    val contentExcluded: Boolean = false,
    val bodyTruncated: Boolean = false,
)

/** One unified call-log row (read-only on the watch). */
@Serializable
data class WearCallEntry(
    val key: String,
    val displayName: String = "",
    val phoneNumber: String = "",
    /** Phone `CommunicateCallType` name. */
    val type: String = "Unknown",
    val timestampMillis: Long = 0L,
    val durationSeconds: Long = 0L,
    val line: WearCommLine = WearCommLine.Sim,
)

/** One voicemail indicator. No audio URI, no auth headers, no playback. */
@Serializable
data class WearVoicemail(
    val key: String,
    val phoneNumber: String = "",
    val timestampMillis: Long = 0L,
    val durationSeconds: Long = 0L,
    /** Capped transcript when the phone has one; never an audio reference. */
    val transcript: String = "",
    val isRead: Boolean = true,
    val line: WearCommLine = WearCommLine.Sim,
)

/** Bounded phone snapshot for the watch ([WearCommunicateProtocol.SNAPSHOT_PATH]). */
@Serializable
data class WearCommunicateSnapshot(
    val generatedAtMillis: Long = 0L,
    val threads: List<WearThread> = emptyList(),
    val calls: List<WearCallEntry> = emptyList(),
    val voicemails: List<WearVoicemail> = emptyList(),
    val voicemailUnreadCount: Int = 0,
    val truncated: Boolean = false,
)

/** One paged slice of a thread, oldest-to-newest inside the page. */
@Serializable
data class WearThreadPage(
    val threadKey: String = "",
    val messages: List<WearMessage> = emptyList(),
    /** True when older messages remain on the phone. */
    val hasMore: Boolean = false,
    /** Cursor for the next older page: oldest timestamp in this page. */
    val oldestTimestampMillis: Long = 0L,
    val generatedAtMillis: Long = 0L,
)

/** Watch request for one thread page ([WearCommunicateProtocol.REQUEST_THREAD_PATH]). */
@Serializable
data class WearThreadRequest(
    val threadKey: String,
    /** Page older than this timestamp; 0 means the latest page. */
    val beforeTimestampMillis: Long = 0L,
)

/** Small new-message alert ([WearCommunicateProtocol.NEW_MESSAGE_PATH]). */
@Serializable
data class WearNewMessageAlert(
    val threadKey: String = "",
    val displayName: String = "",
    val line: WearCommLine = WearCommLine.Sim,
    /** Capped snippet; always empty for disappearing threads. */
    val snippet: String = "",
    val timestampMillis: Long = 0L,
    val unreadCount: Int = 0,
    val contentExcluded: Boolean = false,
)

/**
 * Incoming-call alert ([WearCommunicateProtocol.INCOMING_CALL_PATH]).
 * Carries no answer capability on purpose: watch audio routing is not
 * guaranteed, so the only watch call action is decline (see [WearOpKind]).
 */
@Serializable
data class WearIncomingCall(
    val callKey: String = "",
    val displayName: String = "",
    val line: WearCommLine = WearCommLine.Sim,
    val isVideo: Boolean = false,
    val timestampMillis: Long = 0L,
)

/** Watch-originated actions the phone executes. Archive/mute are absent: the phone UI offers neither. */
@Serializable
enum class WearOpKind { QuickReply, MarkRead, MarkVoicemailRead, DeclineCall }

/** One watch-originated action ([WearCommunicateProtocol.OP_PATH]). */
@Serializable
data class WearCommOp(
    /** Watch-generated id; the phone echoes it in [WearOpResult]. */
    val opId: String,
    val kind: WearOpKind,
    val threadKey: String = "",
    val line: WearCommLine = WearCommLine.Sim,
    val threadId: Long = 0L,
    val remoteId: String? = null,
    val address: String = "",
    val subscriptionId: Int? = null,
    val participants: List<String> = emptyList(),
    /** Reply text for [WearOpKind.QuickReply]; empty otherwise. */
    val body: String = "",
    /** Voicemail/call key for [WearOpKind.MarkVoicemailRead] / [WearOpKind.DeclineCall]. */
    val targetKey: String = "",
)

/** Phone verdict for one op ([WearCommunicateProtocol.OP_RESULT_PATH]). */
@Serializable
data class WearOpResult(
    val opId: String = "",
    val kind: WearOpKind = WearOpKind.MarkRead,
    val success: Boolean = false,
)

/** What the watch last applied ([WearCommunicateProtocol.WATCH_STATE_PATH]). */
@Serializable
data class WearCommWatchState(
    val appliedGeneratedAtMillis: Long = 0L,
    val threadCount: Int = 0,
    val pendingOps: Int = 0,
)

/** Stable thread key: remote id when the line has one, else the phone thread id. */
fun wearThreadKey(line: WearCommLine, remoteId: String?, threadId: Long): String =
    "$line:${remoteId?.takeIf { it.isNotBlank() } ?: threadId}"

/** Content that may cross to the watch: nothing for disappearing/revoked, else capped. */
fun sanitizeWearText(text: String, disappearing: Boolean, cap: Int): String =
    if (disappearing) "" else text.take(cap)

/** Applies the disappearing/revoked exclusion and caps to one message. */
fun sanitizeWearMessage(message: WearMessage): WearMessage {
    if (message.disappearing) {
        return message.copy(body = "", mediaLabel = null, contentExcluded = true, bodyTruncated = false)
    }
    return message.copy(
        body = message.body.take(WearCommunicateProtocol.MAX_BODY_CHARS),
        bodyTruncated = message.bodyTruncated ||
            message.body.length > WearCommunicateProtocol.MAX_BODY_CHARS,
    )
}

/** Applies the exclusion and caps to one thread row (snippet is content). */
fun sanitizeWearThread(thread: WearThread): WearThread = thread.copy(
    displayName = thread.displayName.take(WearCommunicateProtocol.MAX_TITLE_CHARS),
    snippet = sanitizeWearText(
        thread.snippet,
        thread.disappearing,
        WearCommunicateProtocol.MAX_SNIPPET_CHARS,
    ),
    contentExcluded = thread.contentExcluded || thread.disappearing,
    participants = thread.participants.take(10),
)

/**
 * Builds the bounded snapshot: newest first per section, field caps and
 * exclusion applied, then count caps, then the byte cap enforced by
 * dropping the oldest voicemails, then calls, then threads (the
 * conversation list — the watch's home — survives longest).
 */
fun buildWearCommunicateSnapshot(
    threads: List<WearThread>,
    calls: List<WearCallEntry>,
    voicemails: List<WearVoicemail>,
    generatedAtMillis: Long,
): WearCommunicateSnapshot {
    var keptThreads = threads.map(::sanitizeWearThread)
        .sortedWith(compareByDescending<WearThread> { it.timestampMillis }.thenBy { it.key })
        .take(WearCommunicateProtocol.MAX_THREADS)
    var keptCalls = calls
        .sortedWith(compareByDescending<WearCallEntry> { it.timestampMillis }.thenBy { it.key })
        .take(WearCommunicateProtocol.MAX_CALLS)
    var keptVoicemails = voicemails.map {
        it.copy(transcript = it.transcript.take(WearCommunicateProtocol.MAX_TRANSCRIPT_CHARS))
    }.sortedWith(compareByDescending<WearVoicemail> { it.timestampMillis }.thenBy { it.key })
        .take(WearCommunicateProtocol.MAX_VOICEMAILS)
    var truncated = keptThreads.size < threads.size || keptCalls.size < calls.size ||
        keptVoicemails.size < voicemails.size
    fun snapshot() = WearCommunicateSnapshot(
        generatedAtMillis = generatedAtMillis,
        threads = keptThreads,
        calls = keptCalls,
        voicemails = keptVoicemails,
        voicemailUnreadCount = keptVoicemails.count { !it.isRead },
        truncated = truncated,
    )
    var result = snapshot()
    fun overBytes() =
        WearCommunicateProtocol.json.encodeToString(result).encodeToByteArray().size >
            WearCommunicateProtocol.MAX_JSON_BYTES
    while (overBytes() && keptVoicemails.isNotEmpty()) {
        keptVoicemails = keptVoicemails.dropLast(1); truncated = true; result = snapshot()
    }
    while (overBytes() && keptCalls.isNotEmpty()) {
        keptCalls = keptCalls.dropLast(1); truncated = true; result = snapshot()
    }
    while (overBytes() && keptThreads.isNotEmpty()) {
        keptThreads = keptThreads.dropLast(1); truncated = true; result = snapshot()
    }
    return result
}

/**
 * One page of a thread: the [pageSize] newest messages strictly older
 * than [beforeTimestampMillis] (0 = latest page), returned
 * oldest-to-newest. Exclusion/caps are applied before paging so an
 * excluded message still occupies its slot (its position is not a leak).
 */
fun buildWearThreadPage(
    threadKey: String,
    messages: List<WearMessage>,
    beforeTimestampMillis: Long = 0L,
    generatedAtMillis: Long = 0L,
    pageSize: Int = WearCommunicateProtocol.MAX_PAGE_MESSAGES,
): WearThreadPage {
    val clean = messages.map(::sanitizeWearMessage)
        .sortedWith(compareBy<WearMessage> { it.timestampMillis }.thenBy { it.messageKey })
    val eligible = if (beforeTimestampMillis <= 0L) clean
        else clean.filter { it.timestampMillis < beforeTimestampMillis }
    val page = eligible.takeLast(pageSize.coerceAtLeast(1))
    return WearThreadPage(
        threadKey = threadKey,
        messages = page,
        hasMore = eligible.size > page.size,
        oldestTimestampMillis = page.firstOrNull()?.timestampMillis ?: 0L,
        generatedAtMillis = generatedAtMillis,
    )
}

/**
 * Merges a newly arrived [page] into a cached thread view: dedupes by
 * messageKey, keeps chronological order, caps to the most recent
 * [WearCommunicateProtocol.MAX_CACHED_MESSAGES_PER_THREAD] plus any older
 * paged-in messages up to the same cap (oldest dropped first).
 */
fun mergeWearThreadPage(cached: List<WearMessage>, page: WearThreadPage): List<WearMessage> {
    val byKey = (cached + page.messages).associateBy { it.messageKey }
    return byKey.values
        .sortedWith(compareBy<WearMessage> { it.timestampMillis }.thenBy { it.messageKey })
        .takeLast(WearCommunicateProtocol.MAX_CACHED_MESSAGES_PER_THREAD)
}

/** Small alert for [thread] after a change; snippet withheld when excluded. */
fun buildWearNewMessageAlert(thread: WearThread, timestampMillis: Long): WearNewMessageAlert =
    WearNewMessageAlert(
        threadKey = thread.key,
        displayName = thread.displayName.take(WearCommunicateProtocol.MAX_TITLE_CHARS),
        line = thread.line,
        snippet = sanitizeWearText(
            thread.snippet,
            thread.disappearing,
            WearCommunicateProtocol.MAX_ALERT_SNIPPET_CHARS,
        ),
        timestampMillis = timestampMillis,
        unreadCount = thread.unreadCount,
        contentExcluded = thread.disappearing || thread.contentExcluded,
    )

/** A reply the phone may execute: non-blank after trim, capped. Null when invalid. */
fun validWearReply(op: WearCommOp): String? {
    if (op.kind != WearOpKind.QuickReply) return null
    val body = op.body.trim().take(WearCommunicateProtocol.MAX_REPLY_CHARS)
    return body.takeIf { it.isNotEmpty() && op.threadKey.isNotBlank() }
}

/** Pending ops after a phone [result]: a success drops its op; a failure keeps it for resend. */
fun pendingAfterResult(pending: List<WearCommOp>, result: WearOpResult): List<WearCommOp> =
    if (result.success) pending.filter { it.opId != result.opId } else pending
