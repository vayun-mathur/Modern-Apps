package com.vayunmathur.communicate.data.googlevoice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray

/**
 * Converts Google Voice protojson (`alt=protojson`, `application/json+protobuf`) bodies to
 * and from [GoogleVoiceModels]. Google's RPCs use *positional* JSON arrays with no field
 * names, so **every** array index / wire assumption is deliberately quarantined in this
 * one file. If Google shifts a slot, this is the only place that needs to change.
 *
 * The shapes here are reverse-engineered from `voice-documentation.md` (two HAR captures)
 * and are intentionally tolerant: parsing walks the tree heuristically (find the id-prefixed
 * records, then the phone / timestamp / text inside them) rather than hard-coding brittle
 * deep indices, because the captures preserved shape but not exact offsets. They must be
 * validated against live responses; see the plan's "Key Risks".
 */
object GoogleVoiceParser {

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** Thread attribute mutations for `thread/batchupdateattributes`. */
    sealed interface ThreadAction {
        data object MarkRead : ThreadAction
        data object MarkUnread : ThreadAction
        data object Archive : ThreadAction
        data object Unarchive : ThreadAction
    }

    // ------------------------------------------------------------------
    // Request body builders (documented shapes)
    // ------------------------------------------------------------------

    /** `api2thread/list`: `[folder,pageSize,window,null,null,[null,1,1,1]]`. */

    /** Pull the `"!…"` WAA/botguard token out of a captured web `sendsms` body. */
    fun extractBotToken(capturedBody: String): String? {
        val root = parseOrNull(capturedBody) as? JsonArray ?: return null
        // The token is a string element (usually the last non-null) starting with '!'.
        fun search(el: JsonElement): String? {
            when (el) {
                is JsonArray -> for (c in el) search(c)?.let { return it }
                is JsonPrimitive -> if (el.isString && el.content.startsWith("!")) return el.content
                else -> {}
            }
            return null
        }
        return search(root)
    }

    // ------------------------------------------------------------------
    // Response parsing
    // ------------------------------------------------------------------

    fun parseAccount(body: String): GvAccount {
        val root = parseOrNull(body) ?: return GvAccount(null)
        // The primary number is the first phone-shaped string in the (large) account array.
        val phone = firstPhone(root)
        return GvAccount(phoneNumber = phone)
    }

    fun parseThreads(body: String): List<GvThread> {
        val root = parseOrNull(body) ?: return emptyList()
        return findRecords(root, prefixes = listOf("t.")).mapNotNull { it.toThread() }
            .filter { it.phoneNumber.isNotBlank() || it.snippet.isNotBlank() || it.messages.isNotEmpty() }
            .distinctBy { it.id }
    }

    /**
     * Messages of a single thread. `api2thread/get` returned an empty stub for our request shape,
     * but every message is already embedded in the `api2thread/list` record ([2]); the repository
     * therefore feeds a list-record body here.
     */
    fun parseThreadMessages(body: String, threadId: String): List<GvMessage> {
        val root = parseOrNull(body) ?: return emptyList()
        val record = findRecords(root, prefixes = listOf("t.", "c."))
            .firstOrNull { recordId(it) == threadId }
            ?: return emptyList()
        return record.toThread()?.messages ?: emptyList()
    }

    fun parseCalls(body: String): List<GvCall> {
        val root = parseOrNull(body) ?: return emptyList()
        return findRecords(root, prefixes = listOf("c.")).mapNotNull { record ->
            val id = recordId(record) ?: return@mapNotNull null
            val items = record.arrAt(ITEMS)?.mapNotNull { it as? JsonArray }.orEmpty()
            val newest = items.firstOrNull()
            val counterparty = counterpartyOf(record, newest) ?: return@mapNotNull null
            val duration = newest?.longAt(ITEM_DURATION) ?: 0L
            val outgoing = newest != null &&
                normalizePhone(newest.strAt(ITEM_OWN).orEmpty()) == normalizePhone(counterparty)
            GvCall(
                id = id,
                phoneNumber = counterparty,
                displayName = null,
                type = when {
                    outgoing -> GvCallType.Outgoing
                    duration <= 0 -> GvCallType.Missed
                    else -> GvCallType.Incoming
                },
                timestampMillis = newest?.longAt(ITEM_TS)?.let { normalizeTimestampMillis(it) } ?: 0L,
                durationSeconds = duration,
            )
        }.distinctBy { it.id }
    }

    /**
     * Voicemails from a folder-4 `api2thread/list` body. Records are `c.`-keyed
     * like calls, but the item carries the voicemail marker ([ITEM_VM_TYPE] = 2,
     * calls use 14), the transcript word list ([ITEM_TRANSCRIPT]), and the
     * playable media URL ([ITEM_AUDIO_URL]).
     */
    fun parseVoicemails(body: String): List<GvVoicemail> {
        val root = parseOrNull(body) ?: return emptyList()
        return findRecords(root, prefixes = listOf("c.")).mapNotNull { record ->
            val id = recordId(record) ?: return@mapNotNull null
            val items = record.arrAt(ITEMS)?.mapNotNull { it as? JsonArray }.orEmpty()
            val newest = items.firstOrNull() ?: return@mapNotNull null
            if (newest.longAt(ITEM_VM_TYPE) != VM_TYPE_VOICEMAIL) return@mapNotNull null
            val counterparty = counterpartyOf(record, newest) ?: return@mapNotNull null
            GvVoicemail(
                id = id,
                phoneNumber = counterparty,
                timestampMillis = newest.longAt(ITEM_TS)?.let { normalizeTimestampMillis(it) } ?: 0L,
                durationSeconds = newest.longAt(ITEM_DURATION) ?: 0L,
                transcript = newest.transcriptText(),
                audioUrl = newest.strAt(ITEM_AUDIO_URL)?.takeIf { it.isNotBlank() },
                read = newest.longAt(ITEM_VM_READ) != VM_READ_UNREAD,
            )
        }.distinctBy { it.id }
    }

    fun parseSipRegisterInfo(body: String): GvSipRegisterInfo {
        val root = parseOrNull(body) ?: return GvSipRegisterInfo(null, emptyList())
        val phone = firstPhone(root)
        // The tail carries long opaque credential strings (~56/24 chars in the capture).
        val creds = allStrings(root).filter { it.length in 20..512 && !looksLikePhone(it) }
        return GvSipRegisterInfo(phoneNumber = phone, credentials = creds)
    }

    // ------------------------------------------------------------------
    // Positional wire-shape mapping (confirmed from live api2thread/list bodies)
    //
    // record: ["t.<num>"|"c.<id>", state, [ item, item, ... ], null, [[num,...]], ...]
    // item:   [itemId, tsMillis, ownNumber, [counterparty,...], type, read, ..,
    //          duration@8, bodyText@9, .., counterparty@15, .., "t.<id>"|"c.<id>"]
    // Items are newest-first.
    // ------------------------------------------------------------------

    private const val ITEMS = 2
    internal const val ITEM_ID = 0
    internal const val ITEM_TS = 1
    internal const val ITEM_OWN = 2
    internal const val ITEM_PARTICIPANTS = 3
    internal const val ITEM_TYPE = 4
    internal const val ITEM_DURATION = 8
    internal const val ITEM_BODY = 9
    internal const val ITEM_COUNTERPARTY = 15
    internal const val ITEM_MEDIA_FLAG = 16
    /**
     * Folder-4 voicemail item slots (live capture): [ITEM_VM_TYPE] = 2 marks a
     * voicemail (regular calls carry 14); [ITEM_TRANSCRIPT] holds
     * `[confidence, [[word, null, null, wordConfidence], ...]]` or null when
     * untranscribed; [ITEM_AUDIO_URL] is the playable media URL;
     * [ITEM_VM_READ] reads 1 once played, [VM_READ_UNREAD] (8) before.
     */
    internal const val ITEM_VM_TYPE = 4
    internal const val ITEM_TRANSCRIPT = 6
    internal const val ITEM_AUDIO_URL = 11
    internal const val ITEM_VM_READ = 13
    internal const val VM_TYPE_VOICEMAIL = 2L
    internal const val VM_READ_UNREAD = 8L
    internal const val RECORD_FALLBACK_A = 4
    internal const val RECORD_FALLBACK_B = 7
    internal const val E164_LENGTH = 10
    internal const val MICROS_THRESHOLD = 1_000_000_000_000_000L
    internal const val MILLIS_PER_SECOND = 1000L
    internal const val MILLIS_THRESHOLD = 1_000_000_000_000L
    internal const val SECONDS_THRESHOLD = 1_000_000_000L

    private fun parseOrNull(body: String): JsonElement? =
        runCatching { json.parseToJsonElement(body.ifBlank { "null" }) }.getOrNull()
            ?.takeIf { it !is JsonNull }

    /**
     * Depth-first collect the outermost arrays whose first primitive element is a string with one
     * of [prefixes]. Google keys threads by `t.<number>` and calls by `c.<opaque>`. Records are
     * not recursed into once matched.
     */
    private fun findRecords(root: JsonElement, prefixes: List<String>): List<JsonArray> {
        val out = mutableListOf<JsonArray>()
        fun visit(el: JsonElement) {
            if (el is JsonArray) {
                val id = (el.firstOrNull() as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (id != null && prefixes.any { id.startsWith(it) }) {
                    out.add(el)
                    return
                }
                el.forEach { visit(it) }
            } else if (el is JsonObject) {
                el.values.forEach { visit(it) }
            }
        }
        visit(root)
        return out
    }

    private fun JsonArray.toThread(): GvThread? {
        val id = recordId(this) ?: return null
        val itemArrays = arrAt(ITEMS)?.mapNotNull { it as? JsonArray }.orEmpty()
        // SMS thread ids embed the counterparty ("t.+1415..."/"t.37407"); calls don't.
        val idNumber = id.removePrefix("t.").takeIf { it != id && (looksLikePhone(it) || it.all { c -> c.isDigit() }) }
        val newest = itemArrays.firstOrNull()
        val counterparty = idNumber ?: counterpartyOf(this, newest) ?: ""
        val messages = itemArrays.mapIndexedNotNull { index, item ->
            item.toMessage(id, counterparty, index)
        }.sortedBy { it.timestampMillis }
        val newestMsg = messages.maxByOrNull { it.timestampMillis }
        return GvThread(
            id = id,
            phoneNumber = counterparty,
            displayName = null,
            snippet = newestMsg?.text.orEmpty(),
            timestampMillis =
                newestMsg?.timestampMillis ?: (newest?.longAt(ITEM_TS)?.let { normalizeTimestampMillis(it) } ?: 0L),
            unreadCount = 0,
            messages = messages,
        )
    }

    private fun JsonArray.toMessage(threadId: String, threadCounterparty: String, index: Int): GvMessage? {
        val ts = longAt(ITEM_TS)?.let { normalizeTimestampMillis(it) } ?: return null
        val counterparty = (arrAt(ITEM_PARTICIPANTS)?.strAt(0))
            ?.takeIf { it.isNotBlank() }
            ?: threadCounterparty
        // Direction is the type field: 10 = inbound SMS, 11 = outbound SMS (confirmed from the wire).
        val type = longAt(ITEM_TYPE)
        val outgoing = type == 11L
        val mediaUrls = this.mediaUrlsIn()
        val hasMedia = mediaUrls.isNotEmpty() || hasMediaMetadata()
        return GvMessage(
            id = strAt(ITEM_ID) ?: "$threadId#$index",
            threadId = threadId,
            phoneNumber = counterparty,
            text = this.normalizedBodyText(hasMedia),
            timestampMillis = ts,
            outgoing = outgoing,
            read = true,
            mediaUrls = mediaUrls,
            hasMedia = hasMedia,
        )
    }

    /** Counterparty from a record: newest item participants, then record-level participant slots. */
    private fun counterpartyOf(record: JsonArray, newest: JsonArray?): String? {
        (newest?.arrAt(ITEM_PARTICIPANTS)?.strAt(0))?.takeIf { it.isNotBlank() }?.let { return it }
        (record.arrAt(RECORD_FALLBACK_A)?.arrAt(0)?.strAt(0))?.takeIf { it.isNotBlank() }?.let { return it }
        (record.arrAt(RECORD_FALLBACK_B)?.strAt(0))?.takeIf { it.isNotBlank() }?.let { return it }
        val fromId = recordId(record)?.removePrefix("t.")
        if (fromId != null && (looksLikePhone(fromId) || fromId.all { it.isDigit() })) return fromId
        return null
    }


    private val PHONE = Regex("^\\+?[0-9]{7,15}$")
    fun looksLikePhone(raw: String): Boolean = PHONE.matches(raw.replace(" ", "").replace("-", ""))

    /** First phone-shaped string, optionally skipping the account's own number. */
    private fun firstPhone(el: JsonElement, exclude: String? = null): String? {
        val normalizedExclude = exclude?.let { normalizePhone(it) }
        return allStrings(el).firstOrNull {
            looksLikePhone(it) && (normalizedExclude == null || normalizePhone(it) != normalizedExclude)
        }
    }

    private fun normalizePhone(raw: String): String = raw.filter { it.isDigit() }.takeLast(E164_LENGTH)

    /** GV timestamps appear as µs (16 digits), ms (13), or s (10); normalize to millis. */
    fun normalizeTimestampMillis(raw: Long): Long = when {
        raw <= 0 -> 0
        raw >= MICROS_THRESHOLD -> raw / MILLIS_PER_SECOND
        raw >= MILLIS_THRESHOLD -> raw
        raw >= SECONDS_THRESHOLD -> raw * MILLIS_PER_SECOND
        else -> 0
    }

}

internal fun allStrings(el: JsonElement): List<String> {
    val out = mutableListOf<String>()
    fun visit(e: JsonElement) {
        when (e) {
            is JsonArray -> e.forEach { visit(it) }
            is JsonObject -> e.values.forEach { visit(it) }
            is JsonPrimitive -> if (e.isString) out.add(e.content)
        }
    }
    visit(el)
    return out
}
