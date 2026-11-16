package com.vayunmathur.library.image.decoders

import android.graphics.Color
import android.graphics.Matrix
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

/**
 * Deep SVG coverage: transform matrices, style parsing, element families,
 * nested viewports and the full named-colour table. Matrix assertions use
 * Robolectric's real Matrix arithmetic; render assertions check bitmap
 * geometry (sizes), which is what callers depend on.
 */
@RunWith(RobolectricTestRunner::class)
class SvgDeepTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun svg(body: String, attrs: String = """width="100" height="80" viewBox="0 0 100 80"""") =
        """<svg xmlns="http://www.w3.org/2000/svg" $attrs>$body</svg>""".toByteArray()

    private fun decode(body: String, attrs: String = """width="100" height="80" viewBox="0 0 100 80"""") =
        runBlocking {
            SvgDecoder.decode(svg(body, attrs), ImageRequest.Builder(ctx).size(Size(60, 48)).build())
        }

    // ---- transforms ----

    @Test fun transformParsingBuildsRealMatrices() {
        val translate = parseTransform("translate(10 20)")
        assertNotNull(translate)
        val v = FloatArray(9)
        translate.getValues(v)
        assertEquals(10f, v[Matrix.MTRANS_X]); assertEquals(20f, v[Matrix.MTRANS_Y])

        val scale = parseTransform("scale(2)")
        assertNotNull(scale)
        scale.getValues(v)
        assertEquals(2f, v[Matrix.MSCALE_X]); assertEquals(2f, v[Matrix.MSCALE_Y])

        val scaleXY = parseTransform("scale(2 3)")
        assertNotNull(scaleXY)
        scaleXY.getValues(v)
        assertEquals(2f, v[Matrix.MSCALE_X]); assertEquals(3f, v[Matrix.MSCALE_Y])

        val matrix = parseTransform("matrix(1 0 0 1 5 6)")
        assertNotNull(matrix)
        matrix.getValues(v)
        assertEquals(5f, v[Matrix.MTRANS_X]); assertEquals(6f, v[Matrix.MTRANS_Y])

        val rotate = parseTransform("rotate(90)")
        assertNotNull(rotate)
        val skewX = parseTransform("skewX(45)")
        assertNotNull(skewX)
        skewX.getValues(v)
        assertEquals(1f, v[Matrix.MSKEW_X], 0.01f)
        val skewY = parseTransform("skewY(45)")
        assertNotNull(skewY)

        val combined = parseTransform("translate(5 5) scale(2)")
        assertNotNull(combined)
        combined.getValues(v)
        assertEquals(2f, v[Matrix.MSCALE_X])

        assertNull(parseTransform(null))
        assertNull(parseTransform(""))
        assertNull(parseTransform("bogus(1 2)"))
        assertNull(parseTransform("matrix(1 2)"))
        assertFalse(applyTransformOperation(Matrix(), "nope", listOf(1f)))
        assertTrue(applyTransformOperation(Matrix(), "rotate", listOf(30f, 10f, 10f)))
        assertFalse(applyTranslateTransform(Matrix(), emptyList()))
        assertFalse(applyScaleTransform(Matrix(), emptyList()))
        assertFalse(applySkewTransform(Matrix(), emptyList(), horizontal = true))
    }

    @Test fun styleAttributeParsing() {
        val styles = parseStyleAttribute("fill:#ff0000; stroke: blue ; opacity:0.5")
        assertEquals("#ff0000", styles["fill"])
        assertEquals("blue", styles["stroke"])
        assertEquals("0.5", styles["opacity"])
        assertEquals(emptyMap(), parseStyleAttribute(""))
        assertNull(parseStyleDeclaration(""))
        assertNull(parseStyleDeclaration("fill"))
        assertNull(parseStyleDeclaration(":red"))
        assertNull(parseStyleDeclaration("fill:"))
        assertEquals("fill" to "#fff", parseStyleDeclaration(" FILL : #fff "))
        assertEquals(listOf(1f, 2.5f, -3f), parseNumbersList("1, 2.5 -3"))
        assertEquals(emptyList(), parseNumbersList("  "))
    }

    // ---- element families through the real renderer ----

    @Test fun allShapeFamiliesRender() {
        val bmp = decode(
            """
            <ellipse cx="30" cy="24" rx="20" ry="10" fill="lightblue"/>
            <line x1="0" y1="0" x2="60" y2="48" stroke="darkgreen" stroke-width="2"/>
            <polyline points="0,48 30,10 60,48" fill="none" stroke="purple"/>
            <polygon points="10,10 50,10 30,40" fill="orange"/>
            <rect x="5" y="5" width="20" height="12" rx="4" ry="4" fill="teal"/>
            <circle cx="30" cy="24" r="6" fill="magenta"/>
            """.trimIndent(),
        )
        assertNotNull(bmp)
        assertEquals(60, bmp.width); assertEquals(48, bmp.height)
    }

    @Test fun transformsAndStylesRender() {
        val bmp = decode(
            """
            <g transform="translate(10 5) scale(1.5)" style="opacity:0.9">
              <rect x="0" y="0" width="20" height="10" fill="red" transform="rotate(15 10 5)"/>
            </g>
            <g transform="skewX(10)"><rect x="0" y="20" width="10" height="5" style="fill:#00ff00;stroke:none"/></g>
            <rect x="40" y="30" width="10" height="10" fill="gold" transform="matrix(1 0 0 1 2 3)"/>
            """.trimIndent(),
        )
        assertNotNull(bmp)
    }

    @Test fun nestedSvgViewportsRender() {
        val bmp = decode(
            """
            <svg x="5" y="5" width="40" height="30" viewBox="0 0 40 30">
              <rect x="0" y="0" width="40" height="30" fill="navy"/>
            </svg>
            <svg x="10" y="10" width="20" height="20">
              <circle cx="10" cy="10" r="5" fill="yellow"/>
            </svg>
            """.trimIndent(),
        )
        assertNotNull(bmp)
    }

    @Test fun hiddenAndCollapsedContentRendersBlankButValid() {
        val bmp = decode(
            """
            <rect x="0" y="0" width="10" height="10" fill="red" display="none"/>
            <g visibility="hidden"><rect x="0" y="0" width="10" height="10" fill="red"/></g>
            <rect x="20" y="20" width="5" height="5" fill="blue"/>
            """.trimIndent(),
        )
        assertNotNull(bmp)
        assertEquals(60, bmp.width)
    }

    @Test fun namedColorsCoverTheTable() {
        // A spread across the CSS named-colour table, including greys with
        // both spellings and the aqua/fuchsia aliases.
        for ((name, expected) in mapOf(
            "black" to Color.BLACK, "white" to Color.WHITE, "red" to Color.RED,
            "lime" to Color.GREEN, "green" to Color.GREEN, "blue" to Color.BLUE,
            "yellow" to Color.YELLOW, "aqua" to Color.CYAN, "cyan" to Color.CYAN,
            "fuchsia" to Color.MAGENTA, "magenta" to Color.MAGENTA,
            "gray" to Color.GRAY, "grey" to Color.GRAY,
        )) {
            assertEquals(expected, parseColorString(name), "named colour $name")
        }
        // Beyond the built-in table, Android's Color.parseColor name map is
        // the fallback (its darkgray is the darker Android grey, not CSS).
        assertEquals(Color.DKGRAY, parseColorString("darkgray"))
        assertEquals(Color.LTGRAY, parseColorString("lightgray"))
        assertEquals(Color.RED, parseColorString("RED"))
        assertEquals(0x7FFF0000, parseColorString("rgba(255,0,0,0.5)"))
        assertNull(parseColorString("#f00")) // 3-digit shorthand is not supported
        assertEquals(0xFFFF0000.toInt(), parseColorString("#ffff0000"))
    }

    @Test fun documentSizeFallsBackThroughAttributes() {
        // No explicit request size: the decoder's 512 default square, as the
        // existing suite pins. An explicit size is honoured exactly.
        val plain = runBlocking {
            SvgDecoder.decode(
                svg("""<rect width="10" height="10" fill="red"/>""", ""),
                ImageRequest.Builder(ctx).build(),
            )
        }
        assertNotNull(plain)
        assertEquals(512, plain.width)
        val sized = runBlocking {
            SvgDecoder.decode(
                svg("""<rect width="10" height="10" fill="red"/>""", """width="200" viewBox="0 0 100 50""""),
                ImageRequest.Builder(ctx).size(Size(200, 100)).build(),
            )
        }
        assertNotNull(sized)
        assertEquals(200, sized.width)
        assertEquals(100, sized.height)
        // fillMissingDimensions: missing height comes from the viewBox.
        val root = fillMissingDimensions(200f, 0f, ViewBox(0f, 0f, 100f, 50f))
        assertEquals(200f, root.docWidth)
        assertEquals(50f, root.docHeight)
        val both = fillMissingDimensions(0f, 0f, ViewBox(0f, 0f, 100f, 50f))
        assertEquals(100f, both.docWidth)
        assertEquals(50f, both.docHeight)
        val none = fillMissingDimensions(10f, 20f, null)
        assertEquals(10f, none.docWidth)
    }

}
