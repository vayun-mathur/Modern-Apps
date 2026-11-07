package com.vayunmathur.music.platform

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Embedded-lyrics extraction for the three containers the downloader writes:
 * Ogg (Opus/Vorbis comment packets), FLAC (comment metadata block) and
 * MP4 (the iTunes ©lyr atom). Files are synthetic but byte-faithful: the
 * parser never checks CRCs, so pages/boxes are built with plain builders.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmbeddedLyricsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun read(bytes: ByteArray): String? {
        val file = File.createTempFile("lyrics", ".bin", context.cacheDir)
        return try {
            file.writeBytes(bytes)
            EmbeddedLyrics.read(context, Uri.fromFile(file))
        } finally {
            file.delete()
        }
    }

    // ------------------------------------------------------------------
    // Vorbis comment payload shared by Ogg and FLAC builders
    // ------------------------------------------------------------------

    private fun intLe(value: Int) = byteArrayOf(
        value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte(), (value ushr 24).toByte(),
    )

    private fun vorbisComments(vararg entries: String, vendor: String = "ModernApps"): ByteArray {
        val out = ByteArrayOutputStream()
        val vendorBytes = vendor.toByteArray(Charsets.UTF_8)
        out.write(intLe(vendorBytes.size)); out.write(vendorBytes)
        out.write(intLe(entries.size))
        for (entry in entries) {
            val bytes = entry.toByteArray(Charsets.UTF_8)
            out.write(intLe(bytes.size)); out.write(bytes)
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------
    // Ogg
    // ------------------------------------------------------------------

    private fun oggPage(packet: ByteArray, sequence: Int): ByteArray {
        val segments = ArrayList<Int>()
        var remaining = packet.size
        while (remaining >= 255) { segments.add(255); remaining -= 255 }
        segments.add(remaining)
        val out = ByteArrayOutputStream()
        out.write("OggS".toByteArray(Charsets.ISO_8859_1))
        out.write(0); out.write(if (sequence == 0) 2 else 0)
        out.write(ByteArray(8)); out.write(intLe(42)); out.write(intLe(sequence))
        out.write(ByteArray(4)); out.write(segments.size)
        segments.forEach { out.write(it) }
        out.write(packet)
        return out.toByteArray()
    }

    private fun oggOpus(commentsPacket: ByteArray): ByteArray =
        oggPage("OpusHead".toByteArray(Charsets.ISO_8859_1) + ByteArray(11), 0) +
            oggPage(commentsPacket, 1) +
            oggPage(byteArrayOf(0xFC.toByte(), 1, 2, 3), 2)

    @Test
    fun `reads lyrics from an opus tags packet`() {
        val packet = "OpusTags".toByteArray(Charsets.ISO_8859_1) +
            vorbisComments("TITLE=Song", "LYRICS=[00:01.00]hello world")
        assertEquals("[00:01.00]hello world", read(oggOpus(packet)))
    }

    @Test
    fun `reads lyrics from a vorbis comment packet case insensitively`() {
        val packet = byteArrayOf(3) + "vorbis".toByteArray(Charsets.ISO_8859_1) +
            vorbisComments("lyrics=plain unsynced words", "ARTIST=Someone")
        val bytes = oggPage("vorbis-head".toByteArray(Charsets.ISO_8859_1) + ByteArray(20), 0) +
            oggPage(packet, 1)
        assertEquals("plain unsynced words", read(bytes))
    }

    @Test
    fun `ogg without lyrics or with malformed comments returns null`() {
        val noLyrics = "OpusTags".toByteArray(Charsets.ISO_8859_1) +
            vorbisComments("TITLE=Only A Title")
        assertNull(read(oggOpus(noLyrics)))
        // Blank lyrics value counts as none.
        val blank = "OpusTags".toByteArray(Charsets.ISO_8859_1) +
            vorbisComments("LYRICS=   ")
        assertNull(read(oggOpus(blank)))
        // A comment packet that is neither OpusTags nor Vorbis.
        assertNull(read(oggOpus("OpusHead".toByteArray(Charsets.ISO_8859_1))))
        // Truncated vorbis list (count claims entries the bytes do not hold).
        val truncated = "OpusTags".toByteArray(Charsets.ISO_8859_1) +
            intLe(3) + "ven".toByteArray() + intLe(5)
        assertNull(read(oggOpus(truncated)))
    }

    @Test
    fun `ogg comment packet spanning pages is reassembled`() {
        val lyrics = "word ".repeat(200)
        val packet = "OpusTags".toByteArray(Charsets.ISO_8859_1) +
            vorbisComments("LYRICS=$lyrics")
        val head = oggPage("OpusHead".toByteArray(Charsets.ISO_8859_1) + ByteArray(11), 0)
        // First comment page carries 510 bytes as two full 255 segments, which
        // by the lacing rules means the packet continues on the next page.
        val firstPage = ByteArrayOutputStream().apply {
            write("OggS".toByteArray(Charsets.ISO_8859_1)); write(0); write(1)
            write(ByteArray(8)); write(intLe(42)); write(intLe(1)); write(ByteArray(4))
            write(2); write(255); write(255)
            write(packet, 0, 510)
        }.toByteArray()
        val rest = packet.copyOfRange(510, packet.size)
        assertEquals(lyrics, read(head + firstPage + oggPage(rest, 2)))
    }

    // ------------------------------------------------------------------
    // FLAC
    // ------------------------------------------------------------------

    private fun flacBlock(type: Int, payload: ByteArray, last: Boolean): ByteArray {
        val header = byteArrayOf(
            ((if (last) 0x80 else 0) or type).toByte(),
            (payload.size ushr 16).toByte(), (payload.size ushr 8).toByte(), payload.size.toByte(),
        )
        return header + payload
    }

    @Test
    fun `reads lyrics from a flac comment block`() {
        val bytes = "fLaC".toByteArray(Charsets.ISO_8859_1) +
            flacBlock(0, ByteArray(34), last = false) +
            flacBlock(4, vorbisComments("TITLE=T", "LYRICS=flac words"), last = true)
        assertEquals("flac words", read(bytes))
    }

    @Test
    fun `flac without a comment block returns null`() {
        val bytes = "fLaC".toByteArray(Charsets.ISO_8859_1) +
            flacBlock(0, ByteArray(34), last = true)
        assertNull(read(bytes))
        // Comment block after the last-block flag is never reached.
        val unreachable = "fLaC".toByteArray(Charsets.ISO_8859_1) +
            flacBlock(1, ByteArray(4), last = true) +
            flacBlock(4, vorbisComments("LYRICS=late"), last = true)
        assertNull(read(unreachable))
        // Truncated block payload.
        val truncated = "fLaC".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(0x84.toByte(), 0, 0, 100) + ByteArray(10)
        assertNull(read(truncated))
    }

    // ------------------------------------------------------------------
    // MP4
    // ------------------------------------------------------------------

    private fun box(type: String, payload: ByteArray): ByteArray {
        val size = 8 + payload.size
        val header = byteArrayOf(
            (size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte(),
        )
        return header + type.toByteArray(Charsets.ISO_8859_1) + payload
    }

    private fun mp4WithLyrics(lyrics: String?): ByteArray {
        val dataPayload = ByteArray(8) + (lyrics?.toByteArray(Charsets.UTF_8) ?: ByteArray(0))
        val lyr = box("©lyr", box("data", dataPayload))
        val ilst = box("ilst", lyr)
        val meta = box("meta", ByteArray(4) + ilst)
        val udta = box("udta", meta)
        val moov = box("moov", udta)
        val ftyp = box("ftyp", "M4A ".toByteArray(Charsets.ISO_8859_1) + ByteArray(8))
        return ftyp + moov
    }

    @Test
    fun `reads lyrics from an m4a lyr atom`() {
        assertEquals("sing along now", read(mp4WithLyrics("sing along now")))
    }

    @Test
    fun `mp4 without lyrics returns null`() {
        assertNull(read(mp4WithLyrics(null)))
        assertNull(read(mp4WithLyrics("   ")))
        // No moov at all: just an ftyp and a free box.
        val noMoov = box("ftyp", "M4A ".toByteArray(Charsets.ISO_8859_1) + ByteArray(8)) +
            box("free", ByteArray(16))
        assertNull(read(noMoov))
        // Moov without udta/meta/ilst.
        val bareMoov = box("ftyp", "M4A ".toByteArray(Charsets.ISO_8859_1) + ByteArray(8)) +
            box("moov", box("mvhd", ByteArray(12)))
        assertNull(read(bareMoov))
    }

    @Test
    fun `mp4 with a large size moov box is still scanned`() {
        val dataPayload = ByteArray(8) + "large box words".toByteArray(Charsets.UTF_8)
        val ilst = box("ilst", box("©lyr", box("data", dataPayload)))
        val moovPayload = box("udta", box("meta", ByteArray(4) + ilst))
        val moovSize = 16L + moovPayload.size
        val moovHeader = ByteArray(16)
        moovHeader[3] = 1 // size == 1 means 64-bit size follows
        System.arraycopy("moov".toByteArray(Charsets.ISO_8859_1), 0, moovHeader, 4, 4)
        for (i in 0 until 8) moovHeader[8 + i] = (moovSize ushr (56 - 8 * i)).toByte()
        val ftyp = box("ftyp", "M4A ".toByteArray(Charsets.ISO_8859_1) + ByteArray(8))
        assertEquals("large box words", read(ftyp + moovHeader + moovPayload))
    }

    // ------------------------------------------------------------------
    // Container sniffing
    // ------------------------------------------------------------------

    @Test
    fun `unknown and short files return null`() {
        assertNull(read(ByteArray(0)))
        assertNull(read(ByteArray(8) { 0x11 }))
        assertNull(read("RIFFxxxxWAVE".toByteArray(Charsets.ISO_8859_1)))
        assertNull(read("OggS".toByteArray(Charsets.ISO_8859_1)))
    }
}
