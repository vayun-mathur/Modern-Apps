package com.vayunmathur.maps.util

import com.vayunmathur.library.log.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Load the POI side files out of a single-archive `.mamaps` file.
 *
 * The archive carries the same payloads as the side files (`poi_index`,
 * `poi_names`, and the optional `poi_attrs` / `poi_spatial` / `poi_name_index`
 * / `poi_region` / `poi_admin` sections, kinds 8-12, 15 and 16) after the tile data, each 8-byte aligned and named by
 * the section directory + `MAMA8` footer. This reads the container (tile
 * header, footer, directory) with positional reads, maps each POI section on
 * its own, then runs the same validators the multi-file path runs
 * ([PoiIndex.attrsFromBuffer] and siblings) — only the mapping step differs,
 * so the checks cannot drift apart.
 *
 * Per-section mappings, not one whole-file mapping: a `MappedByteBuffer` is
 * capped at 2 GiB, and the planet archive is ~80 GB with its POI sections at
 * byte offsets past 70 GB. A whole-file mapping silently truncated at 2 GiB,
 * so the declared `file_len` never matched and the offline POI index never
 * loaded from a planet archive. Each POI section is far below the cap
 * (planet4: attrs 1.19 GB, the largest), and a mapping stays valid after its
 * channel closes, so each section buffer pins itself.
 *
 * Every access the queries make is absolute (`getInt`, `get`, duplicate +
 * position for bulk), so a section reads exactly as its side file would: all
 * payload offsets are section-relative either way.
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
    const val KIND_REGION = 15
    const val KIND_ADMIN = 16

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
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                raf.channel.use { ch -> buildMapped(ch, overrideDir(file)) }
            }
        }.getOrElse { archiveFailure(it) }
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

    private fun buildMapped(whole: FileChannel, overrides: File?): PoiIndex.Mapped? {
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
        whole: FileChannel,
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
        val region = overrideOrBase(overrides, KIND_REGION, sections, whole)
            ?.let { regionFromBuffer(it, count) }
        val admin = overrideOrBase(overrides, KIND_ADMIN, sections, whole)?.let { adminFromBuffer(it) }
        return mappedOf(indexBuf, namesBuf, count, attrs, grid, words, region, admin)
    }

    /** The [PoiIndex.Mapped] for already-validated section buffers (absent ones degrade). */
    @Suppress("LongParameterList")
    private fun mappedOf(
        indexBuf: MappedByteBuffer,
        namesBuf: MappedByteBuffer,
        count: Int,
        attrs: Pair<MappedByteBuffer, Int>?,
        grid: PoiIndex.Grid?,
        words: Pair<MappedByteBuffer, Int>?,
        region: MappedByteBuffer?,
        admin: AdminTable?,
    ): PoiIndex.Mapped {
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
            region = region,
            admin = admin,
        ).also {
            Log.debug(
                TAG,
                "Loaded $count POI records from archive, " +
                    "grid=${it.cellCount} cells, words=${it.entryCount}, admin=${admin?.count ?: 0}",
            )
        }
    }

    private fun slice(whole: FileChannel, sections: Map<Int, Section>, kind: Int): MappedByteBuffer? {
        val s = sections[kind] ?: return null
        return mapSection(whole, s)
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
        whole: FileChannel,
    ): MappedByteBuffer? {
        if (overrides != null) {
            validatedOverride(overrides, kind)?.let { return it }
        }
        return slice(whole, sections, kind)
    }

    /**
     * A little-endian mapping of `[offset, offset + len)`.
     *
     * Absolute gets on it are section-relative, matching what the validators
     * expect. A section past the 2 GiB per-mapping cap is refused (degrades
     * as absent); its absolute offset may be anywhere in the file.
     */
    private fun mapSection(whole: FileChannel, s: Section): MappedByteBuffer {
        require(s.len <= Int.MAX_VALUE) { "section of ${s.len} bytes is past the 2 GiB mapping cap" }
        return whole.map(FileChannel.MapMode.READ_ONLY, s.offset, s.len)
            .also { it.order(ByteOrder.LITTLE_ENDIAN) }
    }

    /** [len] bytes at absolute [offset], little-endian, or null on a short read. */
    private fun readAt(ch: FileChannel, offset: Long, len: Int): ByteBuffer? {
        val buf = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN)
        var at = offset
        while (buf.hasRemaining()) {
            val n = ch.read(buf, at)
            if (n <= 0) return null
            at += n
        }
        buf.flip()
        return buf
    }

    /**
     * The section directory as kind -> payload extent, or null when corrupt.
     *
     * Mirrors `tilecodec`'s `ArchiveView::parse` container checks — footer
     * magic, directory fit, build-id equality, per-entry bounds/alignment and
     * overlap — so a corrupt container is refused here rather than mapped
     * into. Per-kind magic/version/count agreement stays in the existing
     * validators, which see the same bytes either way. Unknown kinds are kept
     * in the map (a newer writer's section) and simply never asked for.
     */
    private fun parseSections(ch: FileChannel): Map<Int, Section>? {
        val size = ch.size()
        if (!hasContainer(ch, size)) return null
        val footer = readFooter(ch, size - FOOTER_LEN) ?: return null
        val out = readDirectory(ch, footer) ?: return null
        return out.takeUnless { sectionsOverlap(it.values.toList()) }
    }

    /** The directory's entries, or null when its count or padded length is off. */
    private fun readDirectory(ch: FileChannel, footer: Footer): Map<Int, Section>? {
        val dir = readAt(ch, footer.dirOffset, footer.dirLen.toInt()) ?: return null
        val count = dir.getInt(0)
        val aligned = (DIR_HEADER_LEN + count.coerceIn(0, MAX_SECTIONS) * ENTRY_LEN + ALIGN_MASK) /
            ALIGN_BYTES * ALIGN_BYTES
        if (count !in 0..MAX_SECTIONS || footer.dirLen != aligned.toLong()) return null
        return readEntries(dir, count, footer.dirOffset)
    }

    /** Header + footer magic present, and the declared length agrees. */
    private fun hasContainer(ch: FileChannel, size: Long): Boolean {
        if (size < MIN_CONTAINER_BYTES) return false
        val head = readAt(ch, 0, HEADER_FILE_LEN_OFF + Long.SIZE_BYTES) ?: return false
        val footer = readAt(ch, size - FOOTER_LEN, FOOTER_LEN) ?: return false
        return declaresLength(head, size) && footerMatches(head, footer)
    }

    /** Whether the tile header's `file_len` is the file's real length. */
    private fun declaresLength(head: ByteBuffer, size: Long): Boolean {
        val fileLen = head.getLong(HEADER_FILE_LEN_OFF)
        if (fileLen != size) Log.status(TAG, "archive declares $fileLen bytes but is $size")
        return fileLen == size
    }

    /** Whether the footer carries the `MAMA8` magic and the header's build id. */
    private fun footerMatches(head: ByteBuffer, footer: ByteBuffer): Boolean {
        if (FOOTER_MAGIC.indices.any { footer.get(it) != FOOTER_MAGIC[it] }) {
            Log.debug(TAG, "not a single archive (bad MAMA8 magic)")
            return false
        }
        if (footer.getLong(FOOTER_BUILD_OFF) != head.getLong(HEADER_BUILD_ID_OFF)) {
            Log.status(TAG, "archive footer build disagrees with its header")
            return false
        }
        return true
    }

    private data class Footer(val dirOffset: Long, val dirLen: Long)

    private fun readFooter(ch: FileChannel, footerAt: Long): Footer? {
        val footer = readAt(ch, footerAt, FOOTER_LEN) ?: return null
        val dirOffset = footer.getLong(FOOTER_DIR_OFF)
        val dirLen = footer.getLong(FOOTER_LEN_OFF)
        if (dirOffset < 0 || dirLen < DIR_HEADER_LEN || dirOffset + dirLen > footerAt) return null
        // The directory itself is tiny (count is capped), wherever it sits in the file.
        if (dirLen > DIR_HEADER_LEN + MAX_SECTIONS * ENTRY_LEN + ALIGN_BYTES) return null
        return Footer(dirOffset, dirLen)
    }

    private fun readEntries(dir: ByteBuffer, count: Int, dirOffset: Long): Map<Int, Section>? {
        val out = LinkedHashMap<Int, Section>()
        var prevKind = -1
        for (n in 0 until count) {
            val at = DIR_HEADER_LEN + n * ENTRY_LEN
            val kind = dir.get(at).toInt() and BYTE_MASK
            if (dir.get(at + ENTRY_PAD_1) != 0.toByte() ||
                dir.get(at + ENTRY_PAD_2) != 0.toByte() ||
                dir.get(at + ENTRY_PAD_3) != 0.toByte()
            ) {
                return null
            }
            if (dir.getInt(at + ENTRY_RESERVED_OFF) != 0) return null
            if (kind == 0 || kind <= prevKind) return null
            prevKind = kind
            val offset = dir.getLong(at + ENTRY_OFFSET_OFF)
            val len = dir.getLong(at + ENTRY_LEN_OFF)
            if (len <= 0 || offset < 0 || offset % ALIGN != 0L) return null
            // Every payload sits before the directory; checked without overflow.
            if (offset > dirOffset || len > dirOffset - offset) return null
            out[kind] = Section(offset, len)
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
