package com.vayunmathur.contacts.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Phone <-> watch contacts protocol.
 *
 * SINGLE SOURCE OF TRUTH, DELIBERATELY DUPLICATED: Gradle cannot let
 * one application module depend on another, so `:contacts-wear`
 * (applicationId `com.vayunmathur.contacts`, same debug/release signing as the
 * phone app) carries a byte-identical copy under
 * `com.vayunmathur.contacts.wear.domain`. Change both copies together; each
 * side's tests pin the same golden JSON.
 *
 * Large-address-book honesty: the watch does NOT get the whole book verbatim.
 * [WearSnapshotBuilder] caps the snapshot at [WearContactsProtocol.MAX_CONTACTS]
 * contacts (favourites first, then A-Z), at most 3 phones + 3 emails each, with
 * every field truncated. The manifest says how many contacts exist vs how many
 * were sent, so the watch can show "1,000 of 4,213 contacts" instead of
 * pretending the rest do not exist. Each chunk is one WearLink data item
 * (transport-chunked again underneath by :library:wearlink); chunks arrive
 * in any order and are only applied once the manifest's chunk count is
 * complete.
 */
object WearContactsProtocol {
    /** WearLink appId the phone requires in the peer handshake. */
    const val CAPABILITY = "contacts_wear_companion"

    /** Data-item manifest path for the current snapshot generation. */
    const val MANIFEST_PATH = "/contacts/snapshot/manifest"

    /** Data-item prefix for chunk paths: [chunkPath] appends the chunk index. */
    const val CHUNK_PREFIX = "/contacts/snapshot/chunk/"

    /** Data-item path (watch -> phone): the watch's received-state snapshot. */
    const val WATCH_STATE_PATH = "/contacts/watch-state"

    /** Message path (watch -> phone): republish the snapshot. */
    const val REQUEST_SNAPSHOT_PATH = "/contacts/request-snapshot"

    /** Message path (watch -> phone): start this call/message on the phone. */
    const val HANDOFF_PATH = "/contacts/handoff"

    /** Hard cap on contacts ever sent to the watch. */
    const val MAX_CONTACTS = 1_000

    /** Per-contact caps: a watch row never needs a fourth number. */
    const val MAX_PHONES_PER_CONTACT = 3
    const val MAX_EMAILS_PER_CONTACT = 3

    /** Field caps, in characters, applied before serialisation. */
    const val MAX_NAME_CHARS = 80
    const val MAX_PHONE_CHARS = 40
    const val MAX_EMAIL_CHARS = 80

    /** Target ceiling for one chunk's JSON bytes (kept near the old 100 KB item cap). */
    const val MAX_CHUNK_BYTES = 50_000

    /** Chunk path for chunk [index] of the current generation. */
    fun chunkPath(index: Int): String = CHUNK_PREFIX + index

    /** Chunk index encoded by [path], or null when [path] is not a chunk path. */
    fun chunkIndexFromPath(path: String): Int? =
        path.takeIf { it.startsWith(CHUNK_PREFIX) }
            ?.removePrefix(CHUNK_PREFIX)
            ?.toIntOrNull()
            ?.takeIf { it >= 0 }

    /** Wire JSON: unknown keys ignored so a newer side never breaks an older one. */
    val json: Json = Json { ignoreUnknownKeys = true }
}

/** One watch-sized contact row: display name plus capped phone/email lists. */
@Serializable
data class WearContact(
    val id: Long,
    val name: String,
    val phones: List<String> = emptyList(),
    val emails: List<String> = emptyList(),
    val isFavorite: Boolean = false,
)

/** One data item's payload: chunk [index] of generation [generation]. */
@Serializable
data class WearContactsChunk(
    val generation: Long,
    val index: Int,
    val contacts: List<WearContact> = emptyList(),
)

/** The manifest data item: what a complete snapshot looks like. */
@Serializable
data class WearContactsManifest(
    val generation: Long,
    val chunkCount: Int,
    /** Contacts that exist on the phone (before the cap). */
    val totalContacts: Int,
    /** Contacts actually sent (after the cap). */
    val includedContacts: Int,
    /** True when [includedContacts] < [totalContacts]: the watch shows a subset. */
    val truncated: Boolean,
    val sentAtMillis: Long,
)

/** The watch's received-state snapshot ([WearContactsProtocol.WATCH_STATE_PATH]). */
@Serializable
data class WearWatchState(
    val contactCount: Int = 0,
    val generation: Long = 0L,
    val receivedAtMillis: Long = 0L,
)

/** A call/message the watch asks the phone to start (it cannot, or prefers not to). */
@Serializable
data class WearHandoffRequest(
    /** "call" or "message"; anything else is ignored by the phone. */
    val kind: String,
    /** Phone number (call/message) or email address. */
    val value: String,
) {
    companion object {
        const val KIND_CALL = "call"
        const val KIND_MESSAGE = "message"
    }
}

/** Pure snapshot builder: cap, truncate, sort, chunk. No Android types. */
object WearSnapshotBuilder {

    /**
     * Build the capped contact list: favourites first, then A-Z by name
     * (case-insensitive), ties by id. Blank-valued phones/emails are dropped,
     * duplicates collapsed, every list and field capped per the protocol.
     */
    fun select(contacts: List<WearContact>): List<WearContact> =
        contacts
            .map { normalise(it) }
            .filter { it.name.isNotBlank() || it.phones.isNotEmpty() || it.emails.isNotEmpty() }
            .distinctBy { it.id }
            .sortedWith(
                compareByDescending<WearContact> { it.isFavorite }
                    .thenBy { it.name.lowercase() }
                    .thenBy { it.id },
            )
            .take(WearContactsProtocol.MAX_CONTACTS)

    /** Manifest for [selected] out of [totalContacts] contacts, in [chunkCount] chunks. */
    fun manifest(
        generation: Long,
        totalContacts: Int,
        selected: List<WearContact>,
        chunkCount: Int,
        sentAtMillis: Long,
    ): WearContactsManifest = WearContactsManifest(
        generation = generation,
        chunkCount = chunkCount,
        totalContacts = totalContacts,
        includedContacts = selected.size,
        truncated = selected.size < totalContacts,
        sentAtMillis = sentAtMillis,
    )

    /**
     * Split [selected] into chunks whose encoded JSON stays at or under
     * [WearContactsProtocol.MAX_CHUNK_BYTES]. Greedy: a contact joins the open
     * chunk while it fits, otherwise it opens a new one. Field caps guarantee a
     * single contact always fits in an empty chunk.
     */
    fun chunk(generation: Long, selected: List<WearContact>): List<WearContactsChunk> {
        if (selected.isEmpty()) {
            return listOf(WearContactsChunk(generation, 0, emptyList()))
        }
        val chunks = mutableListOf<WearContactsChunk>()
        var current = mutableListOf<WearContact>()
        var currentBytes = emptyChunkBytes(generation, 0)
        for (contact in selected) {
            val contactBytes = encodedSize(contact)
            if (current.isNotEmpty() &&
                currentBytes + contactBytes + 1 > WearContactsProtocol.MAX_CHUNK_BYTES
            ) {
                chunks += WearContactsChunk(generation, chunks.size, current)
                current = mutableListOf()
                currentBytes = emptyChunkBytes(generation, chunks.size)
            }
            current += contact
            currentBytes += contactBytes + 1 // comma between array elements
        }
        if (current.isNotEmpty()) {
            chunks += WearContactsChunk(generation, chunks.size, current)
        }
        return chunks
    }

    /** Serialised sizes are measured, never guessed: encode and count UTF-8 bytes. */
    private fun encodedSize(contact: WearContact): Int =
        WearContactsProtocol.json.encodeToString(contact).encodeToByteArray().size

    private fun emptyChunkBytes(generation: Long, index: Int): Int =
        WearContactsProtocol.json
            .encodeToString(WearContactsChunk(generation, index, emptyList()))
            .encodeToByteArray().size

    private fun normalise(contact: WearContact): WearContact = WearContact(
        id = contact.id,
        name = contact.name.trim().take(WearContactsProtocol.MAX_NAME_CHARS),
        phones = contact.phones
            .map { it.trim().take(WearContactsProtocol.MAX_PHONE_CHARS) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(WearContactsProtocol.MAX_PHONES_PER_CONTACT),
        emails = contact.emails
            .map { it.trim().take(WearContactsProtocol.MAX_EMAIL_CHARS) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(WearContactsProtocol.MAX_EMAILS_PER_CONTACT),
        isFavorite = contact.isFavorite,
    )
}

/** In-memory search over the watch's local copy. Same token rule as the phone list. */
object WearContactSearch {

    /**
     * Every whitespace-separated token must match the name, a phone, or an email.
     * Digit tokens also match against the digits-only form of each phone, so
     * "415 555" finds "+1 (415) 555-0100". Blank query returns all contacts,
     * in the order given (the store keeps snapshot order).
     */
    fun filter(contacts: List<WearContact>, query: String): List<WearContact> {
        val tokens = query.trim().lowercase().split(WHITESPACE).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return contacts
        return contacts.filter { contact -> tokens.all { token -> matches(contact, token) } }
    }

    private fun matches(contact: WearContact, token: String): Boolean {
        if (contact.name.lowercase().contains(token)) return true
        val tokenDigits = token.filter { it.isDigit() }
        return contact.phones.any { phone ->
            phone.lowercase().contains(token) ||
                (tokenDigits.isNotEmpty() && phone.filter { it.isDigit() }.contains(tokenDigits))
        } || contact.emails.any { it.lowercase().contains(token) }
    }

    private val WHITESPACE = Regex("\\s+")
}

/** What the watch can honestly offer for one contact, given this device. */
enum class WearAction {
    /** Place the call on the watch itself (it has telephony + a dialer). */
    CALL_ON_WATCH,

    /** Ask the phone to place the call (watch cannot, phone is reachable). */
    CALL_VIA_PHONE,

    /** Open the watch's own SMS composer. */
    MESSAGE_ON_WATCH,

    /** Ask the phone to open its SMS composer. */
    MESSAGE_VIA_PHONE,

    /** Open the watch's own mail composer. */
    EMAIL_ON_WATCH,
}

object WearActionPolicy {

    /**
     * Actions for [contact], in display order. A button exists only when the
     * platform can actually complete it: direct actions need a local handler,
     * phone-handoff actions need a reachable phone. No handler + no phone
     * means no button — never a dead tap. Email never hands off (the phone's
     * composer is not a better answer than silence here; the address is shown
     * as text so it can be read aloud / typed elsewhere).
     */
    fun actionsFor(
        contact: WearContact,
        hasTelephony: Boolean,
        hasSmsHandler: Boolean,
        hasEmailHandler: Boolean,
        phoneReachable: Boolean,
    ): List<WearAction> {
        val actions = mutableListOf<WearAction>()
        val hasPhone = contact.phones.isNotEmpty()
        if (hasPhone && hasTelephony) actions += WearAction.CALL_ON_WATCH
        else if (hasPhone && phoneReachable) actions += WearAction.CALL_VIA_PHONE
        if (hasPhone && hasSmsHandler) actions += WearAction.MESSAGE_ON_WATCH
        else if (hasPhone && phoneReachable) actions += WearAction.MESSAGE_VIA_PHONE
        if (contact.emails.isNotEmpty() && hasEmailHandler) actions += WearAction.EMAIL_ON_WATCH
        return actions
    }
}

/** Phone-side presence of the watch companion, as the phone UI reports it. */
enum class WearPresence {
    /** No reachable node advertises the wear capability (or no WearLink session). */
    NO_WATCH,

    /** A watch advertises the capability but has not published state yet. */
    WATCH_CONNECTED,

    /** The watch published state for the current (or an older) generation. */
    SYNCED,
}

object WearPresencePolicy {

    /** Presence from capability nodes + the last watch state heard, if any. */
    fun presence(nodeCount: Int, watchState: WearWatchState?): WearPresence = when {
        nodeCount <= 0 -> WearPresence.NO_WATCH
        watchState == null -> WearPresence.WATCH_CONNECTED
        else -> WearPresence.SYNCED
    }

    /** One-line status for the phone UI; states only what is known. */
    fun statusText(presence: WearPresence, watchState: WearWatchState?): String = when (presence) {
        WearPresence.NO_WATCH ->
            "No Wear OS watch with Contacts found. Install Contacts on the watch, then open it once."
        WearPresence.WATCH_CONNECTED ->
            "Watch found. Contacts will sync to it; open Contacts on the watch to check."
        WearPresence.SYNCED ->
            "Watch has ${watchState?.contactCount ?: 0} contacts from the last sync."
    }
}
