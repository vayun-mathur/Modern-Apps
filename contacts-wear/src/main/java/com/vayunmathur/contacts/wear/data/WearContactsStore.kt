package com.vayunmathur.contacts.wear.data

import android.content.Context
import com.vayunmathur.contacts.wear.domain.WearContact
import com.vayunmathur.contacts.wear.domain.WearContactsChunk
import com.vayunmathur.contacts.wear.domain.WearContactsManifest
import com.vayunmathur.contacts.wear.domain.WearContactsProtocol
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString

/** The watch's local copy: contacts + the manifest they arrived under. */
data class WearSnapshot(
    val contacts: List<WearContact> = emptyList(),
    val manifest: WearContactsManifest? = null,
)

/**
 * Collects chunks for one generation and yields the full contact list only when
 * the manifest's chunk count is present and every chunk carries that generation.
 * Pure (no Android) so reassembly is unit-testable.
 */
class WearSnapshotReassembler {
    private var manifest: WearContactsManifest? = null
    private val chunks = mutableMapOf<Int, WearContactsChunk>()

    /** Offer the manifest; returns the completed snapshot, or null while incomplete. */
    fun onManifest(newManifest: WearContactsManifest): WearSnapshot? {
        if (manifest != null && manifest?.generation != newManifest.generation) chunks.clear()
        manifest = newManifest
        return completeOrNull()
    }

    /** Offer one chunk; stale generations are ignored. */
    fun onChunk(chunk: WearContactsChunk): WearSnapshot? {
        val current = manifest ?: run {
            chunks[chunk.index] = chunk
            return null
        }
        if (chunk.generation != current.generation) return null
        chunks[chunk.index] = chunk
        return completeOrNull()
    }

    private fun completeOrNull(): WearSnapshot? {
        val current = manifest ?: return null
        if (chunks.size < current.chunkCount) return null
        val ordered = (0 until current.chunkCount).map { chunks[it] ?: return null }
        if (ordered.any { it.generation != current.generation }) return null
        return WearSnapshot(ordered.flatMap { it.contacts }, current)
    }
}

/**
 * The watch's offline copy, persisted as one JSON file in internal storage.
 * Reads/writes never throw to the UI: a corrupt file reads as an empty book,
 * and the next complete snapshot replaces it atomically (tmp + rename).
 */
class WearContactsStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val reassembler = WearSnapshotReassembler()

    private val _snapshot = MutableStateFlow(load())

    /** Current local snapshot; empty until the first complete sync arrives. */
    val snapshot: StateFlow<WearSnapshot> = _snapshot.asStateFlow()

    /** Feed a manifest DataItem payload; persists when the snapshot completes. */
    fun onManifest(manifest: WearContactsManifest) {
        reassembler.onManifest(manifest)?.let { persist(it) }
    }

    /** Feed a chunk DataItem payload; persists when the snapshot completes. */
    fun onChunk(chunk: WearContactsChunk) {
        reassembler.onChunk(chunk)?.let { persist(it) }
    }

    /** Re-read from disk (e.g. after the listener service wrote a new snapshot). */
    fun reload() {
        _snapshot.value = load()
    }

    private fun persist(snapshot: WearSnapshot) {
        runCatching {
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(WearContactsProtocol.json.encodeToString(snapshot.contacts))
            tmp.renameTo(file)
            File(file.parentFile, MANIFEST_NAME)
                .writeText(WearContactsProtocol.json.encodeToString(snapshot.manifest))
        }
        _snapshot.value = snapshot
    }

    private fun load(): WearSnapshot = runCatching {
        if (!file.exists()) return WearSnapshot()
        val contacts = WearContactsProtocol.json.decodeFromString<List<WearContact>>(
            file.readText(),
        )
        val manifestFile = File(file.parentFile, MANIFEST_NAME)
        val manifest = if (manifestFile.exists()) {
            WearContactsProtocol.json.decodeFromString<WearContactsManifest>(
                manifestFile.readText(),
            )
        } else {
            null
        }
        WearSnapshot(contacts, manifest)
    }.getOrDefault(WearSnapshot())

    companion object {
        private const val FILE_NAME = "contacts_snapshot.json"
        private const val MANIFEST_NAME = "contacts_manifest.json"
    }
}
