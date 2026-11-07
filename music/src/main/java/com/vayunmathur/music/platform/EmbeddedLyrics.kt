package com.vayunmathur.music.platform

import android.content.Context
import android.net.Uri
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Reads embedded lyrics out of the currently playing file.
 *
 * The downloader writes synced LRC text into a `LYRICS` tag - a Vorbis comment
 * in `.opus`/`.flac`, or an iTunes `©lyr` atom in the `.m4a` fallback - and this pulls it
 * back out so the now-playing screen can scroll along. Only the metadata region at the head
 * of the file is scanned; the audio body is never read, because this runs every time the
 * track changes.
 */
object EmbeddedLyrics {

    // Large enough to clear an Opus comment header that also embeds cover art, since the
    // lyrics tag sits in that same packet behind the picture.
    private const val OGG_SCAN_BYTES = 1024 * 1024
    private const val MAX_MOOV_BYTES = 16 * 1024 * 1024

    private const val MAGIC_SNIFF_LENGTH = 12
    private const val MAGIC_TEXT_LENGTH = 4
    private const val MP4_FTYP_OFFSET = 4
    private const val BYTE_MASK = 0xff
    private const val FLAG_LAST_MASK = 0x80
    private const val BLOCK_TYPE_MASK = 0x7f
    private const val SHIFT_SHORT_HIGH = 16
    private const val SHIFT_BYTE_HIGH = 24
    private const val SHIFT_BYTE_MID = 8
    private const val INT_BYTES = 4
    private const val SHORT_HIGH_BYTE = 2
    private const val SHORT_LOW_BYTE = 3
    private const val OGG_PAGE_HEADER_MIN = 27
    private const val OGG_SEGMENT_COUNT_OFFSET = 26
    private const val OGG_SEGMENT_TERMINATOR = 255
    private const val FLAC_BLOCK_HEADER = 4
    private const val VORBIS_COMMENT_LIMIT = 10_000
    private const val MP4_BOX_HEADER = 8
    private const val MP4_LARGE_MARKER = 1L
    private const val MP4_SELF_MARKER = 0L
    private const val MP4_MIN_BOX = 8L
    private const val MP4_LARGE_HEADER = 16L
    private const val META_FULL_BOX_SKIP = 12
    private const val OPUS_TAGS_LENGTH = 8
    private const val OPUS_TAGS_SKIP = 8
    private const val VORBIS_MAGIC_LENGTH = 7
    private const val VORBIS_MAGIC_OFFSET = 1
    private const val VORBIS_MAGIC_TEXT_LENGTH = 6
    private const val DATA_BOX_HEADER_SKIP = 16
    private const val COMMENT_PACKET_INDEX = 1

    /** Returns the raw LRC/lyrics text, or null when the file carries none this can read. */
    fun read(context: Context, uri: Uri): String? = try {
        when (containerOf(context, uri)) {
            Container.OGG -> context.contentResolver.openInputStream(uri)?.use { readOgg(it) }
            Container.FLAC -> context.contentResolver.openInputStream(uri)?.use { readFlac(it) }
            Container.MP4 -> readMp4(context, uri)
            null -> null
        }
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IllegalStateException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private enum class Container { OGG, FLAC, MP4 }

    /** Sniffs the container from the first few bytes rather than trusting a URI's extension. */
    private fun containerOf(context: Context, uri: Uri): Container? {
        val magic = context.contentResolver.openInputStream(uri)?.use {
            it.readNBytesCompat(MAGIC_SNIFF_LENGTH)
        } ?: return null
        if (magic.size < MAGIC_SNIFF_LENGTH) return null
        val head = String(magic, 0, MAGIC_TEXT_LENGTH, Charsets.ISO_8859_1)
        val ftyp = String(magic, MP4_FTYP_OFFSET, MAGIC_TEXT_LENGTH, Charsets.ISO_8859_1)
        return when {
            head == "OggS" -> Container.OGG
            head == "fLaC" -> Container.FLAC
            ftyp == "ftyp" -> Container.MP4
            else -> null
        }
    }

    // ------------------------------------------------------------------
    // Ogg (Vorbis comments in the second packet)
    // ------------------------------------------------------------------

    private fun readOgg(input: InputStream): String? {
        val data = input.readNBytesCompat(OGG_SCAN_BYTES)
        var offset = 0
        var packetIndex = 0
        val packet = java.io.ByteArrayOutputStream()
        while (offset + OGG_PAGE_HEADER_MIN <= data.size) {
            val next = consumeOggPage(data, offset, packet)
            if (next == null) break
            offset = next.first
            val completed = next.second
            if (completed != null) {
                if (packetIndex == COMMENT_PACKET_INDEX) return lyricsFromCommentPacket(completed)
                packetIndex++
            }
        }
        return null
    }

    private fun consumeOggPage(
        data: ByteArray,
        offset: Int,
        packet: java.io.ByteArrayOutputStream,
    ): Pair<Int, ByteArray?>? {
        if (String(data, offset, MAGIC_TEXT_LENGTH, Charsets.ISO_8859_1) != "OggS") {
            return null
        }
        val segmentCount = data[offset + OGG_SEGMENT_COUNT_OFFSET].toInt() and BYTE_MASK
        val tableStart = offset + OGG_PAGE_HEADER_MIN
        if (tableStart + segmentCount > data.size) return null
        var payloadSize = 0
        var packetEnds = false
        for (i in 0 until segmentCount) {
            val segment = data[tableStart + i].toInt() and BYTE_MASK
            payloadSize += segment
            if (segment < OGG_SEGMENT_TERMINATOR) packetEnds = true
        }
        val payloadStart = tableStart + segmentCount
        if (payloadStart + payloadSize > data.size) return null
        packet.write(data, payloadStart, payloadSize)
        val completed = if (packetEnds) {
            val bytes = packet.toByteArray()
            packet.reset()
            bytes
        } else {
            null
        }
        return (payloadStart + payloadSize) to completed
    }

    private fun lyricsFromCommentPacket(packet: ByteArray): String? = when {
        packet.size > OPUS_TAGS_LENGTH &&
            String(packet, 0, OPUS_TAGS_LENGTH, Charsets.ISO_8859_1) == "OpusTags" ->
            lyricsFromVorbisComments(packet, OPUS_TAGS_SKIP)
        packet.size > VORBIS_MAGIC_LENGTH &&
            String(
                packet,
                VORBIS_MAGIC_OFFSET,
                VORBIS_MAGIC_TEXT_LENGTH,
                Charsets.ISO_8859_1,
            ) == "vorbis" ->
            lyricsFromVorbisComments(packet, VORBIS_MAGIC_LENGTH)
        else -> null
    }

    // ------------------------------------------------------------------
    // FLAC (Vorbis comment metadata block)
    // ------------------------------------------------------------------

    private fun readFlac(input: InputStream): String? {
        val magic = input.readNBytesCompat(MAGIC_TEXT_LENGTH)
        if (magic.size < MAGIC_TEXT_LENGTH || String(magic, Charsets.ISO_8859_1) != "fLaC") {
            return null
        }
        while (true) {
            val block = nextFlacBlock(input) ?: return null
            if (block.isComment) return lyricsFromVorbisComments(block.payload, 0)
            if (block.isLast) return null
        }
    }

    private class FlacBlock(val isLast: Boolean, val isComment: Boolean, val payload: ByteArray)

    private fun nextFlacBlock(input: InputStream): FlacBlock? {
        val header = input.readNBytesCompat(FLAC_BLOCK_HEADER)
        if (header.size < FLAC_BLOCK_HEADER) return null
        val flags = header[0].toInt() and BYTE_MASK
        val isLast = flags and FLAG_LAST_MASK != 0
        val blockType = flags and BLOCK_TYPE_MASK
        val length = ((header[1].toInt() and BYTE_MASK) shl SHIFT_SHORT_HIGH) or
            ((header[SHORT_HIGH_BYTE].toInt() and BYTE_MASK) shl SHIFT_BYTE_MID) or
            (header[SHORT_LOW_BYTE].toInt() and BYTE_MASK)
        if (length < 0 || length > OGG_SCAN_BYTES) return null
        val block = input.readNBytesCompat(length)
        if (block.size < length) return null
        return FlacBlock(isLast, blockType == FLAC_BLOCK_HEADER, block)
    }

    private fun lyricsFromVorbisComments(buf: ByteArray, start: Int): String? {
        val entries = vorbisEntries(buf, start) ?: return null
        return entries.firstNotNullOfOrNull { entry ->
            val separator = entry.indexOf('=')
            if (separator <= 0) return@firstNotNullOfOrNull null
            if (!entry.substring(0, separator).equals("LYRICS", ignoreCase = true)) {
                return@firstNotNullOfOrNull null
            }
            entry.substring(separator + 1).ifBlank { null }
        }
    }

    private fun vorbisEntries(buf: ByteArray, start: Int): List<String>? {
        var offset = start
        if (offset + INT_BYTES > buf.size) return null
        val vendorLength = readIntLe(buf, offset)
        offset += INT_BYTES + vendorLength
        if (offset + INT_BYTES > buf.size || vendorLength < 0) return null
        val count = readIntLe(buf, offset)
        offset += INT_BYTES
        if (count < 0 || count > VORBIS_COMMENT_LIMIT) return null
        val entries = ArrayList<String>(count.coerceAtMost(VORBIS_COMMENT_LIMIT))
        repeat(count) {
            val entry = readVorbisEntry(buf, offset) ?: return null
            offset = entry.second
            entries.add(entry.first)
        }
        return entries
    }

    private fun readVorbisEntry(buf: ByteArray, offset: Int): Pair<String, Int>? {
        if (offset + INT_BYTES > buf.size) return null
        val length = readIntLe(buf, offset)
        val start = offset + INT_BYTES
        if (length < 0 || start + length > buf.size) return null
        val entry = String(buf, start, length, Charsets.UTF_8)
        return entry to (start + length)
    }

    // ------------------------------------------------------------------
    // MP4 / M4A (iTunes ©lyr atom)
    // ------------------------------------------------------------------

    private fun readMp4(context: Context, uri: Uri): String? =
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { input -> readMp4(input.channel) }
        }

    private fun readMp4(channel: FileChannel): String? {
        val size = channel.size()
        var position = 0L
        while (position < size) {
            val box = readBoxRange(channel, position, size) ?: break
            if (box.isMoov) return readMoovLyrics(channel, box)
            position = box.end
        }
        return null
    }

    private class BoxRange(val isMoov: Boolean, val contentStart: Long, val end: Long)

    private fun readBoxRange(channel: FileChannel, position: Long, size: Long): BoxRange? {
        if (position + MP4_BOX_HEADER > size) return null
        val head = ByteBuffer.allocate(MP4_BOX_HEADER)
        channel.position(position)
        while (head.hasRemaining()) {
            if (channel.read(head) < 0) return null
        }
        head.flip()
        val declared = head.int.toLong() and 0xffffffffL
        val type = String(ByteArray(MAGIC_TEXT_LENGTH) { head.get() }, Charsets.ISO_8859_1)
        // A largesize box (declared == 1) carries an extra 8-byte size field,
        // so its content starts after the 16-byte header, not the usual 8.
        val contentStart = position +
            if (declared == MP4_LARGE_MARKER) MP4_LARGE_HEADER else MP4_BOX_HEADER.toLong()
        val end = boxEnd(channel, position, size, declared) ?: return null
        if (end <= position || end > size) return null
        return BoxRange(type == "moov", contentStart, end)
    }

    private fun boxEnd(channel: FileChannel, position: Long, size: Long, declared: Long): Long? {
        if (declared == MP4_LARGE_MARKER) return readLargeBoxEnd(channel, position, size)
        if (declared == MP4_SELF_MARKER) return size
        if (declared < MP4_MIN_BOX) return null
        return position + declared
    }

    private fun readLargeBoxEnd(channel: FileChannel, position: Long, size: Long): Long? {
        val ext = ByteBuffer.allocate(MP4_BOX_HEADER)
        channel.position(position + MP4_BOX_HEADER)
        while (ext.hasRemaining()) {
            if (channel.read(ext) < 0) return null
        }
        ext.flip()
        val large = ext.long
        if (large < MP4_LARGE_HEADER) return null
        return (position + large).coerceAtMost(size)
    }

    private fun readMoovLyrics(channel: FileChannel, box: BoxRange): String? {
        val payloadSize = box.end - box.contentStart
        if (payloadSize <= 0 || payloadSize > MAX_MOOV_BYTES) return null
        val buffer = ByteBuffer.allocate(payloadSize.toInt())
        channel.position(box.contentStart)
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) return null
        }
        val ilst = findIlst(buffer.array()) ?: return null
        return lyricsFromIlst(ilst)
    }

    private fun findIlst(moov: ByteArray): ByteArray? {
        fun descend(start: Int, end: Int, path: List<String>): ByteArray? {
            var offset = start
            while (offset + MP4_BOX_HEADER <= end) {
                val size = readIntBe(moov, offset)
                val type = String(moov, offset + INT_BYTES, MAGIC_TEXT_LENGTH, Charsets.ISO_8859_1)
                val boxEnd = if (size == 0) end else offset + size
                if (size < MP4_BOX_HEADER || boxEnd > end) return null
                val found = matchIlstLevel(moov, offset, boxEnd, type, path)
                if (found != null) return found
                offset = boxEnd
            }
            return null
        }
        return descend(0, moov.size, listOf("udta", "meta", "ilst"))
    }

    private fun matchIlstLevel(
        moov: ByteArray,
        offset: Int,
        boxEnd: Int,
        type: String,
        path: List<String>,
    ): ByteArray? {
        if (type != path.first()) return null
        val contentStart = if (type == "meta") offset + META_FULL_BOX_SKIP else offset + MP4_BOX_HEADER
        if (path.size == 1) {
            return moov.copyOfRange(contentStart.coerceAtMost(boxEnd), boxEnd)
        }
        return descendIlst(moov, contentStart, boxEnd, path.drop(1))
    }

    private fun descendIlst(moov: ByteArray, start: Int, end: Int, path: List<String>): ByteArray? {
        var offset = start
        while (offset + MP4_BOX_HEADER <= end) {
            val size = readIntBe(moov, offset)
            val type = String(moov, offset + INT_BYTES, MAGIC_TEXT_LENGTH, Charsets.ISO_8859_1)
            val boxEnd = if (size == 0) end else offset + size
            if (size < MP4_BOX_HEADER || boxEnd > end) return null
            val found = matchIlstLevel(moov, offset, boxEnd, type, path)
            if (found != null) return found
            offset = boxEnd
        }
        return null
    }

    private fun lyricsFromIlst(ilst: ByteArray): String? {
        var offset = 0
        while (offset + MP4_BOX_HEADER <= ilst.size) {
            val size = readIntBe(ilst, offset)
            if (size < MP4_BOX_HEADER || offset + size > ilst.size) break
            val type = String(ilst, offset + INT_BYTES, MAGIC_TEXT_LENGTH, Charsets.ISO_8859_1)
            if (type == "\u00A9lyr") {
                lyricsFromLyrBox(ilst, offset + MP4_BOX_HEADER, offset + size)?.let { return it }
            }
            offset += size
        }
        return null
    }

    private fun lyricsFromLyrBox(ilst: ByteArray, start: Int, end: Int): String? {
        var inner = start
        while (inner + MP4_BOX_HEADER <= end) {
            val dataSize = readIntBe(ilst, inner)
            if (dataSize < MP4_BOX_HEADER || inner + dataSize > end) break
            if (String(ilst, inner + INT_BYTES, MAGIC_TEXT_LENGTH, Charsets.ISO_8859_1) == "data") {
                val payloadStart = inner + DATA_BOX_HEADER_SKIP
                val payloadEnd = inner + dataSize
                if (payloadStart < payloadEnd) {
                    String(ilst, payloadStart, payloadEnd - payloadStart, Charsets.UTF_8)
                        .ifBlank { null }?.let { return it }
                }
            }
            inner += dataSize
        }
        return null
    }

    // ------------------------------------------------------------------

    private fun readIntBe(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and BYTE_MASK) shl SHIFT_BYTE_HIGH) or
            ((buf[offset + 1].toInt() and BYTE_MASK) shl SHIFT_SHORT_HIGH) or
            ((buf[offset + SHORT_HIGH_BYTE].toInt() and BYTE_MASK) shl SHIFT_BYTE_MID) or
            (buf[offset + SHORT_LOW_BYTE].toInt() and BYTE_MASK)

    private fun readIntLe(buf: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(buf, offset, INT_BYTES).order(ByteOrder.LITTLE_ENDIAN).int

    private fun InputStream.readNBytesCompat(count: Int): ByteArray {
        val out = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = read(out, read, count - read)
            if (n < 0) break
            read += n
        }
        return if (read == count) out else out.copyOf(read)
    }
}
