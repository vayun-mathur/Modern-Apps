package com.vayunmathur.maps.util

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.int

/**
 * The client half of incremental map updates: the `planet5.manifest.json`
 * model, the on-disk overlay layout, and the queries the Settings UI and the
 * check worker drive.
 *
 * Layout under the app's external files dir (next to `basemap.mamaps`):
 * ```
 * basemap.mamaps                 the immutable base (never rewritten)
 * overlay/manifest.json          the pinned manifest (last applied check)
 * overlays/<seq:04d>/overlay-<n:04d>.mamaps   downloaded segments
 * ```
 * Segments download through the existing `.part` + Range resume path
 * (`runDownloadsCore` with per-segment SHA-256), sequentially, so peak
 * transient disk is one segment — never old+new full worlds. The base is
 * never copied or patched; the version switch to `(base, seq)` is an atomic
 * handle-set swap in the Phase 3 reader work.
 *
 * Manifest JSON is parsed by hand (org.json ships on every device): the
 * schema is small and a new dependency for one file is not worth it.
 */
object MapOverlay {
    /** Where [MapTileCache.BASEMAP_ARCHIVE_FILE] is checked for updates. */
    const val MANIFEST_URL = MapTileCache.BASEMAP_MANIFEST_URL

    /** Pinned manifest filename (under [OVERLAY_ROOT]). */
    const val MANIFEST_FILE = "manifest.json"

    /** Overlay root dir name (under external files). */
    const val OVERLAY_ROOT = "overlays"

    /** Manifest format version this client understands. */
    const val MANIFEST_VERSION = 1

    /** One overlay segment reference from the manifest. */
    data class SegmentRef(
        val url: String,
        val len: Long,
        val sha256: String,
        val seq: Int,
    )

    /** One independently chunked sidecar section (large kinds; empty today). */
    data class SectionRef(
        val kind: Int,
        val url: String,
        val len: Long,
        val sha256: String,
        val chunkSize: Long,
        val chunkHashes: List<String>,
    )

    /** Parsed manifest: base pin + overlay sequence + what to fetch. */
    data class Manifest(
        val version: Int,
        val baseUrl: String,
        val baseBuildId: Long,
        val baseLen: Long,
        val overlaySeq: Long,
        val overlayBuildId: Long,
        val segments: List<SegmentRef>,
        val sections: List<SectionRef>,
        /** Deleted tile ids (answered absent even though the base holds them). */
        val tombstones: List<Long>,
        val minClient: Int,
    )

    /**
     * Parse [text] into a [Manifest], or null when it is not a manifest this
     * client can apply (wrong version, missing fields, min_client above us).
     * Never throws: a bad manifest is "no update", not a crash.
     */
    fun parse(text: String): Manifest? = runCatching {
        val root = Json.parseToJsonElement(text).jsonObject
        val version = root.getValue("version").jsonPrimitive.int
        if (version != MANIFEST_VERSION) return null
        val minClient = root["min_client"]?.jsonPrimitive?.int ?: 1
        if (minClient > MANIFEST_VERSION) return null
        val base = root.getValue("base").jsonObject
        val segments = root.getValue("segments").jsonArray.toSegments()
        val sections = root["sections"]?.jsonArray?.toSections().orEmpty()
        val tombstones = root["tombstones"]?.jsonArray
            ?.map { it.jsonPrimitive.long }.orEmpty()
        Manifest(
            version = version,
            baseUrl = base.getValue("url").jsonPrimitive.content,
            baseBuildId = parseBuildId(base.getValue("build_id").jsonPrimitive.content),
            baseLen = base.getValue("len").jsonPrimitive.long,
            overlaySeq = root.getValue("overlay_seq").jsonPrimitive.long,
            overlayBuildId = parseBuildId(root.getValue("overlay_build_id").jsonPrimitive.content),
            segments = segments,
            sections = sections,
            tombstones = tombstones,
            minClient = minClient,
        )
    }.getOrNull()

    private fun JsonArray.toSegments(): List<SegmentRef> {
        val out = ArrayList<SegmentRef>(size)
        for (element in this) {
            val s = element.jsonObject
            out.add(
                SegmentRef(
                    url = s.getValue("url").jsonPrimitive.content,
                    len = s.getValue("len").jsonPrimitive.long,
                    sha256 = s.getValue("sha256").jsonPrimitive.content,
                    seq = s.getValue("seq").jsonPrimitive.int,
                ),
            )
        }
        return out.sortedBy { it.seq }
    }

    private fun JsonArray.toSections(): List<SectionRef> {
        val out = ArrayList<SectionRef>(size)
        for (element in this) {
            val s = element.jsonObject
            val hashes = s.getValue("chunk_hashes").jsonArray
            out.add(
                SectionRef(
                    kind = s.getValue("kind").jsonPrimitive.int,
                    url = s.getValue("url").jsonPrimitive.content,
                    len = s.getValue("len").jsonPrimitive.long,
                    sha256 = s.getValue("sha256").jsonPrimitive.content,
                    chunkSize = s.getValue("chunk_size").jsonPrimitive.long,
                    chunkHashes = List(hashes.size) { hashes[it].jsonPrimitive.content },
                ),
            )
        }
        return out.sortedBy { it.kind }
    }

    /**
     * A `0x…` hex build id from the manifest, or 0 when it does not parse.
     * Build ids are opaque equality tokens here (match the base or refuse).
     */
    fun parseBuildId(raw: String): Long =
        raw.removePrefix("0x").removePrefix("0X").toLongOrNull(BUILD_ID_RADIX) ?: 0L

    /** Build ids are hex in the manifest and in logs. */
    const val BUILD_ID_RADIX = 16

    /** On-disk file for segment [seg] of overlay [overlaySeq] under [root]. */
    fun segmentFile(root: File, overlaySeq: Long, seg: SegmentRef): File =
        File(File(root, "$OVERLAY_ROOT/%04d".format(overlaySeq)), "overlay-%04d.mamaps".format(seg.seq))

    /** Whether every segment of [manifest] is present and length-plausible. */
    fun isApplied(root: File, manifest: Manifest): Boolean =
        manifest.segments.all { seg ->
            val f = segmentFile(root, manifest.overlaySeq, seg)
            f.isFile && f.length() == seg.len
        }

    /** Segments of [manifest] still missing or short on disk. */
    fun missing(root: File, manifest: Manifest): List<SegmentRef> =
        manifest.segments.filter { seg ->
            val f = segmentFile(root, manifest.overlaySeq, seg)
            !f.isFile || f.length() != seg.len
        }

    /**
     * Total bytes still to fetch for [manifest] (missing segments only).
     * What the Settings row shows as pending.
     */
    fun pendingBytes(root: File, manifest: Manifest): Long =
        missing(root, manifest).sumOf { it.len }

    /** The pinned manifest on disk, or null when no check ever applied. */
    fun pinned(root: File): Manifest? {
        val f = File(File(root, OVERLAY_ROOT), MANIFEST_FILE)
        if (!f.isFile) return null
        return runCatching { parse(f.readText()) }.getOrNull()
    }

    /** Pin [text] (already parsed) as the applied manifest. */
    fun pin(root: File, text: String) {
        val dir = File(root, OVERLAY_ROOT)
        dir.mkdirs()
        File(dir, MANIFEST_FILE).writeText(text)
    }

    /**
     * Crash recovery for the rebase two-rename swap (`current` <- `.next`
     * via `.prev`). States:
     * - `.prev` + current: swap completed before cleanup → drop `.prev`.
     * - `.prev`, no current: crashed between renames → restore `.prev`.
     * - `.next` alone: crashed before any rename → leave for resume.
     */
    fun recoverRebaseCrash(root: File, baseName: String) {
        val current = File(root, baseName)
        val prev = File(root, "$baseName.prev")
        if (!prev.exists()) return
        if (current.exists()) {
            prev.delete()
        } else {
            prev.renameTo(current)
        }
    }
}
