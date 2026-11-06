package com.vayunmathur.library.ui.odf

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * OOXML fourth round: presentation backgrounds, transitions, text shapes,
 * slide masters and notes. Slice-1 coverage for library/ui (odf package).
 */
class OoxmlSlidesSlice1Test {

    private fun entries(zip: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                out[e.name] = zis.readBytes().toString(Charsets.UTF_8)
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        return out
    }

    @Test
    fun `slide backgrounds colors and images emit`() {
        val zip = OoxmlExporter.export(
            OdfDocument.Presentation(
                title = "P",
                slides = listOf(
                    OdfSlide(name = "S1", backgroundColor = 0xFF112233),
                    OdfSlide(
                        name = "S2",
                        backgroundImagePath = "bg.png",
                    ),
                ),
                images = mapOf("bg.png" to byteArrayOf(1, 2, 3)),
            )
        )
        val parts = entries(zip)
        val s1 = parts["ppt/slides/slide1.xml"] ?: error("no slide1 in ${parts.keys}")
        assertTrue(s1.contains("p:bg"), s1.take(2000))
        assertTrue(s1.contains("112233"), s1.take(2000))
        val s2 = parts["ppt/slides/slide2.xml"] ?: error("no slide2 in ${parts.keys}")
        assertTrue(s2.contains("blipFill"), s2.take(2000))
    }

    @Test
    fun `slide transitions map types and speeds`() {
        val zip = OoxmlExporter.export(
            OdfDocument.Presentation(
                title = "P",
                slides = listOf(
                    OdfSlide(name = "S1", transitionType = "wipe", transitionSpeed = "fast"),
                    OdfSlide(name = "S2", transitionType = "circle", transitionSpeed = "slow"),
                    OdfSlide(name = "S3", transitionType = "mystery"),
                ),
            )
        )
        val parts = entries(zip)
        val s1 = parts["ppt/slides/slide1.xml"] ?: error("no slide1")
        assertTrue(s1.contains("p:wipe"), s1.take(3000))
        assertTrue(s1.contains("spd=\"fast\""), s1.take(3000))
        val s3 = parts["ppt/slides/slide3.xml"] ?: error("no slide3")
        assertTrue(s3.contains("p:transition"), s3.take(3000))
    }

    @Test
    fun `text shapes carry paragraphs and runs`() {
        val zip = OoxmlExporter.export(
            OdfDocument.Presentation(
                title = "P",
                slides = listOf(
                    OdfSlide(
                        name = "S1",
                        masterName = "Master1",
                        elements = listOf(
                            OdfSlideElement.Shape(
                                OdfShape.CustomShape(
                                    x = 10f, y = 10f, width = 300f, height = 100f,
                                    fillColor = 0xFF00FF00,
                                    rotationDegrees = 15f,
                                    text = listOf(
                                        OdfParagraph(
                                            listOf(OdfSpan("shaped", bold = true)),
                                            style = ParagraphStyle.HEADING2,
                                            alignment = androidx.compose.ui.text.style.TextAlign.Center,
                                        )
                                    ),
                                )
                            ),
                            OdfSlideElement.Frame(
                                OdfFrame(
                                    x = 0f, y = 0f, width = 200f, height = 100f,
                                    paragraphs = listOf(
                                        OdfParagraph(listOf(OdfSpan("framed")))
                                    ),
                                )
                            ),
                        ),
                    )
                ),
            )
        )
        val parts = entries(zip)
        val s1 = parts["ppt/slides/slide1.xml"] ?: error("no slide1 in ${parts.keys}")
        assertTrue(s1.contains("shaped"), s1.take(5000))
        assertTrue(s1.contains("framed"), s1.take(5000))
    }

    @Test
    fun `a drawing document exports as pptx`() {
        val zip = OoxmlExporter.export(
            OdfDocument.Drawing(
                title = "D",
                pages = listOf(OdfSlide(name = "P1")),
            )
        )
        val parts = entries(zip)
        assertTrue(parts.keys.any { it.startsWith("ppt/") }, parts.keys.toString())
        assertTrue(OoxmlExporter.extensionFor(OdfDocument.Drawing(title = "D", pages = emptyList())) == "pptx")
    }
}
