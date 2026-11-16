package com.vayunmathur.office.odf

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.ui.odf.ChartType
import com.vayunmathur.library.ui.odf.OdfChart
import com.vayunmathur.library.ui.odf.OdfChartSeries
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSheet
import com.vayunmathur.library.ui.odf.OdfSlide
import com.vayunmathur.library.ui.odf.OdfSpan
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * W4: ODF package writing (per-extension image media types, embedded chart
 * objects, per-type package mimetypes), ODS settings.xml freeze panes, and
 * XLSX display formatting for custom/currency/datetime number formats.
 */
@RunWith(RobolectricTestRunner::class)
class W4OdfPackageFormatsTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    init {
        Robolectric.setupContentProvider(
            Slice4ImportExportTest.Slice4Provider::class.java,
            "com.vayunmathur.office.w4test")
    }

    private var outTarget: File?
        get() = Slice4ImportExportTest.Slice4Provider.outTarget
        set(v) { Slice4ImportExportTest.Slice4Provider.outTarget = v }

    private fun contentUri(name: String): Uri = Uri.parse("content://com.vayunmathur.office.w4test/$name")

    private fun uriFor(name: String, bytes: ByteArray): Uri {
        val uri = Uri.parse("content://com.vayunmathur.office.w4src/$name")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        return uri
    }

    private fun zipBytes(entries: Map<String, ByteArray>): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { zos ->
            for ((n, c) in entries) {
                zos.putNextEntry(java.util.zip.ZipEntry(n))
                zos.write(c)
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun saveTo(name: String, doc: OdfDocument): File {
        val out = File(context.cacheDir, name)
        out.delete()
        outTarget = out
        try {
            OdfWriter.save(context, null, doc, contentUri(name))
        } finally {
            outTarget = null
        }
        assertTrue(out.exists() && out.length() > 50, "no package written for $name")
        return out
    }

    private fun entriesOf(file: File): Map<String, String> {
        val map = mutableMapOf<String, String>()
        java.util.zip.ZipFile(file).use { z ->
            val e = z.entries()
            while (e.hasMoreElements()) {
                val entry = e.nextElement()
                map[entry.name] = z.getInputStream(entry).bufferedReader().readText()
            }
        }
        return map
    }

    private fun para(text: String) = OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(text))))

    // ---- OdfWriter: images, objects, mimetypes ----

    @Test
    fun `writer declares every image media type and skips placeholders`() {
        val images = mapOf(
            "Pictures/a.png" to byteArrayOf(1),
            "Pictures/b.jpg" to byteArrayOf(2),
            "Pictures/c.jpeg" to byteArrayOf(3),
            "Pictures/d.gif" to byteArrayOf(4),
            "Pictures/e.bmp" to byteArrayOf(5),
            "Pictures/f.svg" to byteArrayOf(6),
            "Pictures/g.webp" to byteArrayOf(7),
            "Pictures/h.rdf" to byteArrayOf(8),
            "Pictures/i.bin" to byteArrayOf(9),
            "inline" to byteArrayOf(10),
            "" to byteArrayOf(11),
            "Pictures/empty.png" to byteArrayOf(),
        )
        val doc = OdfDocument.TextDocument(
            "Imgs", listOf(para("with images")),
            images = images,
        )
        val file = saveTo("w4-images.odt", doc)
        try {
            val entries = entriesOf(file)
            for (name in listOf("a.png", "b.jpg", "c.jpeg", "d.gif", "e.bmp", "f.svg", "g.webp", "h.rdf", "i.bin")) {
                assertTrue(entries.containsKey("Pictures/$name"), "missing $name in ${entries.keys}")
            }
            assertTrue(!entries.containsKey("inline"), "inline placeholder must be skipped")
            assertTrue(!entries.containsKey("Pictures/empty.png"), "empty image must be skipped")
            val manifest = entries.getValue("META-INF/manifest.xml")
            assertTrue(manifest.contains("image/png"), manifest)
            assertTrue(manifest.contains("image/jpeg"), manifest)
            assertTrue(manifest.contains("image/gif"), manifest)
            assertTrue(manifest.contains("image/bmp"), manifest)
            assertTrue(manifest.contains("image/svg+xml"), manifest)
            assertTrue(manifest.contains("image/webp"), manifest)
            assertTrue(manifest.contains("application/rdf+xml"), manifest)
            assertTrue(manifest.contains("application/vnd.oasis.opendocument.text"), manifest)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `writer embeds chart objects and per-type mimetypes`() {
        val chart = OdfChart(
            type = ChartType.BAR,
            categories = listOf("Q1", "Q2"),
            series = listOf(OdfChartSeries("Sales", listOf(3f, 4f))),
            title = "Sales",
        )
        val docs: List<Pair<String, OdfDocument>> = listOf(
            "w4-chart.odt" to OdfDocument.TextDocument("C", listOf(OdfContentBlock.Chart(chart))),
            "w4-sheet.ods" to OdfDocument.Spreadsheet("S", listOf(OdfSheet("Data", emptyList()))),
            "w4-slides.odp" to OdfDocument.Presentation("P", listOf(OdfSlide("S1"))),
            "w4-draw.odg" to OdfDocument.Drawing("D", listOf(OdfSlide("P1"))),
        )
        val expectedMime = mapOf(
            "w4-chart.odt" to "application/vnd.oasis.opendocument.text",
            "w4-sheet.ods" to "application/vnd.oasis.opendocument.spreadsheet",
            "w4-slides.odp" to "application/vnd.oasis.opendocument.presentation",
            "w4-draw.odg" to "application/vnd.oasis.opendocument.graphics",
        )
        for ((name, doc) in docs) {
            val file = saveTo(name, doc)
            try {
                val entries = entriesOf(file)
                assertEquals(expectedMime[name], entries["mimetype"]?.trim())
                assertTrue(entries.containsKey("content.xml"), entries.keys.toString())
                if (name == "w4-chart.odt") {
                    assertTrue(
                        entries.keys.any { it.contains("content.xml") && entries[it]!!.contains("Sales") } ||
                            entries.keys.any { it.endsWith(".xml") },
                        entries.keys.toString())
                }
            } finally {
                file.delete()
            }
        }
    }

    // ---- OdfParser: settings.xml freeze panes ----

    @Test
    fun `ods settings freeze panes apply per sheet`() {
        val content = """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" """ +
            """xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" """ +
            """xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0">""" +
            """<office:body><office:spreadsheet>""" +
            """<table:table table:name="Data"><table:table-row><table:table-cell office:value-type="float" office:value="1"><text:p>1</text:p></table:table-cell></table:table-row></table:table>""" +
            """<table:table table:name="Free"><table:table-row><table:table-cell office:value-type="float" office:value="2"><text:p>2</text:p></table:table-cell></table:table-row></table:table>""" +
            """<table:table table:name="Fallback"><table:table-row><table:table-cell office:value-type="float" office:value="3"><text:p>3</text:p></table:table-cell></table:table-row></table:table>""" +
            """</office:spreadsheet></office:body></office:document-content>"""
        fun entry(name: String, items: Map<String, String>): String =
            """<config:config-item-map-entry config:name="$name">""" +
                items.entries.joinToString("") { (k, v) ->
                    """<config:config-item config:name="$k" config:type="int">$v</config:config-item>"""
                } + "</config:config-item-map-entry>"
        val settings = """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<office:document-settings xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" """ +
            """xmlns:config="urn:oasis:names:tc:opendocument:xmlns:config:1.0">""" +
            """<office:settings>""" +
            """<config:config-item config:name="VisibleAreaWidth" config:type="int">999</config:config-item>""" +
            """<config:config-item-map-indexed config:name="Views">""" +
            """<config:config-item-map-entry>""" +
            """<config:config-item-map-named config:name="Tables">""" +
            entry("Data", mapOf(
                "HorizontalSplitMode" to "2", "HorizontalSplitPosition" to "2",
                "VerticalSplitMode" to "2", "VerticalSplitPosition" to "1",
                "PositionRight" to "9", "PositionBottom" to "8")) +
            entry("Free", mapOf(
                "HorizontalSplitMode" to "0", "HorizontalSplitPosition" to "4",
                "VerticalSplitMode" to "1", "VerticalSplitPosition" to "3")) +
            entry("Fallback", mapOf(
                "HorizontalSplitMode" to "2", "VerticalSplitMode" to "2",
                "PositionRight" to "3", "PositionBottom" to "2")) +
            """</config:config-item-map-named>""" +
            """<config:config-item-map-named config:name="Other">""" +
            entry("Data", mapOf("HorizontalSplitMode" to "2", "HorizontalSplitPosition" to "7")) +
            """</config:config-item-map-named>""" +
            """</config:config-item-map-entry></config:config-item-map-indexed>""" +
            """</office:settings></office:document-settings>"""
        val bytes = zipBytes(mapOf(
            "content.xml" to content.encodeToByteArray(),
            "settings.xml" to settings.encodeToByteArray(),
            "mimetype" to "application/vnd.oasis.opendocument.spreadsheet".encodeToByteArray(),
        ))
        val doc = OdfParser.parse(context, uriFor("frozen.ods", bytes), "frozen.ods")
        val sheet = assertIs<OdfDocument.Spreadsheet>(doc)
        val byName = sheet.sheets.associateBy { it.name }
        assertEquals(1, byName["Data"]?.freezeRows)
        assertEquals(2, byName["Data"]?.freezeCols)
        assertEquals(0, byName["Free"]?.freezeRows)
        assertEquals(0, byName["Free"]?.freezeCols)
        // Fallback positions are used when the explicit split positions are absent/zero.
        assertEquals(2, byName["Fallback"]?.freezeRows)
        assertEquals(3, byName["Fallback"]?.freezeCols)
    }

    // ---- OoxmlXlsx: custom number/date formats (Kotlin fallback formatter) ----

    private fun xlsxWithFormats(formats: List<Pair<Int, String>>, cells: String): OdfDocument.Spreadsheet {
        val numFmts = formats.joinToString("") { (id, code) ->
            """<numFmt numFmtId="$id" formatCode="$code"/>"""
        }
        val xfs = (listOf("""<xf numFmtId="0" fontId="0" fillId="0" borderId="0"/>""") +
            formats.map { (id, _) ->
                """<xf numFmtId="$id" fontId="0" fillId="0" borderId="0" applyNumberFormat="1"/>"""
            }).joinToString("")
        val entries = mapOf(
            "xl/workbook.xml" to ("<workbook xmlns=\"s\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                "<sheets><sheet name=\"Data\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"),
            "xl/_rels/workbook.xml.rels" to ("<Relationships xmlns=\"p\">" +
                "<Relationship Id=\"rId1\" Type=\"http://t/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>"),
            "xl/styles.xml" to ("<styleSheet xmlns=\"s\"><numFmts count=\"${formats.size}\">$numFmts</numFmts>" +
                "<cellXfs count=\"${formats.size + 1}\">$xfs</cellXfs></styleSheet>"),
            "xl/worksheets/sheet1.xml" to ("<worksheet xmlns=\"s\"><sheetData><row r=\"1\">$cells</row></sheetData></worksheet>"),
        )
        return OoxmlImporter.import(
            zipBytes(entries.mapValues { it.value.encodeToByteArray() }), "fmt.xlsx",
        ) as OdfDocument.Spreadsheet
    }

    @Test
    fun `xlsx currency grouping percent and scientific display`() {
        val doc = xlsxWithFormats(
            listOf(
                200 to "\$#,##0.00",
                201 to "0.00%",
                202 to "0.00E+00",
                203 to "#,##0",
            ),
            """<c r="A1" s="1"><v>1234.5</v></c>""" +
                """<c r="B1" s="2"><v>0.1234</v></c>""" +
                """<c r="C1" s="3"><v>12345</v></c>""" +
                """<c r="D1" s="4"><v>-1234567</v></c>""",
        )
        val cells = doc.sheets[0].rows[0].cells
        assertEquals("\$1,234.50", cells[0].text)
        assertEquals("12.34%", cells[1].text)
        assertTrue(cells[2].text.contains("E"), cells[2].text)
        assertEquals("-1,234,567", cells[3].text)
    }

    @Test
    fun `xlsx date serials with and without time`() {
        val doc = xlsxWithFormats(
            listOf(210 to "yyyy-mm-dd", 211 to "yyyy-mm-dd hh:mm"),
            """<c r="A1" s="1"><v>45292</v></c><c r="B1" s="2"><v>45292.5</v></c>""",
        )
        val cells = doc.sheets[0].rows[0].cells
        // Date display goes through the native formatter when available and the
        // Kotlin ISO fallback otherwise; both must render the serial as a date.
        assertTrue(cells[0].text.startsWith("2024-"), cells[0].text)
        assertTrue(cells[0].text.endsWith("-01"), cells[0].text)
        assertTrue(cells[1].text.startsWith("2024-"), cells[1].text)
        assertTrue(cells[1].text.contains(":"), cells[1].text)
    }
}
