package com.vayunmathur.office.data.odf

import androidx.compose.ui.unit.LayoutDirection
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfFrame
import com.vayunmathur.library.ui.odf.OdfImage
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSerializer
import com.vayunmathur.library.ui.odf.OdfShape
import com.vayunmathur.library.ui.odf.OdfSlide
import com.vayunmathur.library.ui.odf.OdfSlideElement
import com.vayunmathur.library.ui.odf.OdfSpan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Verifies newly wired native-ODF serialization: rounded-rect corner radius, paragraph
 * writing-mode (RTL), and image opacity/color-mode inside a draw:frame. Asserts the emitted
 * content XML carries the right ODF attributes.
 */
class OdfSerializerFeatureTest {

    @Test fun rectCornerRadiusSerialized() {
        val doc = OdfDocument.Presentation("p", listOf(OdfSlide("s", elements = listOf(
            OdfSlideElement.Shape(OdfShape.Rect(10f, 10f, 100f, 60f, fillColor = 0xFF4472C4, cornerRadius = 12f))
        ))))
        val xml = OdfSerializer.serialize(doc)
        assertTrue(xml.contains("draw:corner-radius"), "expected draw:corner-radius in ${xml.take(60)}")
    }

    @Test fun paragraphWritingModeSerialized() {
        val doc = OdfDocument.TextDocument("d", listOf(
            OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("مرحبا")), direction = LayoutDirection.Rtl))
        ))
        val xml = OdfSerializer.serialize(doc)
        assertTrue(xml.contains("style:writing-mode=\"rl-tb\""))
    }

    @Test fun frameImageOpacityAndColorModeSerialized() {
        val doc = OdfDocument.Presentation("p", listOf(OdfSlide("s", elements = listOf(
            OdfSlideElement.Frame(OdfFrame(0f, 0f, 100f, 100f, emptyList(),
                image = OdfImage(
                    "Pictures/x.png",
                    byteArrayOf(1, 2, 3),
                    100f,
                    100f,
                    opacityPercent = 50f,
                    colorMode = "greyscale")))
        ))))
        val xml = OdfSerializer.serialize(doc)
        assertTrue(xml.contains("draw:image-opacity=\"50.0%\"") || xml.contains("draw:image-opacity=\"50%\""))
        assertTrue(xml.contains("draw:color-mode=\"greyscale\""))
    }
}
