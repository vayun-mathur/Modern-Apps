package com.vayunmathur.library.ui.odf

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ODF automatic-style branches: span decorations, paragraph properties,
 * tab stops, drop caps, font declarations and row/section styles.
 * Slice-1 coverage for library/ui (odf package).
 */
class OdfSerializerStylesSlice1Test {

    private fun para(text: String, style: ParagraphStyle = ParagraphStyle.BODY) =
        OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(text)), style = style))

    private fun textDoc(vararg blocks: OdfContentBlock, title: String = "T") =
        OdfDocument.TextDocument(title = title, content = blocks.toList())

    private fun styledDoc(p: OdfParagraph) =
        OdfSerializer.serialize(textDoc(OdfContentBlock.Paragraph(p)))

    @Test
    fun `an underline with style and color serializes fully`() {
        val xml = styledDoc(
            OdfParagraph(
                listOf(
                    OdfSpan(
                        "u",
                        underline = true,
                        underlineStyle = "double",
                        underlineColor = 0xFFFF0000,
                    )
                )
            )
        )
        assertTrue(xml.contains("text-underline-style=\"double\""), xml.take(4000))
        assertTrue(xml.contains("text-underline-color=\"#FF0000\""), xml.take(4000))
    }

    @Test
    fun `an underline without color uses font-color`() {
        val xml = styledDoc(OdfParagraph(listOf(OdfSpan("u", underline = true))))
        assertTrue(xml.contains("text-underline-color=\"font-color\""), xml.take(4000))
    }

    @Test
    fun `strike super sub and letter spacing serialize`() {
        val xml = styledDoc(
            OdfParagraph(
                listOf(
                    OdfSpan("s", strikethrough = true),
                    OdfSpan("sup", superscript = true),
                    OdfSpan("sub", subscript = true),
                    OdfSpan("tracked", letterSpacing = 1.5f),
                )
            )
        )
        assertTrue(xml.contains("text-line-through-style"), xml.take(4000))
        assertTrue(xml.contains("super 58%"), xml.take(4000))
        assertTrue(xml.contains("sub 58%"), xml.take(4000))
        assertTrue(xml.contains("letter-spacing=\"1.5pt\""), xml.take(4000))
    }

    @Test
    fun `text transform language and country serialize`() {
        val xml = styledDoc(
            OdfParagraph(
                listOf(
                    OdfSpan("t", textTransform = "uppercase", language = "en", country = "US")
                )
            )
        )
        assertTrue(xml.contains("text-transform=\"uppercase\""), xml.take(4000))
        assertTrue(xml.contains("language=\"en\""), xml.take(4000))
        assertTrue(xml.contains("country=\"US\""), xml.take(4000))
    }

    @Test
    fun `a named font emits a font-face declaration`() {
        val xml = styledDoc(
            OdfParagraph(listOf(OdfSpan("f", fontFamily = "DejaVu Sans")))
        )
        assertTrue(xml.contains("font-face-decls"), xml.take(4000))
        assertTrue(xml.contains("DejaVu Sans"), xml.take(4000))
        assertTrue(xml.contains("style:font-name=\"DejaVu Sans\""), xml.take(4000))
    }

    @Test
    fun `paragraph margins indent padding and line height serialize`() {
        val xml = styledDoc(
            OdfParagraph(
                listOf(OdfSpan("x")),
                marginLeft = 96f,
                marginRight = 48f,
                marginTop = 24f,
                marginBottom = 12f,
                textIndent = 36f,
                padding = 6f,
                lineHeightPercent = 1.5f,
            )
        )
        assertTrue(xml.contains("margin-left"), xml.take(6000))
        assertTrue(xml.contains("margin-right"), xml.take(6000))
        assertTrue(xml.contains("margin-top"), xml.take(6000))
        assertTrue(xml.contains("margin-bottom"), xml.take(6000))
        assertTrue(xml.contains("text-indent"), xml.take(6000))
        assertTrue(xml.contains("fo:padding"), xml.take(6000))
        assertTrue(xml.contains("line-height=\"150%\""), xml.take(6000))
    }

    @Test
    fun `keep widows orphans background and break-before serialize`() {
        val xml = styledDoc(
            OdfParagraph(
                listOf(OdfSpan("x")),
                keepWithNext = true,
                keepTogether = true,
                widows = 2,
                orphans = 3,
                backgroundColor = 0xFFFFFF00,
                breakBeforePage = true,
            )
        )
        assertTrue(xml.contains("keep-with-next"), xml.take(6000))
        assertTrue(xml.contains("keep-together"), xml.take(6000))
        assertTrue(xml.contains("widows=\"2\""), xml.take(6000))
        assertTrue(xml.contains("orphans=\"3\""), xml.take(6000))
        assertTrue(xml.contains("background-color"), xml.take(6000))
        assertTrue(xml.contains("break-before=\"page\""), xml.take(6000))
    }

    @Test
    fun `uniform and per-edge paragraph borders serialize`() {
        val uniform = styledDoc(
            OdfParagraph(listOf(OdfSpan("x")), borderColor = 0xFF0000FF)
        )
        assertTrue(uniform.contains("fo:border=\"0.5pt solid #0000FF\""), uniform.take(6000))
        val edged = styledDoc(
            OdfParagraph(
                listOf(OdfSpan("x")),
                borders = OdfBorders(top = "0.5pt solid #112233"),
            )
        )
        assertTrue(edged.contains("fo:border-top"), edged.take(6000))
    }

    @Test
    fun `rtl and ltr directions serialize`() {
        val rtl = styledDoc(
            OdfParagraph(listOf(OdfSpan("x")), direction = LayoutDirection.Rtl)
        )
        assertTrue(rtl.contains("writing-mode=\"rl-tb\""), rtl.take(6000))
        val ltr = styledDoc(
            OdfParagraph(listOf(OdfSpan("x")), direction = LayoutDirection.Ltr)
        )
        assertTrue(ltr.contains("writing-mode=\"lr-tb\""), ltr.take(6000))
    }

    @Test
    fun `plain tab stops serialize`() {
        val xml = styledDoc(
            OdfParagraph(listOf(OdfSpan("x")), tabStops = listOf(96f, 192f))
        )
        assertTrue(xml.contains("tab-stops"), xml.take(6000))
        assertTrue(xml.contains("tab-stop"), xml.take(6000))
    }

    @Test
    fun `rich tab stops with type and leader serialize`() {
        val xml = styledDoc(
            OdfParagraph(
                listOf(OdfSpan("x")),
                tabStopDetails = listOf(
                    OdfTabStop(position = 288f, type = "right", leaderChar = "."),
                    OdfTabStop(position = 384f, type = "center"),
                ),
            )
        )
        assertTrue(xml.contains("style:type=\"right\""), xml.take(6000))
        assertTrue(xml.contains("style:leader-char=\".\""), xml.take(6000))
    }

    @Test
    fun `a drop cap serializes its lines and length`() {
        val xml = styledDoc(
            OdfParagraph(listOf(OdfSpan("Drop")), dropCapLines = 3, dropCapLength = 2)
        )
        assertTrue(xml.contains("style:drop-cap"), xml.take(6000))
        assertTrue(xml.contains("style:lines=\"3\""), xml.take(6000))
    }

    @Test
    fun `centered and justified alignments serialize`() {
        val centered = styledDoc(
            OdfParagraph(listOf(OdfSpan("x")), alignment = TextAlign.Center)
        )
        assertTrue(centered.contains("text-align=\"center\""), centered.take(6000))
        val justified = styledDoc(
            OdfParagraph(listOf(OdfSpan("x")), alignment = TextAlign.Justify)
        )
        assertTrue(justified.contains("text-align=\"justify\""), justified.take(6000))
    }
}
