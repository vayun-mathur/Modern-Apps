package com.vayunmathur.library.ui.odf

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * OOXML second round: docx list numbering, xlsx styles/floating/print areas,
 * date-token formats. Slice-1 coverage for library/ui (odf package).
 */
class OoxmlNumberingStylesSlice1Test {

    private fun para(text: String, style: ParagraphStyle = ParagraphStyle.BODY) =
        OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(text)), style = style))

    private fun textDoc(vararg blocks: OdfContentBlock, title: String = "T") =
        OdfDocument.TextDocument(title = title, content = blocks.toList())

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

    private fun item(text: String, type: ListType = ListType.BULLET, level: Int = 1, index: Int = 1) =
        OdfContentBlock.Paragraph(
            OdfParagraph(
                listOf(OdfSpan(text)),
                style = ParagraphStyle.LIST_ITEM,
                listLevel = level,
                listType = type,
                listItemIndex = index,
            )
        )

    @Test
    fun `bulleted and numbered lists emit numbering xml`() {
        val zip = OoxmlExporter.export(
            textDoc(item("a"), item("b", ListType.NUMBERED, index = 1), item("c", ListType.NUMBERED, index = 2))
        )
        val parts = entries(zip)
        val numbering = parts["word/numbering.xml"] ?: error("no numbering in ${parts.keys}")
        assertTrue(numbering.contains("numFmt"), numbering.take(1000))
        val doc = parts["word/document.xml"] ?: error("no document")
        assertTrue(doc.contains("numPr"), doc.take(3000))
    }

    @Test
    fun `nested lists emit multiple levels`() {
        val zip = OoxmlExporter.export(
            textDoc(item("top", level = 1), item("nested", level = 2), item("top2", level = 1))
        )
        val parts = entries(zip)
        val doc = parts["word/document.xml"] ?: error("no document")
        assertTrue(doc.contains("ilvl"), doc.take(3000))
    }

    @Test
    fun `xlsx cell styles colors and alignments emit`() {
        val zip = OoxmlExporter.export(
            OdfDocument.Spreadsheet(
                title = "S",
                sheets = listOf(
                    OdfSheet(
                        name = "Sheet1",
                        rows = listOf(
                            OdfRow(
                                listOf(
                                    OdfCell(
                                        "styled",
                                        bold = true,
                                        italic = true,
                                        backgroundColor = 0xFFFFFF00,
                                        textColor = 0xFFFF0000,
                                        alignment = androidx.compose.ui.text.style.TextAlign.Center,
                                        wrap = true,
                                        borders = OdfBorders(top = "0.5pt solid #000000"),
                                    )
                                )
                            )
                        ),
                    )
                ),
            )
        )
        val parts = entries(zip)
        val styles = parts["xl/styles.xml"] ?: error("no styles in ${parts.keys}")
        assertTrue(styles.contains("cellXfs") || styles.contains("cellStyleXfs"), styles.take(2000))
        assertTrue(styles.contains("FFFF00"), styles.take(4000))
    }

    @Test
    fun `xlsx floating images emit a drawing part`() {
        val zip = OoxmlExporter.export(
            OdfDocument.Spreadsheet(
                title = "S",
                sheets = listOf(
                    OdfSheet(
                        name = "Sheet1",
                        rows = listOf(OdfRow(listOf(OdfCell("v")))),
                        floating = listOf(
                            OdfSlideElement.Frame(
                                OdfFrame(
                                    x = 10f, y = 10f, width = 100f, height = 50f,
                                    paragraphs = emptyList(),
                                    image = OdfImage(path = "inline", imageData = byteArrayOf(1, 2, 3)),
                                )
                            )
                        ),
                    )
                ),
            )
        )
        val parts = entries(zip)
        assertTrue(parts.keys.any { it.startsWith("xl/drawings/") }, parts.keys.toString())
    }

    @Test
    fun `xlsx hidden rows cols tab color and print areas emit`() {
        val zip = OoxmlExporter.export(
            OdfDocument.Spreadsheet(
                title = "S",
                sheets = listOf(
                    OdfSheet(
                        name = "Sheet1",
                        rows = listOf(OdfRow(listOf(OdfCell("v")))),
                        columnWidths = listOf(120f),
                        hiddenRows = setOf(0),
                        hiddenCols = setOf(0),
                        printRanges = "Sheet1.A1:Sheet1.A1",
                        tabColor = 0xFF0000,
                    )
                ),
                namedRanges = listOf(OdfNamedRange("MyRange", "Sheet1.A1:Sheet1.A1")),
            )
        )
        val parts = entries(zip)
        val wb = parts["xl/workbook.xml"] ?: error("no workbook in ${parts.keys}")
        assertTrue(wb.contains("Sheet1"), wb.take(2000))
        val sheetPart = parts.keys.first { it.startsWith("xl/worksheets/") }
        val sheet = parts[sheetPart] ?: error("no $sheetPart")
        assertTrue(sheet.contains("hidden"), sheet.take(3000))
    }

    @Test
    fun `xlsx date-token formats map tokens`() {
        val zip = OoxmlExporter.export(
            OdfDocument.Spreadsheet(
                title = "S",
                sheets = listOf(
                    OdfSheet(
                        name = "Sheet1",
                        rows = listOf(
                            OdfRow(
                                listOf(
                                    OdfCell(
                                        "2024-01-01",
                                        numberFormat = OdfNumberFormat(
                                            dateTimeTokens = listOf(
                                                OdfNumberToken("year", style = "long"),
                                                OdfNumberToken("text", text = "-"),
                                                OdfNumberToken("month", style = "long"),
                                            ),
                                        ),
                                    )
                                )
                            )
                        ),
                    )
                ),
            )
        )
        val parts = entries(zip)
        val styles = parts["xl/styles.xml"] ?: error("no styles in ${parts.keys}")
        assertTrue(styles.contains("numFmt"), styles.take(3000))
    }

    @Test
    fun `xlsx merged cells and hyperlinks emit`() {
        val zip = OoxmlExporter.export(
            OdfDocument.Spreadsheet(
                title = "S",
                sheets = listOf(
                    OdfSheet(
                        name = "Sheet1",
                        rows = listOf(
                            OdfRow(
                                listOf(
                                    OdfCell("link", hyperlink = "https://x.test", spannedColumns = 2),
                                    OdfCell("covered", isCovered = true),
                                )
                            )
                        ),
                    )
                ),
            )
        )
        val parts = entries(zip)
        val sheetPart = parts.keys.first { it.startsWith("xl/worksheets/") }
        val sheet = parts[sheetPart] ?: error("no $sheetPart")
        assertTrue(sheet.contains("mergeCell"), sheet.take(4000))
        assertTrue(sheet.contains("hyperlink"), sheet.take(4000))
    }
}
