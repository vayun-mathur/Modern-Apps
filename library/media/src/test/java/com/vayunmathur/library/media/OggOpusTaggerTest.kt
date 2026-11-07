package com.vayunmathur.library.media

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OggOpusTaggerTest {

    private val serial = 0x0BADCAFE

    private class Page(
        val headerType: Int,
        val granule: Long,
        val serial: Int,
        val sequence: Int,
        val lacing: List<Int>,
        val payload: ByteArray,
        val crcValid: Boolean,
        val raw: ByteArray,
    )

    private fun parse(stream: ByteArray): List<Page> {
        val pages = ArrayList<Page>()
        var offset = 0
        while (offset + 27 <= stream.size) {
            assertEquals("OggS", String(stream, offset, 4, Charsets.ISO_8859_1))
            val segmentCount = stream[offset + 26].toInt() and 0xff
            val lacing = (0 until segmentCount).map { stream[offset + 27 + it].toInt() and 0xff }
            val payloadStart = offset + 27 + segmentCount
            val end = payloadStart + lacing.sum()
            val whole = stream.copyOfRange(offset, end)
            val stated = readIntLe(whole, 22)
            for (i in 22..25) whole[i] = 0
            pages.add(
                Page(
                    headerType = stream[offset + 5].toInt() and 0xff,
                    granule = readLongLe(stream, offset + 6),
                    serial = readIntLe(stream, offset + 14),
                    sequence = readIntLe(stream, offset + 18),
                    lacing = lacing,
                    payload = stream.copyOfRange(payloadStart, end),
                    crcValid = OggPages.crc(whole) == stated,
                    raw = stream.copyOfRange(offset, end),
                ),
            )
            offset = end
        }
        assertEquals(stream.size, offset, "parser must consume the whole stream")
        return pages
    }

    private fun readIntLe(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xff) or
            ((buf[offset + 1].toInt() and 0xff) shl 8) or
            ((buf[offset + 2].toInt() and 0xff) shl 16) or
            ((buf[offset + 3].toInt() and 0xff) shl 24)

    private fun readLongLe(buf: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = value or ((buf[offset + i].toLong() and 0xff) shl (8 * i))
        return value
    }

    private fun packetsOf(page: Page): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var at = 0; var start = 0
        for (value in page.lacing) {
            at += value
            if (value < 255) { out.add(page.payload.copyOfRange(start, at)); start = at }
        }
        return out
    }

    /** Builds head + comment + one audio page with the given packets. */
    private fun oggFile(
        commentPacket: ByteArray,
        audioPacket: ByteArray = ByteArray(100) { 0xFC.toByte() },
        audioGranule: Long = 960L,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        var sequence = 0
        val head = OpusHead.build(2, OpusHead.DEFAULT_PRE_SKIP, 48_000)
        for (page in OggPages.forPacket(head, serial, sequence, 0L, OggPages.FLAG_BEGIN_OF_STREAM)) {
            out.write(page); sequence++
        }
        val commentStart = sequence
        for (page in OggPages.forPacket(commentPacket, serial, sequence, 0L, 0)) {
            out.write(page); sequence++
        }
        check(sequence > commentStart)
        out.write(
            OggPages.build(
                OggPages.FLAG_END_OF_STREAM, audioGranule, serial, sequence,
                OggPages.lacingFor(audioPacket.size), audioPacket, 0, audioPacket.size,
            ),
        )
        return out.toByteArray()
    }

    private fun streamWithAudio(packets: Int): ByteArray {
        val sink = ByteArrayOutputStream()
        val writer = OggStreamWriter(serial, sink)
        writer.writeHeaderPacket(OpusHead.build(2, OpusHead.DEFAULT_PRE_SKIP, 48_000))
        writer.writeHeaderPacket(OggOpusTagger.buildOpusTagsPacket(VorbisTags()))
        var samples = 0L
        repeat(packets) {
            samples += 960
            writer.writeAudioPacket(ByteArray(200) { 0xFC.toByte() }, samples)
        }
        writer.finish(samples)
        return sink.toByteArray()
    }

    @Test
    fun `rejects empty and non ogg input`() {
        assertNull(OggOpusTagger.tag(ByteArray(0), VorbisTags(title = "x")))
        assertNull(OggOpusTagger.tag(ByteArray(200) { 0x11 }, VorbisTags(title = "x")))
        assertNull(OggOpusTagger.tag("OggS".toByteArray(Charsets.ISO_8859_1), VorbisTags(title = "x")))
    }

    @Test
    fun `rejects a stream with only a head page`() {
        val sink = ByteArrayOutputStream()
        val writer = OggStreamWriter(serial, sink)
        writer.writeHeaderPacket(OpusHead.build(2, OpusHead.DEFAULT_PRE_SKIP, 48_000))
        writer.finish(0L)
        // finish adds an (empty) EOS page; tagging a 2-page stream whose second page
        // has no segments must also fail rather than crash.
        assertNull(OggOpusTagger.tag(sink.toByteArray(), VorbisTags(title = "x")))
    }

    @Test
    fun `rejects a truncated stream`() {
        val full = streamWithAudio(10)
        assertNull(OggOpusTagger.tag(full.copyOf(full.size - 10), VorbisTags(title = "x")))
        assertNull(OggOpusTagger.tag(full.copyOf(30), VorbisTags(title = "x")))
    }

    @Test
    fun `rejects a comment packet that never terminates`() {
        val bigComment = "OpusTags".toByteArray(Charsets.ISO_8859_1) + ByteArray(70_000) { 0x55 }
        val full = oggFile(bigComment)
        val pages = parse(full)
        // Keep only head + first comment page (all its lacing values are 255).
        val firstCommentEnd = pages[0].raw.size + pages[1].raw.size
        assertNull(OggOpusTagger.tag(full.copyOf(firstCommentEnd), VorbisTags(title = "x")))
    }

    @Test
    fun `replaces the comment packet and renumbers audio pages`() {
        val source = streamWithAudio(10)
        val before = parse(source)
        val tagged = assertNotNull(
            OggOpusTagger.tag(source, VorbisTags(title = "New Title", artist = "New Artist")),
        )
        val after = parse(tagged)
        // Head untouched byte for byte.
        assertTrue(before[0].raw.contentEquals(after[0].raw))
        after.forEachIndexed { index, page ->
            assertEquals(index, page.sequence, "page $index sequence")
            assertEquals(serial, page.serial)
            assertTrue(page.crcValid, "page $index crc")
        }
        val commentText = String(after[1].payload, Charsets.UTF_8)
        assertTrue(commentText.contains("TITLE=New Title"), commentText)
        assertTrue(commentText.contains("ARTIST=New Artist"))
        assertEquals(before.last().granule, after.last().granule)
        assertEquals(
            before.drop(2).flatMap { packetsOf(it) }.map { it.toList() },
            after.drop(2).flatMap { packetsOf(it) }.map { it.toList() },
            "audio packets must survive retagging byte for byte",
        )
    }

    @Test
    fun `shrinks a multi page comment and renumbers the audio after it`() {
        val bigComment = "OpusTags".toByteArray(Charsets.ISO_8859_1) +
            VorbisComments.buildCommentList(VorbisTags(lyrics = "x".repeat(70_000)))
        val source = oggFile(bigComment)
        val beforeCount = parse(source).size
        assertTrue(beforeCount > 3, "comment should span pages, got $beforeCount pages")
        val tagged = assertNotNull(OggOpusTagger.tag(source, VorbisTags(title = "Small")))
        val after = parse(tagged)
        assertEquals(3, after.size, "head + one comment page + one audio page")
        after.forEachIndexed { index, page ->
            assertEquals(index, page.sequence)
            assertTrue(page.crcValid, "page $index crc")
        }
        assertTrue(String(after[1].payload, Charsets.UTF_8).contains("TITLE=Small"))
        assertEquals(960L, after[2].granule)
    }

    @Test
    fun `grows the comment past one page when cover art is embedded`() {
        val source = streamWithAudio(5)
        val art = ByteArray(80_000) { (it % 253).toByte() }
        val tagged = assertNotNull(
            OggOpusTagger.tag(source, VorbisTags(title = "Art", coverArt = art, coverIsPng = true)),
        )
        val pages = parse(tagged)
        pages.forEachIndexed { index, page ->
            assertEquals(index, page.sequence)
            assertTrue(page.crcValid, "page $index crc")
        }
        assertTrue(pages.size > 3, "cover art comment must span pages")
    }

    @Test
    fun `buildOpusTagsPacket starts with the magic and the vendor`() {
        val packet = OggOpusTagger.buildOpusTagsPacket(VorbisTags(title = "Hi"))
        assertEquals("OpusTags", String(packet, 0, 8, Charsets.ISO_8859_1))
        assertTrue(String(packet, Charsets.UTF_8).contains("TITLE=Hi"))
        assertTrue(String(packet, Charsets.UTF_8).contains(VorbisComments.VENDOR))
    }

    @Test
    fun `vorbis tags default to absent fields`() {
        val tags = VorbisTags()
        assertEquals(null, tags.title)
        assertEquals(null, tags.trackNumber)
        assertEquals(null, tags.coverArt)
        assertEquals(false, tags.coverIsPng)
        val full = VorbisTags(
            title = "t", artist = "a", album = "al", albumArtist = "aa", date = "d",
            trackNumber = 1, trackTotal = 2, discNumber = 1, lyrics = "l",
            recordingId = "r", releaseId = "rel", coverArt = byteArrayOf(1), coverIsPng = true,
        )
        assertEquals("t", full.title)
        assertEquals(1, full.trackNumber)
        assertTrue(full.coverIsPng)
    }
}
