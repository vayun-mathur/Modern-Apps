package com.vayunmathur.maps.util

import com.vayunmathur.library.log.Log
import java.io.File
import java.nio.ByteOrder
import java.nio.MappedByteBuffer

/**
 * Load the POI side files out of a single-archive `.mamaps` file.
 *
 * The archive carries the same five payloads as the side files (`poi_index`,
 * `poi_names`, and the optional `poi_attrs` / `poi_spatial` / `poi_name_index`
 * sections, kinds 8-12) after the tile data, each 8-byte aligned and named by
 * the section directory + `MAMA8` footer. This maps the one file once and
 * slices views of it, then runs the same validators the multi-file path runs
 * ([PoiIndex.attrsFromBuffer] and siblings) — only the mapping step differs,
 * so the checks cannot drift apart.
 *
 * Every access the queries make is absolute (`getInt`, `get`, duplicate +
 * position for bulk), so a slice reads exactly as the whole file it came
 * from: all payload offsets are section-relative either way. The single
 * whole-file mapping is held in [PoiIndex.Mapped.archive], which keeps every
 * slice alive; without it a slice could outlive its mapping.
 */
object PoiArchive {
    private const val TAG = "PoiArchive"

    private const val FOOTER_LEN = 32
    private const val ENTRY_LEN = 32
    private const val DIR_HEADER_LEN = 4
    private const val ALIGN = 8L
    /** Smallest file that can hold a tile header plus the footer. */
    private const val MIN_CONTAINER_BYTES = 128 + FOOTER_LEN
    /** Offsets of the directory extent + build id within the footer. */
    private const val FOOTER_DIR_OFF = 8
    private const val FOOTER_LEN_OFF = 16
    private const val FOOTER_BUILD_OFF = 24
    /** Most directory entries accepted (a corrupt count refuses the file). */
    private const val MAX_SECTIONS = 32
    /** Directory length rounds up to the alignment. */
    private const val ALIGN_BYTES = 8
    private const val ALIGN_MASK = 7
    /** "MAMA8\0\0\0": 4D 41 4D 41 38 00 00 00. */
    private val FOOTER_MAGIC = byteArrayOf(0x4D, 0x41, 0x4D, 0x41, 0x38, 0x00, 0x00, 0x00)
    /** Mask for one unsigned byte (entry kind). */
    private const val BYTE_MASK = 0xFF
    /** Pad/reserved/offset/len offsets within one directory entry. */
    private const val ENTRY_PAD_1 = 1
    private const val ENTRY_PAD_2 = 2
    private const val ENTRY_PAD_3 = 3
    private const val ENTRY_RESERVED_OFF = 4
    private const val ENTRY_OFFSET_OFF = 8
    private const val ENTRY_LEN_OFF = 16
    /** Bytes per `poi_index.bin` record (shared with [PoiIndex]). */
    private const val INDEX_RECORD_BYTES = 14

    /** Section kinds, mirroring `tilecodec::mamaps::archive::ARCHIVE_KIND_*`. */
    const val KIND_INDEX = 8
    const val KIND_NAMES = 9
    const val KIND_ATTRS = 10
    const val KIND_SPATIAL = 11
    const val KIND_WORDS = 12

    /** Tile-header offsets this needs: `build_id` at 16, `file_len` at 24. */
    private const val HEADER_BUILD_ID_OFF = 16
    private const val HEADER_FILE_LEN_OFF = 24

    private data class Section(val offset: Long, val len: Long)

    /**
     * Map [file] once and build the POI [PoiIndex.Mapped], or null when the
     * file is not a single archive, disagrees with itself, or carries no POI
     * index section. Optional sections absent from the directory degrade
     * exactly as absent files do: no attributes, Morton fallback, scan
     * fallback.
     *
     * Section overrides (incremental updates): when the applied overlay pins
     * chunked sidecar sections, `<root>/overlays/sections/<kind>.section`
     * files replace the base slices for those kinds. Validators run
     * unchanged — an override that fails validation degrades exactly as an
     * absent section does, never as corrupt base bytes.
     */
    internal fun openArchive(file: File): PoiIndex.Mapped? {
        if (!file.isFile) return null
        return runCatching { buildMapped(PoiIndex.mapReadOnly(file), overrideDir(file)) }
            .getOrElse { archiveFailure(it) }
    }

    /**
     * The section-override dir for [baseFile] (`overlays/sections/` beside
     * it), or null when no applied overlay pins sections. Files are named
     * `<kind>.section` (e.g. `13.section` for transit).
     */
    private fun overrideDir(baseFile: File): File? {
        val root = baseFile.parentFile ?: return null
        val dir = File(File(root, MapOverlay.OVERLAY_ROOT), "sections")
        return dir.takeIf { it.isDirectory }
    }

    /** Log a mapping failure and yield null (the archive path degrades to side files). */
    private fun archiveFailure(cause: Throwable): PoiIndex.Mapped? {
        // Only the failures a corrupt/truncated file can actually produce reach
        // here: I/O mapping it, overrunning a buffer, or a bounds check firing.
        // Anything else is a programming error and must keep propagating.
        when (cause) {
            is java.io.IOException,
            is IndexOutOfBoundsException,
            is IllegalArgumentException,
            -> {
                Log.status(TAG, "Failed to map POI sections from archive", cause)
                return null
            }
            else -> throw cause
        }
    }

    private fun buildMapped(whole: MappedByteBuffer, overrides: File?): PoiIndex.Mapped? {
        whole.order(ByteOrder.LITTLE_ENDIAN)
        val sections = parseSections(whole) ?: return null
        // Overrides validate through the same validators as base slices; a
        // failed override degrades to the base slice, never to corrupt bytes.
        // Index + names override as a pair (ordinal join): one without the
        // other falls back to the base pair, never a mixed vintage.
        val overrideIndex = overrides?.let { validatedOverride(it, KIND_INDEX) }
        val overrideNames = overrides?.let { validatedOverride(it, KIND_NAMES) }
        val paired = (overrideIndex == null) == (overrideNames == null)
        if (!paired) {
            Log.status(TAG, "half-paired POI override; using base index+names")
        }
        val indexBuf = overrideIndex.takeIf { paired } ?: slice(whole, sections, KIND_INDEX) ?: run {
            Log.status(TAG, "archive carries no POI index section")
            return null
        }
        val namesBuf = overrideNames.takeIf { paired } ?: slice(whole, sections, KIND_NAMES) ?: run {
            Log.status(TAG, "archive carries a POI index but no POI names")
            return null
        }
        if (indexBuf.capacity() == 0 || namesBuf.capacity() == 0) {
            Log.status(TAG, "archive POI index or names section is empty")
            return null
        }
        return mappedFromSections(whole, sections, indexBuf, namesBuf, overrides)
    }

    /**
     * The override file for [kind] as a validated buffer, or null when absent
     * or invalid (caller falls back to base). The returned duplicate shares
     * the file mapping and pins it exactly like a base slice: once stored in
     * [PoiIndex.Mapped] fields it lives as long as they do, so no separate
     * holder is needed.
     */
    private fun validatedOverride(dir: File, kind: Int): MappedByteBuffer? {
        val file = File(dir, "$kind.section")
        if (!file.isFile || file.length() == 0L || file.length() > Int.MAX_VALUE) return null
        return runCatching {
            val mapped = PoiIndex.mapReadOnly(file)
            // Minimal shape check now; the per-kind validators in
            // mappedFromSections run the full agreement (magic/count).
            if (mapped.capacity() == 0) return null
            (mapped.duplicate() as MappedByteBuffer).also { it.order(ByteOrder.LITTLE_ENDIAN) }
        }.getOrNull()
    }

    private fun mappedFromSections(
        whole: MappedByteBuffer,
        sections: Map<Int, Section>,
        indexBuf: MappedByteBuffer,
        namesBuf: MappedByteBuffer,
        overrides: File?,
    ): PoiIndex.Mapped {
        val count = indexBuf.capacity() / INDEX_RECORD_BYTES
        val attrs = overrideOrBase(overrides, KIND_ATTRS, sections, whole)
            ?.let { PoiIndex.attrsFromBuffer(it, count) }
        val grid = overrideOrBase(overrides, KIND_SPATIAL, sections, whole)
            ?.let { PoiIndex.spatialFromBuffer(it, count) }
        val words = overrideOrBase(overrides, KIND_WORDS, sections, whole)
            ?.let { PoiIndex.nameIndexFromBuffer(it, count) }
        return PoiIndex.Mapped(
            index = indexBuf,
            names = namesBuf,
            namesLen = namesBuf.capacity(),
            count = count,
            attrs = attrs?.first,
            attrsBlobStart = attrs?.second ?: 0,
            spatial = grid?.buf,
            cellCount = grid?.cellCount ?: 0,
            lat0E7 = grid?.lat0E7 ?: 0,
            lon0E7 = grid?.lon0E7 ?: 0,
            cellE7 = grid?.cellE7 ?: 0,
            cols = grid?.cols ?: 0,
            nameIdx = words?.first,
            entryCount = words?.second ?: 0,
            archive = whole,
        ).also {
            Log.debug(
                TAG,
                "Loaded $count POI records from archive, " +
                    "grid=${grid?.cellCount ?: 0} cells, words=${words?.second ?: 0}",
            )
        }
    }

    private fun slice(whole: MappedByteBuffer, sections: Map<Int, Section>, kind: Int): MappedByteBuffer? {
        val s = sections[kind] ?: return null
        return sliceRaw(whole, s)
    }

    /**
     * The override buffer for [kind], or the base slice when no valid
     * override exists. Override validators are the same functions the base
     * path runs ([PoiIndex.attrsFromBuffer] and siblings) — only the mapping
     * step differs, so the checks cannot drift apart.
     */
    private fun overrideOrBase(
        overrides: File?,
        kind: Int,
        sections: Map<Int, Section>,
        whole: MappedByteBuffer,
    ): MappedByteBuffer? {
        if (overrides != null) {
            validatedOverride(overrides, kind)?.let { return it }
        }
        return slice(whole, sections, kind)
    }

    /**
     * A little-endian view of `[offset, offset + len)`.
     *
     * Absolute gets on the view are section-relative, matching what the
     * validators expect; the view shares the mapping, so no bytes are copied.
     */
    private fun sliceRaw(whole: MappedByteBuffer, s: Section): MappedByteBuffer {
        require(s.offset <= Int.MAX_VALUE && s.len <= Int.MAX_VALUE) { "section past the 2GB mapping cap" }
        val dup = whole.duplicate()
        dup.position(s.offset.toInt())
        dup.limit((s.offset + s.len).toInt())
        // slice() shares the mapping: no bytes are copied, and the parent is
        // pinned by Mapped.archive.
        return (dup.slice() as MappedByteBuffer).also { it.order(ByteOrder.LITTLE_ENDIAN) }
    }

    /**
     * The section directory as kind -> payload extent, or null when corrupt.
     *
     * Mirrors `tilecodec`'s `ArchiveView::parse` container checks — footer
     * magic, directory fit, build-id equality, per-entry bounds/alignment and
     * overlap — so a corrupt container is refused here rather than sliced
     * into. Per-kind magic/version/count agreement stays in the existing
     * validators, which see the same bytes either way.
     */
    private fun parseSections(whole: MappedByteBuffer): Map<Int, Section>? {
        whole.order(ByteOrder.LITTLE_ENDIAN)
        if (!hasContainer(whole)) return null
        val footerAt = whole.capacity() - FOOTER_LEN
        val footer = readFooter(whole, footerAt) ?: return null
        val dirRange = dirRange(whole, footer) ?: return null
        val out = readEntries(whole, dirRange) ?: return null
        if (sectionsOverlap(out.values.toList())) return null
        return out
    }

    /** Header + footer magic present, and the declared length agrees. */
    private fun hasContainer(whole: MappedByteBuffer): Boolean {
        if (whole.capacity() < MIN_CONTAINER_BYTES) return false
        val buildId = whole.getLong(HEADER_BUILD_ID_OFF)
        val fileLen = whole.getLong(HEADER_FILE_LEN_OFF)
        if (fileLen != whole.capacity().toLong()) {
            Log.status(TAG, "archive declares $fileLen bytes but is ${whole.capacity()}")
            return false
        }
        val footerAt = whole.capacity() - FOOTER_LEN
        for (i in FOOTER_MAGIC.indices) {
            if (whole.get(footerAt + i) != FOOTER_MAGIC[i]) {
                Log.debug(TAG, "not a single archive (bad MAMA8 magic)")
                return false
            }
        }
        val footerBuild = whole.getLong(footerAt + FOOTER_BUILD_OFF)
        if (footerBuild != buildId) {
            Log.status(TAG, "archive footer build disagrees with its header")
            return false
        }
        return true
    }

    private data class Footer(val dirOffset: Long, val dirLen: Long)

    private fun readFooter(whole: MappedByteBuffer, footerAt: Int): Footer? {
        val dirOffset = whole.getLong(footerAt + FOOTER_DIR_OFF)
        val dirLen = whole.getLong(footerAt + FOOTER_LEN_OFF)
        if (dirOffset < 0 || dirLen < DIR_HEADER_LEN || dirOffset + dirLen > footerAt) return null
        if (dirOffset > Int.MAX_VALUE || dirOffset + dirLen > Int.MAX_VALUE) return null
        return Footer(dirOffset, dirLen)
    }

    private fun dirRange(whole: MappedByteBuffer, footer: Footer): IntRange? {
        val count = whole.getInt(footer.dirOffset.toInt())
        if (count < 0 || count > MAX_SECTIONS) return null
        val want = DIR_HEADER_LEN + count * ENTRY_LEN
        val aligned = (want + ALIGN_MASK) / ALIGN_BYTES * ALIGN_BYTES
        if (footer.dirLen != aligned.toLong()) return null
        val at = footer.dirOffset.toInt() + DIR_HEADER_LEN
        return at until at + count * ENTRY_LEN
    }

    private fun readEntries(whole: MappedByteBuffer, range: IntRange): Map<Int, Section>? {
        val out = LinkedHashMap<Int, Section>()
        var prevKind = -1
        var at = range.first
        while (at < range.last) {
            val kind = whole.get(at).toInt() and BYTE_MASK
            if (whole.get(at + ENTRY_PAD_1) != 0.toByte() ||
                whole.get(at + ENTRY_PAD_2) != 0.toByte() ||
                whole.get(at + ENTRY_PAD_3) != 0.toByte()
            ) {
                return null
            }
            if (whole.getInt(at + ENTRY_RESERVED_OFF) != 0) return null
            if (kind <= prevKind) return null
            prevKind = kind
            val offset = whole.getLong(at + ENTRY_OFFSET_OFF)
            val len = whole.getLong(at + ENTRY_LEN_OFF)
            if (len <= 0 || offset % ALIGN != 0L) return null
            out[kind] = Section(offset, len)
            at += ENTRY_LEN
        }
        return out
    }

    /** Pairwise overlap, same refusal as the tile header's. */
    private fun sectionsOverlap(list: List<Section>): Boolean {
        for (i in list.indices) {
            for (j in i + 1 until list.size) {
                val a = list[i]
                val b = list[j]
                if (a.offset < b.offset + b.len && b.offset < a.offset + a.len) return true
            }
        }
        return false
    }
}
