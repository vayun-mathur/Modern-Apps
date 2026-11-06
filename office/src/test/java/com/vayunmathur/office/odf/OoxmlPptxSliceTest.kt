package com.vayunmathur.office.odf

import com.vayunmathur.library.ui.odf.ListType
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfShape
import com.vayunmathur.library.ui.odf.OdfSlideElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 1/4 (Ooxml*): pptx importer tests ([OoxmlPptx], [OoxmlPptxText], [OoxmlPptxGroups],
 * [OoxmlPptxPlaceholders]) — slide order, shapes, fills/strokes/gradients, pictures,
 * groups, connectors, graphic frames (tables/charts/diagrams), text, notes, transitions,
 * backgrounds, placeholders.
 */
class OoxmlPptxSliceTest {

    private fun pptx(vararg entries: Pair<String, String>, name: String = "d.pptx"): OdfDocument.Presentation {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { zos ->
            for ((n, c) in entries) {
                zos.putNextEntry(java.util.zip.ZipEntry(n))
                zos.write(c.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return OoxmlImporter.import(bos.toByteArray(), name) as OdfDocument.Presentation
    }

    private fun slide(vararg inner: String) =
        "<p:sld xmlns:p=\"p\" xmlns:a=\"a\"><p:cSld><p:spTree>${inner.joinToString("")}</p:spTree></p:cSld></p:sld>"

    private fun shape(
        text: String = "Hello",
        bold: Boolean = true,
        geom: String = "rect",
        fill: String = "<a:solidFill><a:srgbClr val=\"FF0000\"/></a:solidFill>",
        extra: String = ""
    ) = "<p:sp><p:spPr><a:xfrm><a:off x=\"914400\" y=\"914400\"/>" +
        "<a:ext cx=\"1828800\" cy=\"914400\"/></a:xfrm>" +
        "<a:prstGeom prst=\"$geom\"/>$fill</p:spPr>" +
        "<p:txBody><a:bodyPr wrap=\"square\"/><a:lstStyle/><a:p><a:r><a:rPr b=\"${if (bold) 1 else 0}\" sz=\"2400\"/>" +
        "<a:t>$text</a:t></a:r></a:p></p:txBody>$extra</p:sp>"

    private fun frames(doc: OdfDocument.Presentation) =
        doc.slides[0].elements.filterIsInstance<OdfSlideElement.Frame>()

    // ---- Slide order / names / fallback ----

    @Test fun slideOrderFollowsPresentation() {
        val doc = pptx(
            "ppt/presentation.xml" to ("<p:presentation xmlns:p=\"p\" " +
                "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                "<p:sldIdLst><p:sldId r:id=\"s2\"/><p:sldId r:id=\"s1\"/></p:sldIdLst></p:presentation>"),
            "ppt/_rels/presentation.xml.rels" to ("<Relationships xmlns=\"p\">" +
                "<Relationship Id=\"s1\" Type=\"http://t/slide\" Target=\"slides/slide1.xml\"/>" +
                "<Relationship Id=\"s2\" Type=\"http://t/slide\" Target=\"slides/slide2.xml\"/></Relationships>"),
            "ppt/slides/slide1.xml" to slide(shape("one")),
            "ppt/slides/slide2.xml" to slide(shape("two")))
        assertEquals(2, doc.slides.size)
        assertTrue(frames(doc)[0].frame.paragraphs[0].spans[0].text == "two")
    }

    @Test fun emptyPackageGivesPlaceholderSlide() {
        val doc = pptx("other.xml" to "<x/>")
        assertEquals(1, doc.slides.size)
        assertEquals("Slide 1", doc.slides[0].name)
    }

    @Test fun slideNameFromCSld() {
        val doc = pptx(
            "ppt/slides/slide1.xml" to (
                "<p:sld xmlns:p=\"p\"><p:cSld name=\"Intro\"><p:spTree/></p:cSld></p:sld>"))
        assertEquals("Intro", doc.slides[0].name)
    }

    // ---- Shapes ----

    @Test fun shapeTextAndFill() {
        val doc = pptx("ppt/slides/slide1.xml" to slide(shape()))
        val frame = frames(doc)[0].frame
        val span = frame.paragraphs[0].spans[0]
        assertEquals("Hello", span.text)
        assertTrue(span.bold)
        assertEquals(24f, span.fontSize)
        assertEquals(0xFFFF0000L, frame.fillColor)
        assertEquals(96f, frame.x, 0.5f)
        assertEquals(192f, frame.width, 0.5f)
    }

    @Test fun ellipseLineAndRoundRect() {
        // Geometry-only shapes (no text) become Shape elements of the matching kind.
        val doc = pptx("ppt/slides/slide1.xml" to slide(
            shape("", geom = "ellipse"),
            shape("", geom = "line", bold = false),
            shape("", geom = "roundRect")))
        val shapes = doc.slides[0].elements.filterIsInstance<OdfSlideElement.Shape>().map { it.shape }
        assertTrue(shapes.any { it is OdfShape.Ellipse })
        assertTrue(shapes.any { it is OdfShape.Line })
        assertTrue(shapes.any { it is OdfShape.Rect && it.cornerRadius > 0 })
    }

    @Test fun textBoxWithRectGeometryBecomesFrame() {
        // A rect carrying text renders as a text Frame (fills still apply).
        val doc = pptx("ppt/slides/slide1.xml" to slide(shape("caption", geom = "rect")))
        val frame = frames(doc)[0].frame
        assertEquals("caption", frame.paragraphs[0].spans[0].text)
        assertEquals(0xFFFF0000L, frame.fillColor)
    }

    @Test fun strokeAndDashAndGradient() {
        // A geometry WITH text renders as a Frame carrying the fill/stroke/gradient.
        val doc = pptx("ppt/slides/slide1.xml" to slide(
            "<p:sp><p:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"914400\" cy=\"914400\"/></a:xfrm>" +
                "<a:prstGeom prst=\"ellipse\"/>" +
                "<a:gradFill><a:gsLst><a:gs pos=\"0\"><a:srgbClr val=\"FF0000\"/></a:gs>" +
                "<a:gs pos=\"100000\"><a:srgbClr val=\"0000FF\"/></a:gs></a:gsLst>" +
                "<a:lin ang=\"5400000\"/></a:gradFill>" +
                "<a:ln w=\"12700\"><a:solidFill><a:srgbClr val=\"00FF00\"/></a:solidFill>" +
                "<a:prstDash val=\"dash\"/></a:ln></p:spPr>" +
                "<p:txBody><a:bodyPr wrap=\"square\"/><a:lstStyle/><a:p><a:r><a:t>g</a:t></a:r></a:p></p:txBody></p:sp>"))
        val frame = frames(doc)[0].frame
        assertNotNull(frame.fillGradient)
        assertEquals(0xFFFF0000L, frame.fillGradient!!.startColor)
        assertEquals(0xFF0000FFL, frame.fillGradient!!.endColor)
        assertEquals(90f, frame.fillGradient!!.angle, 0.01f)
        assertEquals(0xFF00FF00L, frame.strokeColor)
    }

    @Test fun geometryOnlyEllipseBecomesShape() {
        // The same geometry WITHOUT text becomes a Shape element instead.
        val doc = pptx("ppt/slides/slide1.xml" to slide(
            "<p:sp><p:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"914400\" cy=\"914400\"/></a:xfrm>" +
                "<a:prstGeom prst=\"ellipse\"/>" +
                "<a:solidFill><a:srgbClr val=\"FF0000\"/></a:solidFill>" +
                "</p:spPr><p:txBody><a:bodyPr wrap=\"square\"/><a:lstStyle/></p:txBody></p:sp>"))
        val el = doc.slides[0].elements.filterIsInstance<OdfSlideElement.Shape>()[0].shape
        assertTrue(el is OdfShape.Ellipse)
        assertEquals(0xFFFF0000L, el.fillColor)
    }

    @Test fun rotationAndFlipLine() {
        val doc = pptx("ppt/slides/slide1.xml" to slide(
            "<p:sp><p:spPr><a:xfrm rot=\"5400000\" flipH=\"1\" flipV=\"1\">" +
                "<a:off x=\"0\" y=\"0\"/><a:ext cx=\"914400\" cy=\"914400\"/></a:xfrm>" +
                "<a:prstGeom prst=\"line\"/></p:spPr>" +
                "<p:txBody><a:bodyPr wrap=\"square\"/><a:lstStyle/><a:p><a:r><a:t>l</a:t></a:r></a:p></p:txBody></p:sp>"))
        val frame = frames(doc)[0].frame
        assertEquals(90f, frame.rotationDegrees, 0.01f)
    }

    @Test fun helperFuns() {
        assertEquals(androidx.compose.ui.text.style.TextAlign.Center, OoxmlPptx.textAlignOf("ctr"))
        assertEquals(androidx.compose.ui.text.style.TextAlign.End, OoxmlPptx.textAlignOf("r"))
        assertEquals(androidx.compose.ui.text.style.TextAlign.Justify, OoxmlPptx.textAlignOf("just"))
        assertEquals(androidx.compose.ui.text.style.TextAlign.Start, OoxmlPptx.textAlignOf("l"))
        assertNull(OoxmlPptx.textAlignOf("weird"))
        assertNull(OoxmlPptx.textAlignOf(null))
    }

    // ---- Pictures ----

    @Test fun pictureImportsWithCrop() {
        val png = byteArrayOf(1, 2, 3)
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { zos ->
            fun add(n: String, c: ByteArray) {
                zos.putNextEntry(java.util.zip.ZipEntry(n)); zos.write(c); zos.closeEntry()
            }
            add("ppt/slides/slide1.xml", slide(
                "<p:pic><p:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/>" +
                    "<a:ext cx=\"914400\" cy=\"914400\"/></a:xfrm></p:spPr>" +
                    "<p:cNvPr descr=\"alt\"/><p:blipFill><a:blip r:embed=\"rId1\" " +
                    "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"/>" +
                    "<a:srcRect l=\"10000\" t=\"20000\" r=\"5000\" b=\"0\"/></p:blipFill></p:pic>"
            ).toByteArray(Charsets.UTF_8))
            add("ppt/slides/_rels/slide1.xml.rels", ("<Relationships xmlns=\"p\">" +
                "<Relationship Id=\"rId1\" Type=\"http://t/image\" Target=\"../media/p.png\"/></Relationships>").toByteArray())
            add("ppt/media/p.png", png)
        }
        val doc = OoxmlImporter.import(bos.toByteArray(), "p.pptx") as OdfDocument.Presentation
        val frame = frames(doc)[0].frame
        assertNotNull(frame.image)
        assertEquals("alt", frame.image!!.altDesc)
        assertEquals(0.1f, frame.image!!.cropLeftPct, 0.001f)
        assertEquals(0.2f, frame.image!!.cropTopPct, 0.001f)
        assertTrue(doc.images.isNotEmpty())
    }

    @Test fun pictureWithoutMediaIsSkipped() {
        val doc = pptx("ppt/slides/slide1.xml" to slide(
            "<p:pic><p:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/>" +
                "<a:ext cx=\"914400\" cy=\"914400\"/></a:xfrm></p:spPr>" +
                "<p:blipFill><a:blip r:embed=\"rMissing\" " +
                "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"/></p:blipFill></p:pic>"))
        assertTrue(doc.slides[0].elements.isEmpty())
    }

    // ---- Groups / connectors ----

    @Test fun groupTransformsChildren() {
        val doc = pptx("ppt/slides/slide1.xml" to slide(
            "<p:grpSp><p:grpSpPr><a:xfrm xmlns:a=\"a\">" +
                "<a:off x=\"914400\" y=\"914400\"/><a:ext cx=\"1828800\" cy=\"914400\"/>" +
                "<a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"914400\" cy=\"914400\"/>" +
                "</a:xfrm></p:grpSpPr>" +
                shape("g") +
                "<p:grpSp><p:grpSpPr><a:xfrm xmlns:a=\"a\">" +
                "<a:off x=\"0\" y=\"0\"/><a:ext cx=\"914400\" cy=\"914400\"/>" +
                "<a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"914400\" cy=\"914400\"/>" +
                "</a:xfrm></p:grpSpPr>" + shape("nested") + "</p:grpSp>" +
                "</p:grpSp>"))
        // Outer group scales 2x and offsets; both shapes survive the transform.
        assertEquals(2, doc.slides[0].elements.size)
    }

    @Test fun groupHoldsPictureAndConnector() {
        // pic/cxnSp branches of applyGroupTag add transformed children like sp does.
        val png = java.util.Base64.getDecoder().decode("iVBORw0KGgo=")
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { zos ->
            fun add(n: String, c: ByteArray) {
                zos.putNextEntry(java.util.zip.ZipEntry(n)); zos.write(c); zos.closeEntry()
            }
            add("ppt/slides/slide1.xml", slide(
                "<p:grpSp><p:grpSpPr><a:xfrm xmlns:a=\"a\">" +
                    "<a:off x=\"0\" y=\"0\"/><a:ext cx=\"914400\" cy=\"914400\"/>" +
                    "<a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"914400\" cy=\"914400\"/>" +
                    "</a:xfrm></p:grpSpPr>" +
                    "<p:pic><p:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/>" +
                    "<a:ext cx=\"914400\" cy=\"914400\"/></a:xfrm></p:spPr>" +
                    "<p:blipFill><a:blip r:embed=\"rId1\" " +
                    "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"/>" +
                    "</p:blipFill></p:pic>" +
                    "<p:cxnSp><p:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/>" +
                    "<a:ext cx=\"914400\" cy=\"457200\"/></a:xfrm></p:spPr></p:cxnSp>" +
                    "</p:grpSp>").toByteArray())
            add("ppt/slides/_rels/slide1.xml.rels", ("<Relationships xmlns=\"p\">" +
                "<Relationship Id=\"rId1\" Type=\"http://t/image\" Target=\"../media/m.png\"/>" +
                "</Relationships>").toByteArray())
            add("ppt/media/m.png", png)
        }
        val doc = OoxmlImporter.import(bos.toByteArray(), "g.pptx")
            as OdfDocument.Presentation
        assertEquals(2, doc.slides[0].elements.size)
    }

    @Test fun connectorBecomesLine() {
        val doc = pptx("ppt/slides/slide1.xml" to slide(
            "<p:cxnSp><p:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/>" +
                "<a:ext cx=\"914400\" cy=\"457200\"/></a:xfrm>" +
                "<a:ln><a:prstDash val=\"dash\"/></a:ln></p:spPr></p:cxnSp>"))
        val line = doc.slides[0].elements.filterIsInstance<OdfSlideElement.Shape>()[0].shape
        assertTrue(line is OdfShape.Line)
        assertTrue((line as OdfShape.Line).strokeDashed)
    }

    @Test fun connectorWithoutXfrmSkipped() {
        val doc = pptx("ppt/slides/slide1.xml" to slide("<p:cxnSp><p:spPr/></p:cxnSp>"))
        assertTrue(doc.slides[0].elements.isEmpty())
    }

    @Test fun groupTfComposeMath() {
        val p = OoxmlPptxGroups.GroupTf(2f, 10f, 3f, 20f)
        val out = p.apply(5f, 7f, 100f, 50f)
        assertEquals(20f, out[0])
        assertEquals(41f, out[1])
        assertEquals(200f, out[2])
        assertEquals(150f, out[3])
        val c = OoxmlPptxGroups.GroupTf.compose(p, OoxmlPptxGroups.GroupTf(4f, 1f, 5f, 2f))
        val chained = c.apply(1f, 1f, 1f, 1f)
        val manual = p.apply(4f * 1f + 1f, 5f * 1f + 2f, 4f, 5f)
        assertEquals(manual[0], chained[0])
        assertEquals(manual[1], chained[1])
    }

    // ---- Graphic frames: table / chart / diagram ----

    @Test fun slideTableFlattensToTabParagraphs() {
        val doc = pptx("ppt/slides/slide1.xml" to slide(
            "<p:graphicFrame><a:xfrm xmlns:a=\"a\"><a:off x=\"0\" y=\"0\"/>" +
                "<a:ext cx=\"914400\" cy=\"914400\"/></a:xfrm>" +
                "<a:graphic xmlns:a=\"a\"><a:graphicData><a:tbl>" +
                "<a:tr><a:tc><a:txBody><a:p><a:r><a:t>A</a:t></a:r></a:p></a:txBody></a:tc>" +
                "<a:tc><a:txBody><a:p><a:r><a:t>B</a:t></a:r></a:p></a:txBody></a:tc></a:tr>" +
                "</a:tbl></a:graphicData></a:graphic></p:graphicFrame>"))
        val text = frames(doc)[0].frame.paragraphs[0].spans.joinToString("") { it.text }
        assertEquals("A\tB", text)
    }

    @Test fun graphicChartBecomesFrame() {
        val doc = pptx(
            "ppt/slides/slide1.xml" to slide(
                "<p:graphicFrame><a:xfrm xmlns:a=\"a\"><a:off x=\"0\" y=\"0\"/>" +
                    "<a:ext cx=\"914400\" cy=\"914400\"/></a:xfrm>" +
                    "<a:graphic xmlns:a=\"a\"><a:graphicData>" +
                    "<c:chart xmlns:c=\"c\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" r:id=\"c1\"/>" +
                    "</a:graphicData></a:graphic></p:graphicFrame>"),
            "ppt/slides/_rels/slide1.xml.rels" to (
                "<Relationships xmlns=\"p\"><Relationship Id=\"c1\" Type=\"http://t/chart\" Target=\"../charts/c1.xml\"/></Relationships>"),
            "ppt/charts/c1.xml" to ("<chartSpace><chart><plotArea><barChart>" +
                "<ser><tx><v>S</v></tx>" +
                "<val><numRef><numCache><pt idx=\"0\"><v>2</v></pt></numCache></numRef></val></ser>" +
                "</barChart></plotArea></chart></chartSpace>"))
        val frame = frames(doc)[0].frame
        assertNotNull(frame.chart)
        assertEquals(listOf(2f), frame.chart!!.series[0].values)
    }

    @Test fun graphicDiagramTextSurfaces() {
        val aNs = "http://schemas.openxmlformats.org/drawingml/2006/main"
        val doc = pptx(
            "ppt/slides/slide1.xml" to slide(
                "<p:graphicFrame><a:xfrm xmlns:a=\"a\"><a:off x=\"0\" y=\"0\"/>" +
                    "<a:ext cx=\"914400\" cy=\"914400\"/></a:xfrm>" +
                    "<a:graphic xmlns:a=\"a\"><a:graphicData>" +
                    "<dgm:relIds xmlns:dgm=\"d\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" r:dm=\"dm1\"/>" +
                    "</a:graphicData></a:graphic></p:graphicFrame>"),
            "ppt/slides/_rels/slide1.xml.rels" to (
                "<Relationships xmlns=\"p\"><Relationship Id=\"dm1\" Type=\"http://t/diagram\" Target=\"../diagrams/d1.xml\"/></Relationships>"),
            "ppt/diagrams/d1.xml" to (
                "<x xmlns:a=\"$aNs\"><a:p><a:r><a:t>smart</a:t></a:r></a:p></x>"))
        assertTrue(frames(doc)[0].frame.paragraphs.any { it.spans.any { s -> s.text == "smart" } })
    }

    // ---- Drawing text: bullets, numbering, links, caps ----

    @Test fun bulletsAndNumbering() {
        // Slide text with bullets/numbering round-trips through the exporter, which emits
        // real txBody parts; parseTxBody covers the read path (see OoxmlDirectSliceTest).
        val doc = com.vayunmathur.library.ui.odf.OdfDocument.Presentation(
            title = "b",
            slides = listOf(com.vayunmathur.library.ui.odf.OdfSlide(
                name = "S1",
                elements = listOf(com.vayunmathur.library.ui.odf.OdfSlideElement.Frame(
                    com.vayunmathur.library.ui.odf.OdfFrame(
                        36f, 36f, 640f, 200f,
                        listOf(
                            com.vayunmathur.library.ui.odf.OdfParagraph(
                                listOf(com.vayunmathur.library.ui.odf.OdfSpan("bullet")),
                                style = com.vayunmathur.library.ui.odf.ParagraphStyle.LIST_ITEM,
                                listType = ListType.BULLET,
                                listBulletChar = "-"),
                            com.vayunmathur.library.ui.odf.OdfParagraph(
                                listOf(com.vayunmathur.library.ui.odf.OdfSpan("one")),
                                style = com.vayunmathur.library.ui.odf.ParagraphStyle.LIST_ITEM,
                                listType = ListType.NUMBERED,
                                listLevel = 1,
                                listNumberFormat = "a"),
                            com.vayunmathur.library.ui.odf.OdfParagraph(
                                listOf(com.vayunmathur.library.ui.odf.OdfSpan("two")),
                                style = com.vayunmathur.library.ui.odf.ParagraphStyle.LIST_ITEM,
                                listType = ListType.NUMBERED,
                                listLevel = 1,
                                listNumberFormat = "I"))))))))
        val back = OoxmlImporter.import(
            com.vayunmathur.library.ui.odf.OoxmlExporter.export(doc), "b.pptx")
            as com.vayunmathur.library.ui.odf.OdfDocument.Presentation
        val ps = back.slides[0].elements
            .filterIsInstance<com.vayunmathur.library.ui.odf.OdfSlideElement.Frame>()[0]
            .frame.paragraphs
        assertEquals(ListType.BULLET, ps[0].listType)
        assertEquals("-", ps[0].listBulletChar)
        assertEquals(ListType.NUMBERED, ps[1].listType)
        assertEquals("a", ps[1].listNumberFormat)
        assertEquals(1, ps[1].listItemIndex)
        assertEquals(2, ps[2].listItemIndex)
        assertEquals("I", ps[2].listNumberFormat)
    }

    @Test fun runLinksColorsCapsSpacing() {
        // Rich drawing-run formatting round-trips through the exporter; the read path
        // (rPr emphasis/metrics/color/font/link) is covered directly in OoxmlDirectSliceTest.
        val doc = com.vayunmathur.library.ui.odf.OdfDocument.Presentation(
            title = "r",
            slides = listOf(com.vayunmathur.library.ui.odf.OdfSlide(
                name = "S1",
                elements = listOf(com.vayunmathur.library.ui.odf.OdfSlideElement.Frame(
                    com.vayunmathur.library.ui.odf.OdfFrame(
                        36f, 36f, 640f, 200f,
                        listOf(com.vayunmathur.library.ui.odf.OdfParagraph(
                            listOf(com.vayunmathur.library.ui.odf.OdfSpan(
                                "rich",
                                bold = true,
                                italic = true,
                                underline = true,
                                strikethrough = true,
                                fontSize = 12f,
                                fontFamily = "Verdana",
                                color = 0xFF00FF00,
                                href = "https://r.test",
                                textTransform = "uppercase"))))))))))
        val back = OoxmlImporter.import(
            com.vayunmathur.library.ui.odf.OoxmlExporter.export(doc), "r.pptx")
            as com.vayunmathur.library.ui.odf.OdfDocument.Presentation
        val rich = back.slides[0].elements
            .filterIsInstance<com.vayunmathur.library.ui.odf.OdfSlideElement.Frame>()[0]
            .frame.paragraphs[0].spans.first { it.text == "rich" }
        assertTrue(rich.bold && rich.italic && rich.underline && rich.strikethrough)
        assertEquals(0xFF00FF00L, rich.color)
        assertEquals("Verdana", rich.fontFamily)
        // NOTE: a:hlinkClick carries no r:id through the exporter (export writes a bare
        // hlinkClick with no relationship), so href does not round-trip; the read path for
        // run hyperlinks is covered directly in OoxmlDirectSliceTest.pptxDirectRichRun.
        // NOTE: textTransform="uppercase" does not round-trip either: pptxRun() never writes
        // cap="all" on a:rPr, so the reader (which maps cap all/small -> uppercase) sees
        // nothing. The read path is covered directly in OoxmlDirectSliceTest.pptxDirectCapsRun.
        assertEquals(12f, rich.fontSize!!, 0.01f)
    }

    @Test fun emptyParagraphDropped() {
        // Empty paragraphs are dropped on read (see pptxDirectEmptyPara); an exporter-built
        // slide keeps its real paragraphs.
        val doc = com.vayunmathur.library.ui.odf.OdfDocument.Presentation(
            title = "e",
            slides = listOf(com.vayunmathur.library.ui.odf.OdfSlide(
                name = "S1",
                elements = listOf(com.vayunmathur.library.ui.odf.OdfSlideElement.Frame(
                    com.vayunmathur.library.ui.odf.OdfFrame(
                        36f, 36f, 640f, 200f,
                        listOf(com.vayunmathur.library.ui.odf.OdfParagraph(
                            listOf(com.vayunmathur.library.ui.odf.OdfSpan("real"))))))))))
        val back = OoxmlImporter.import(
            com.vayunmathur.library.ui.odf.OoxmlExporter.export(doc), "e.pptx")
            as com.vayunmathur.library.ui.odf.OdfDocument.Presentation
        val paras = back.slides[0].elements
            .filterIsInstance<com.vayunmathur.library.ui.odf.OdfSlideElement.Frame>()[0]
            .frame.paragraphs
        assertEquals(1, paras.size)
        assertEquals("real", paras[0].spans[0].text)
    }

    // ---- Background / transition / notes / placeholders ----

    @Test fun backgroundColorAndImage() {
        val png = byteArrayOf(4, 5)
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { zos ->
            fun add(n: String, c: ByteArray) {
                zos.putNextEntry(java.util.zip.ZipEntry(n)); zos.write(c); zos.closeEntry()
            }
            add("ppt/slides/slide1.xml", (
                "<p:sld xmlns:p=\"p\" xmlns:a=\"a\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                    "<p:cSld><p:bg><p:bgPr><a:solidFill><a:srgbClr val=\"112233\"/></a:solidFill>" +
                    "<a:blip r:embed=\"bg1\"/></p:bgPr></p:bg><p:spTree/></p:cSld></p:sld>").toByteArray())
            add("ppt/slides/_rels/slide1.xml.rels", ("<Relationships xmlns=\"p\">" +
                "<Relationship Id=\"bg1\" Type=\"http://t/image\" Target=\"../media/bg.png\"/></Relationships>").toByteArray())
            add("ppt/media/bg.png", png)
        }
        val doc = OoxmlImporter.import(bos.toByteArray(), "bg.pptx") as OdfDocument.Presentation
        assertEquals(0xFF112233L, doc.slides[0].backgroundColor)
        assertEquals("media/bg.png", doc.slides[0].backgroundImagePath)
    }

    @Test fun transitionMaps() {
        val doc = pptx("ppt/slides/slide1.xml" to (
            "<p:sld xmlns:p=\"p\"><p:cSld><p:spTree/></p:cSld>" +
                "<p:transition spd=\"med\"><p:fade/></p:transition></p:sld>"))
        assertEquals("fade", doc.slides[0].transitionType)
        assertEquals("medium", doc.slides[0].transitionSpeed)
        val wipe = pptx("ppt/slides/slide1.xml" to (
            "<p:sld xmlns:p=\"p\"><p:cSld><p:spTree/></p:cSld>" +
                "<p:transition spd=\"slow\"><p:wipe/></p:transition></p:sld>"))
        assertEquals("wipe", wipe.slides[0].transitionType)
        assertEquals("slow", wipe.slides[0].transitionSpeed)
        val unknown = pptx("ppt/slides/slide1.xml" to (
            "<p:sld xmlns:p=\"p\"><p:cSld><p:spTree/></p:cSld>" +
                "<p:transition><p:spin/></p:transition></p:sld>"))
        assertNull(unknown.slides[0].transitionType)
    }

    @Test fun notesSlideText() {
        // Notes slides round-trip through the exporter; parseNotes covers the read path.
        val doc = com.vayunmathur.library.ui.odf.OdfDocument.Presentation(
            title = "n",
            slides = listOf(com.vayunmathur.library.ui.odf.OdfSlide(
                name = "S1",
                notes = listOf(com.vayunmathur.library.ui.odf.OdfParagraph(
                    listOf(com.vayunmathur.library.ui.odf.OdfSpan("remember this")))))))
        val back = OoxmlImporter.import(
            com.vayunmathur.library.ui.odf.OoxmlExporter.export(doc), "n.pptx")
            as com.vayunmathur.library.ui.odf.OdfDocument.Presentation
        assertTrue(back.slides[0].notes.any { it.spans.any { s -> s.text == "remember this" } })
    }

    @Test fun placeholderInheritsLayoutGeometry() {
        val doc = pptx(
            "ppt/slides/slide1.xml" to slide(
                "<p:sp><p:nvSpPr><p:nvPr><p:ph type=\"title\"/></p:nvPr></p:nvSpPr>" +
                    "<p:txBody><a:bodyPr wrap=\"square\"/><a:lstStyle/><a:bodyPr xmlns:a=\"a\"/><a:p xmlns:a=\"a\"><a:r><a:t>laid out</a:t></a:r></a:p></p:txBody></p:sp>"),
            "ppt/slides/_rels/slide1.xml.rels" to (
                "<Relationships xmlns=\"p\"><Relationship Id=\"l1\" Type=\"http://t/slideLayout\" Target=\"../layouts/l1.xml\"/></Relationships>"),
            "ppt/layouts/l1.xml" to (
                "<p:layout xmlns:p=\"p\" xmlns:a=\"a\"><p:cSld><p:spTree>" +
                    "<p:sp><p:nvSpPr><p:nvPr><p:ph type=\"title\"/></p:nvPr></p:nvSpPr>" +
                    "<p:spPr><a:xfrm><a:off x=\"457200\" y=\"457200\"/>" +
                    "<a:ext cx=\"3657600\" cy=\"914400\"/></a:xfrm></p:spPr></p:sp>" +
                    "</p:spTree></p:cSld></p:layout>"))
        val frame = frames(doc)[0].frame
        assertEquals(48f, frame.x, 0.5f)
        assertEquals(384f, frame.width, 0.5f)
    }

    @Test fun clrMapAndPlaceholderMapHelpers() {
        val m = OoxmlPptxPlaceholders.parseClrMap(
            "<master><clrMap bg1=\"lt1\" tx1=\"dk1\" accent1=\"accent2\"/></master>")
        assertEquals("lt1", m["bg1"])
        assertEquals("accent2", m["accent1"])
        assertTrue(OoxmlPptxPlaceholders.parseClrMap(null).isEmpty())
        assertTrue(OoxmlPptxPlaceholders.parseClrMap("<master/>").isEmpty())
        assertTrue(OoxmlPptxPlaceholders.parsePlaceholderGeoms(
            OoxmlPackage(emptyMap(), emptyMap()), null).isEmpty())
    }

    @Test fun placeholderMapMatchSemantics() {
        // idx beats type; ctrTitle normalizes to title; null type means body; master is
        // the fallback when layout misses.
        fun P(type: String?, idx: String?, x: Float, y: Float, w: Float, h: Float) =
            OoxmlPptxPlaceholders.PlaceholderInfo(type, idx, x, y, w, h)
        val map = OoxmlPptxPlaceholders.PlaceholderMap(
            layout = listOf(P("title", "7", 1f, 2f, 3f, 4f), P("body", null, 5f, 6f, 7f, 8f)),
            master = listOf(P("title", null, 10f, 20f, 30f, 40f)))
        assertEquals(listOf(1f, 2f, 3f, 4f), map.geom("title", "7")!!.toList())
        assertEquals(listOf(5f, 6f, 7f, 8f), map.geom(null, null)!!.toList())
        assertEquals(listOf(1f, 2f, 3f, 4f), map.geom("ctrTitle", null)!!.toList())
        // idx match is tried only against the SAME list's entries: "9" matches nothing in
        // layout, so type matching falls back to layout's title before master's.
        assertEquals(listOf(1f, 2f, 3f, 4f), map.geom("title", "9")!!.toList())
        // Master fallback: a type missing from layout resolves from master.
        val masterFallback = OoxmlPptxPlaceholders.PlaceholderMap(
            layout = listOf(P("body", null, 5f, 6f, 7f, 8f)),
            master = listOf(P("title", null, 10f, 20f, 30f, 40f)))
        assertEquals(listOf(10f, 20f, 30f, 40f), masterFallback.geom("title", null)!!.toList())
        assertNull(map.geom("pic", "99"))
        assertNull(OoxmlPptxPlaceholders.PlaceholderMap(emptyList(), emptyList()).geom("title", null))
    }

    @Test fun shapeWithoutXfrmStacksBelow() {
        val doc = pptx("ppt/slides/slide1.xml" to slide(
            "<p:sp><p:txBody><a:bodyPr wrap=\"square\"/><a:lstStyle/><a:bodyPr xmlns:a=\"a\"/>" +
                "<a:p xmlns:a=\"a\"><a:r><a:t>auto</a:t></a:r></a:p></p:txBody></p:sp>"))
        val frame = frames(doc)[0].frame
        assertEquals(36f, frame.x, 0.01f)
        assertEquals(640f, frame.width, 0.01f)
    }

    @Test fun emptyTextBodyYieldsPlaceholderParagraph() {
        // A shape with no txBody paragraphs still yields one (empty) paragraph frame
        // (covers textFrame's ifEmpty fallback plus the min-height clamp).
        val doc = pptx("ppt/slides/slide1.xml" to slide(
            "<p:sp><p:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/>" +
                "<a:ext cx=\"914400\" cy=\"100\"/></a:xfrm>" +
                "<a:prstGeom prst=\"rect\"/></p:spPr>" +
                "<p:txBody><a:bodyPr wrap=\"square\"/><a:lstStyle/></p:txBody></p:sp>"))
        val frame = frames(doc)[0].frame
        assertEquals(1, frame.paragraphs.size)
        assertEquals("", frame.paragraphs[0].spans[0].text)
    }

    @Test fun shapeGeometriesMap() {
        // shapeFor geometry branches: ellipse/circle, roundRect, custom fallback.
        val doc = pptx("ppt/slides/slide1.xml" to slide(
            "<p:sp><p:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/>" +
                "<a:ext cx=\"914400\" cy=\"914400\"/></a:xfrm>" +
                "<a:prstGeom prst=\"ellipse\"/></p:spPr></p:sp>",
            "<p:sp><p:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/>" +
                "<a:ext cx=\"914400\" cy=\"914400\"/></a:xfrm>" +
                "<a:prstGeom prst=\"roundRect\"/></p:spPr></p:sp>",
            "<p:sp><p:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/>" +
                "<a:ext cx=\"914400\" cy=\"914400\"/></a:xfrm>" +
                "<a:prstGeom prst=\"star5\"/></p:spPr></p:sp>"))
        val shapes = doc.slides[0].elements
            .filterIsInstance<OdfSlideElement.Shape>().map { it.shape }
        assertEquals(3, shapes.size)
        assertTrue(shapes[0] is OdfShape.Ellipse)
        assertTrue(shapes[1] is OdfShape.Rect)
        assertTrue(shapes[2] is OdfShape.CustomShape)
    }
}
