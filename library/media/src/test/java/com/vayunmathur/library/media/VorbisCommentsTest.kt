package com.vayunmathur.library.media

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VorbisCommentsTest {

    private fun readIntLe(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xff) or
            ((buf[offset + 1].toInt() and 0xff) shl 8) or
            ((buf[offset + 2].toInt() and 0xff) shl 16) or
            ((buf[offset + 3].toInt() and 0xff) shl 24)

    private fun readIntBe(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xff) shl 24) or
            ((buf[offset + 1].toInt() and 0xff) shl 16) or
            ((buf[offset + 2].toInt() and 0xff) shl 8) or
            (buf[offset + 3].toInt() and 0xff)

    /** Parses buildCommentList output into (vendor, comments). */
    private fun parse(bytes: ByteArray): Pair<String, List<String>> {
        var offset = 0
        val vendorLen = readIntLe(bytes, offset); offset += 4
        val vendor = String(bytes, offset, vendorLen, Charsets.UTF_8); offset += vendorLen
        val count = readIntLe(bytes, offset); offset += 4
        val comments = ArrayList<String>()
        repeat(count) {
            val len = readIntLe(bytes, offset); offset += 4
            comments.add(String(bytes, offset, len, Charsets.UTF_8)); offset += len
        }
        assertEquals(bytes.size, offset, "comment list must consume exactly its bytes")
        return vendor to comments
    }

    @Test
    fun `empty tags produce only the vendor and a zero count`() {
        val (vendor, comments) = parse(VorbisComments.buildCommentList(VorbisTags()))
        assertEquals(VorbisComments.VENDOR, vendor)
        assertEquals("ModernApps", vendor)
        assertTrue(comments.isEmpty())
    }

    @Test
    fun `writes every text tag in identifying-first order`() {
        val tags = VorbisTags(
            title = "Song", artist = "Artist", album = "Album", albumArtist = "Album Artist",
            date = "2024-01-02", trackNumber = 3, trackTotal = 12, discNumber = 2,
            lyrics = "la la", recordingId = "rec-1", releaseId = "rel-1",
        )
        val (_, comments) = parse(VorbisComments.buildCommentList(tags))
        assertEquals(
            listOf(
                "TITLE=Song", "ARTIST=Artist", "ALBUM=Album", "ALBUMARTIST=Album Artist",
                "DATE=2024-01-02", "TRACKNUMBER=3", "TRACKTOTAL=12", "DISCNUMBER=2",
                "MUSICBRAINZ_TRACKID=rec-1", "MUSICBRAINZ_ALBUMID=rel-1", "LYRICS=la la",
            ),
            comments,
        )
    }

    @Test
    fun `omits null and blank fields but keeps zero numbers`() {
        val tags = VorbisTags(title = "   ", artist = "", album = null, trackNumber = 0, trackTotal = null)
        val (_, comments) = parse(VorbisComments.buildCommentList(tags))
        assertEquals(listOf("TRACKNUMBER=0"), comments)
    }

    @Test
    fun `keeps unicode values as utf8`() {
        val (_, comments) = parse(
            VorbisComments.buildCommentList(VorbisTags(title = "Björk — Homogenic")),
        )
        assertEquals(listOf("TITLE=Björk — Homogenic"), comments)
    }

    @Test
    fun `embeds cover art as a base64 flac picture block after the text tags`() {
        val image = byteArrayOf(1, 2, 3, 4, 5)
        val tags = VorbisTags(title = "T", coverArt = image, coverIsPng = true)
        val (_, comments) = parse(VorbisComments.buildCommentList(tags))
        assertEquals(2, comments.size)
        assertEquals("TITLE=T", comments[0])
        assertTrue(comments[1].startsWith("METADATA_BLOCK_PICTURE="))
        val block = Base64.getDecoder().decode(comments[1].removePrefix("METADATA_BLOCK_PICTURE="))
        var offset = 0
        assertEquals(3, readIntBe(block, offset), "front cover picture type"); offset += 4
        val mimeLen = readIntBe(block, offset); offset += 4
        assertEquals("image/png", String(block, offset, mimeLen, Charsets.ISO_8859_1)); offset += mimeLen
        assertEquals(0, readIntBe(block, offset), "description length"); offset += 4
        repeat(4) { assertEquals(0, readIntBe(block, offset)); offset += 4 }
        val imageLen = readIntBe(block, offset); offset += 4
        assertEquals(image.size, imageLen)
        assertTrue(image.contentEquals(block.copyOfRange(offset, offset + imageLen)))
        assertEquals(block.size, offset + imageLen)
    }

    @Test
    fun `labels jpeg cover art and ignores empty art`() {
        val (_, jpeg) = parse(
            VorbisComments.buildCommentList(VorbisTags(coverArt = byteArrayOf(9), coverIsPng = false)),
        )
        val block = Base64.getDecoder().decode(jpeg.single().removePrefix("METADATA_BLOCK_PICTURE="))
        val mimeLen = readIntBe(block, 4)
        assertEquals("image/jpeg", String(block, 8, mimeLen, Charsets.ISO_8859_1))

        val (_, empty) = parse(
            VorbisComments.buildCommentList(VorbisTags(coverArt = ByteArray(0))),
        )
        assertTrue(empty.isEmpty(), "empty cover art must not emit a picture comment")
    }

    @Test
    fun `intLe writes little endian bytes`() {
        assertTrue(byteArrayOf(0, 0, 0, 0).contentEquals(VorbisComments.intLe(0)))
        assertTrue(byteArrayOf(1, 0, 0, 0).contentEquals(VorbisComments.intLe(1)))
        assertTrue(
            byteArrayOf(0x4D, 0x3C, 0x2B, 0x1A).contentEquals(VorbisComments.intLe(0x1A2B3C4D)),
        )
        assertTrue(
            byteArrayOf(-1, -1, -1, -1).contentEquals(VorbisComments.intLe(-1)),
        )
        assertFalse(VorbisComments.intLe(256).contentEquals(VorbisComments.intLe(1)))
    }
}
