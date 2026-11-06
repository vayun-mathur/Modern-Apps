package com.vayunmathur.library.ui.odf

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ODF serializer round three: full metadata, number styles, validation
 * rules, named ranges, floating sheet objects, shapes. Slice-1 coverage.
 */
class OdfSerializerMetaSlice1Test {

    private fun para(text: String) =
        OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(text))))

    @Test
    fun `full metadata serializes every field`() {
        val meta = OdfMetadata(
            title = "Doc",
            author = "Ada",
            creator = "Bo",
            creationDate = "2024-01-01",
            modifiedDate = "2024-02-02",
            description = "desc",
            subject = "subj",
            keywords = listOf("k"),
            pageCount = 3,
            wordCount = 100,
            charCount = 500,
            paragraphCount = 10,
            editingCycles = 7,
            generator = "Test/1.0",
            userDefined = mapOf("reviewed" to "yes"),
            userDefinedTypes = mapOf("reviewed" to "boolean"),
        )
        val xml = OdfSerializer.serializeMeta(meta)
        assertTrue(xml.contains("initial-creator"), xml)
        assertTrue(xml.contains("Bo"), xml)
        assertTrue(xml.contains("document-statistic"), xml)
        assertTrue(xml.contains("page-count=\"3\""), xml)
        assertTrue(xml.contains("user-defined"), xml)
        assertTrue(xml.contains("value-type=\"boolean\""), xml)
        assertTrue(xml.contains("Test/1.0"), xml)
    }

    @Test
    fun `number styles cover date time scientific fraction currency percent`() {
        val doc = OdfDocument.Spreadsheet(
            title = "S",
            sheets = listOf(
                OdfSheet(
                    name = "Sheet1",
                    rows = listOf(
                        OdfRow(
                            listOf(
                                OdfCell("2024-01-01", numberFormat = OdfNumberFormat(isDate = true)),
                                OdfCell(
                                    "12:00",
                                    numberFormat = OdfNumberFormat(
                                        isTime = true,
                                        dateTimeTokens = listOf(
                                            OdfNumberToken("hours", style = "long"),
                                            OdfNumberToken("text", text = ":"),
                                            OdfNumberToken("minutes", style = "long"),
                                            OdfNumberToken("seconds"),
                                            OdfNumberToken("am-pm"),
                                        ),
                                    ),
                                ),
                                OdfCell("1e5", numberFormat = OdfNumberFormat(isScientific = true)),
                                OdfCell("1/2", numberFormat = OdfNumberFormat(isFraction = true)),
                                OdfCell(
                                    "$1.00",
                                    numberFormat = OdfNumberFormat(currencySymbol = "$", grouping = true),
                                ),
                                OdfCell("50%", numberFormat = OdfNumberFormat(percent = true)),
                                OdfCell("1.5", numberFormat = OdfNumberFormat(decimals = 2, grouping = true)),
                            )
                        )
                    ),
                )
            ),
        )
        val xml = OdfSerializer.serialize(doc)
        assertTrue(xml.contains("number:date-style"), xml.take(8000))
        assertTrue(xml.contains("number:time-style"), xml)
        assertTrue(xml.contains("scientific-number"), xml)
        assertTrue(xml.contains("number:fraction"), xml)
        assertTrue(xml.contains("currency-style"), xml)
        assertTrue(xml.contains("percentage-style"), xml)
        assertTrue(xml.contains("am-pm") || xml.contains("AM/PM"), xml)
    }

    @Test
    fun `validations and named ranges serialize`() {
        val doc = OdfDocument.Spreadsheet(
            title = "S",
            sheets = listOf(OdfSheet(name = "Sheet1", rows = listOf(OdfRow(listOf(OdfCell("v")))))),
            namedRanges = listOf(OdfNamedRange("R1", "Sheet1.A1:Sheet1.A1")),
            validations = listOf(
                OdfDataValidation(name = "V1", condition = "cell-content-is-whole-number()")
            ),
        )
        val xml = OdfSerializer.serialize(doc)
        assertTrue(xml.contains("R1"), xml.take(8000))
        assertTrue(xml.contains("content-validation") || xml.contains("content-validations"), xml.take(8000))
    }

    @Test
    fun `a floating chart on a sheet serializes`() {
        val doc = OdfDocument.Spreadsheet(
            title = "S",
            sheets = listOf(
                OdfSheet(
                    name = "Sheet1",
                    rows = listOf(OdfRow(listOf(OdfCell("v")))),
                    floating = listOf(
                        OdfSlideElement.Shape(
                            OdfShape.Ellipse(
                                x = 5f, y = 5f, width = 80f, height = 40f,
                                fillColor = 0xFF00FF,
                                strokeDashed = true,
                            )
                        ),
                        OdfSlideElement.Shape(
                            OdfShape.Polyline(
                                x = 0f, y = 0f, width = 60f, height = 60f,
                                points = listOf(0f to 0f, 60f to 0f, 30f to 60f),
                                closed = true,
                            )
                        ),
                    ),
                )
            ),
        )
        val xml = OdfSerializer.serialize(doc)
        assertTrue(xml.contains("draw:"), xml.take(12000))
    }

    @Test
    fun `per-edge paragraph borders serialize`() {
        val xml = OdfSerializer.serialize(
            OdfDocument.TextDocument(
                title = "T",
                content = listOf(
                    OdfContentBlock.Paragraph(
                        OdfParagraph(
                            listOf(OdfSpan("edged")),
                            borders = OdfBorders(top = "0.5pt solid #000000", bottom = "1pt solid #FF0000"),
                        )
                    )
                ),
            )
        )
        assertTrue(xml.contains("edged"), xml.take(4000))
        assertTrue(xml.contains("fo:border"), xml.take(8000))
    }

    @Test
    fun `uniform per-edge borders collapse to one attribute`() {
        val xml = OdfSerializer.serialize(
            OdfDocument.TextDocument(
                title = "T",
                content = listOf(
                    OdfContentBlock.Paragraph(
                        OdfParagraph(
                            listOf(OdfSpan("boxed")),
                            borders = OdfBorders(
                                top = "0.5pt solid #000000",
                                right = "0.5pt solid #000000",
                                bottom = "0.5pt solid #000000",
                                left = "0.5pt solid #000000",
                            ),
                        )
                    )
                ),
            )
        )
        assertTrue(xml.contains("fo:border=\"0.5pt solid #000000\""), xml.take(8000))
    }
}
