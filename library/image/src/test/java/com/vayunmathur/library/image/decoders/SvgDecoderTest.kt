package com.vayunmathur.library.image.decoders

import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.library.image.Size
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SvgDecoderTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun svg(body: String, attrs: String = """width="100" height="80" viewBox="0 0 100 80"""") =
        """<svg xmlns="http://www.w3.org/2000/svg" $attrs>$body</svg>""".toByteArray()

    @Test fun `canDecode detects svg by hint and by content`() {
        assertTrue(SvgDecoder.canDecode(ByteArray(0), "icon.svg"))
        assertTrue(SvgDecoder.canDecode(ByteArray(0), "icon.svg?w=10"))
        assertTrue(SvgDecoder.canDecode(ByteArray(0), "image/svg+xml"))
        assertTrue(SvgDecoder.canDecode(svg("<rect/>"), null))
        assertTrue(SvgDecoder.canDecode("  <svg></svg>".toByteArray(), null))
        assertFalse(SvgDecoder.canDecode(byteArrayOf(1, 2, 3), null))
        assertFalse(SvgDecoder.canDecode(byteArrayOf(1, 2, 3), "photo.png"))
    }

    @Test fun `decode renders shapes at requested and default sizes`(): Unit = runBlocking {
        val sized = SvgDecoder.decode(
            svg("""<rect x="0" y="0" width="100" height="80" fill="#ff0000"/><circle cx="50" cy="40" r="10" fill="blue"/>"""),
            ImageRequest.Builder(ctx).size(Size(50, 40)).build(),
        )
        assertNotNull(sized)
        assertEquals(50, sized.width); assertEquals(40, sized.height)
        val defaulted = SvgDecoder.decode(svg("""<path d="M0 0 L100 80" stroke="black"/>"""), ImageRequest.Builder(ctx).build())
        assertNotNull(defaulted)
        assertEquals(512, defaulted.width)
    }

    @Test fun `decode handles additional shapes and invalid input`(): Unit = runBlocking {
        val complex = svg(
            """<rect x="1" y="1" width="10" height="10" fill="#00ff00"/><circle cx="20" cy="20" r="5" fill="red"/><path d="M0 0 L10 10" stroke="black"/>"""
        )
        assertNotNull(SvgDecoder.decode(complex, ImageRequest.Builder(ctx).size(Size(20, 20)).build()))
        // Malformed text decodes to null; empty and non-svg input yield blank bitmaps, never a crash
        assertNull(SvgDecoder.decode("not xml at all".toByteArray(), ImageRequest.Builder(ctx).build()))
        assertNotNull(SvgDecoder.decode(ByteArray(0), ImageRequest.Builder(ctx).build())) // empty input still yields a blank bitmap
        assertNotNull(SvgDecoder.decode("<html><body/></html>".toByteArray(), ImageRequest.Builder(ctx).size(Size(8, 8)).build())) // non-svg XML renders blank
    }

    @Test fun `color parsing covers hex functional keywords and alpha`() {
        assertEquals(Color.RED, parseColorString("#ff0000"))
        assertEquals(Color.RED, parseColorString("red"))
        assertEquals(Color.RED, parseColorString("rgb(255,0,0)"))
        assertEquals(Color.RED, parseColorString("rgb(100%,0%,0%)"))
        assertEquals(Color.TRANSPARENT, parseColorString("transparent"))
        assertNull(parseColorString("none"))
        assertNull(parseColorString(""))
        assertNull(parseColorString(null))
        assertNull(parseColorString("not-a-color"))
        assertEquals(255, parseRgbComp("100%"))
        assertEquals(128, parseRgbComp("128"))
        assertEquals(0, parseRgbComp("junk"))
        assertEquals(0.5f, parseAlphaComp("50%"))
        assertEquals(0.5f, parseAlphaComp("0.5"))
        assertEquals(1f, parseAlphaComp("junk"))
        assertEquals(1f, normalizeAlpha(255f))
        assertEquals(0f, normalizeAlpha(-1f))
    }

    @Test fun `path data parses every command family`() {
        val path = parsePathData("M10 10 H90 V90 L10 90 Z")
        assertFalse(path.isEmpty)
        val curve = parsePathData("M0 0 C10 10 20 10 30 0 S40 -10 50 0 Q60 10 70 0 T80 0 A10 10 0 0 1 90 10 m5 5 l5 5 h5 v5 z")
        assertFalse(curve.isEmpty)
        assertTrue(parsePathData("").isEmpty)
        assertTrue(parsePathData("garbage").isEmpty)
    }

    @Test fun `length parsing handles units andjunk`() {
        assertEquals(10f, parseLength("10"))
        assertEquals(10f, parseLength("10px"))
        assertNull(parseLength(null))
        assertNull(parseLength("abc"))
        assertEquals(100f, parseDocLength("100"))
        assertNull(parseDocLength(null))
    }
}
