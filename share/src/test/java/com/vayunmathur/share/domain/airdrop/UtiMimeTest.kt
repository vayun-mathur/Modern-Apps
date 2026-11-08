package com.vayunmathur.share.domain.airdrop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Every UTI [Uti.forFileName] advertises in an Ask must resolve through [Uti.mimeTypeOf]:
 * otherwise the receiver files it as `application/octet-stream`.
 */
class UtiMimeTest {

    @Test
    fun `advertised archive and document UTIs resolve to MIME types`() {
        assertEquals("application/zip", Uti.mimeTypeOf(Uti.forFileName("a.zip").uti))
        assertEquals("application/epub+zip", Uti.mimeTypeOf(Uti.forFileName("b.epub").uti))
        assertEquals("application/x-tar", Uti.mimeTypeOf(Uti.forFileName("c.tar").uti))
        assertEquals("text/markdown", Uti.mimeTypeOf(Uti.forFileName("d.md").uti))
        assertEquals("text/csv", Uti.mimeTypeOf(Uti.forFileName("e.csv").uti))
        assertEquals("text/calendar", Uti.mimeTypeOf(Uti.forFileName("f.ics").uti))
        assertEquals("application/vnd.apple.pkpass", Uti.mimeTypeOf(Uti.forFileName("g.pkpass").uti))
        assertEquals("application/x-apple-diskimage", Uti.mimeTypeOf(Uti.forFileName("h.dmg").uti))
    }

    @Test
    fun `advertised media UTIs resolve to MIME types`() {
        assertEquals("video/x-matroska", Uti.mimeTypeOf(Uti.forFileName("a.mkv").uti))
        assertEquals("video/mpeg", Uti.mimeTypeOf(Uti.forFileName("b.mpeg").uti))
        assertEquals("video/mpeg", Uti.mimeTypeOf(Uti.forFileName("c.mpg").uti))
        assertEquals("model/vnd.pixar.usd", Uti.mimeTypeOf(Uti.forFileName("d.usd").uti))
        assertEquals("model/vnd.usdz+zip", Uti.mimeTypeOf(Uti.forFileName("e.usdz").uti))
    }

    @Test
    fun `unknown UTI still resolves to null for the generic fallback`() {
        assertNull(Uti.mimeTypeOf("com.example.never-seen"))
        assertNull(Uti.mimeTypeOf("public.data"))
    }
}
