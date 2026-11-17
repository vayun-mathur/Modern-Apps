package com.vayunmathur.office.ui

import com.vayunmathur.library.ui.odf.OdfBorders
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.ParagraphStyle
import androidx.compose.ui.text.style.TextAlign

/**
 * The first 14 of 99 content blocks of `metadata_data/assets/sample1.docx`, as
 * produced by this app's own [com.vayunmathur.office.data.odf.OoxmlImporter].
 *
 * GENERATED — do not edit by hand.
 *
 * Why it is baked rather than parsed in the preview: `OoxmlXml` builds its parser with
 * `XmlPullParserFactory.newInstance()`, and `org.xmlpull.v1` is supplied only by `android.jar`,
 * where every method is a stub that throws `RuntimeException("Stub!")`. Layoutlib ships no
 * `org/xmlpull/` classes of its own, so a preview physically cannot parse a .docx. Running the
 * real importer offline and emitting the result keeps the listing image faithful to the actual
 * file, and deterministic besides.
 *
 * Properties left at their default are omitted. So are ones with no bearing on a still image
 * (language/country tags, revision ids, cross-reference bookkeeping) and `fontFamily` — the
 * document embeds the Ubuntu family, which Layoutlib cannot load, so recording it would only
 * describe a substitution that happens anyway.
 *
 * To regenerate after changing the .docx: run [com.vayunmathur.office.data.odf.OoxmlImporter] over
 * it on the JVM (a real `org.xmlpull` implementation such as kxml2 must be on the classpath in
 * place of android.jar's stubs) and re-emit, dropping defaults as above.
 */
internal val SampleDocxBlocks: List<OdfContentBlock> = listOf(
    OdfContentBlock.Paragraph(
        paragraph = OdfParagraph(
            spans = listOf(
                OdfSpan(
                    text = "Demonstration of DOCX support in calibre",
                    fontSize = 26f,
                    color = 4279711325L,
                    letterSpacing = 0.25f,
                ),
            ),
            style = ParagraphStyle.HEADING1,
            marginBottom = 20f,
            lineHeightPercent = 1f,
            borders = OdfBorders(
                bottom = "1.00pt solid #4F81BD",
            ),
        ),
    ),
    OdfContentBlock.Paragraph(
        paragraph = OdfParagraph(
            spans = listOf(
                OdfSpan(
                    text =
                        "This document demonstrates the ability of the calibre DOCX Input plugin" +
                            "to convert the various typographic features in a Microsoft Word (2007 and" +
                            "newer) document. Convert this document to a modern ebook format, such as" +
                                "AZW3 for Kindles or EPUB for other ebook readers, to see it in action.",
                    fontSize = 12f,
                ),
            ),
            textIndent = 28.800001f,
            lineHeightPercent = 1.15f,
        ),
    ),
    OdfContentBlock.Paragraph(
        paragraph = OdfParagraph(
            spans = listOf(
                OdfSpan(
                    text = "There is support for images, tables, lists, footnotes, endnotes, ",
                    fontSize = 12f,
                ),
                OdfSpan(
                    text = "links, dropcaps and ",
                    fontSize = 12f,
                ),
                OdfSpan(
                    text = "various",
                    fontSize = 12f,
                ),
                OdfSpan(
                    text = " types of text and paragraph level formatting.",
                    fontSize = 12f,
                ),
            ),
            textIndent = 28.800001f,
            lineHeightPercent = 1.15f,
        ),
    ),
    OdfContentBlock.Paragraph(
        paragraph = OdfParagraph(
            spans = listOf(
                OdfSpan(
                    text = "To see the DOCX conversion in action, simply add this file to calibre using the ",
                    fontSize = 12f,
                ),
                OdfSpan(
                    text = "“Add Books” ",
                    bold = true,
                    fontSize = 12f,
                ),
                OdfSpan(
                    text = "button and then click “",
                    fontSize = 12f,
                ),
                OdfSpan(
                    text = "Convert”. ",
                    bold = true,
                    fontSize = 12f,
                ),
                OdfSpan(
                    text =
                        " Set the output format in the top right corner of" +
                            "the conversion dialog to EPUB or AZW3 and click ",
                    fontSize = 12f,
                ),
                OdfSpan(
                    text = "“OK”",
                    bold = true,
                    fontSize = 12f,
                ),
                OdfSpan(
                    text = ".",
                    fontSize = 12f,
                ),
            ),
            textIndent = 28.800001f,
            lineHeightPercent = 1.15f,
        ),
    ),
    OdfContentBlock.Paragraph(
        paragraph = OdfParagraph(
            spans = listOf(
                OdfSpan(
                    text = "",
                ),
            ),
            textIndent = 28.800001f,
            lineHeightPercent = 1.15f,
        ),
    ),
    OdfContentBlock.Paragraph(
        paragraph = OdfParagraph(
            spans = listOf(
                OdfSpan(
                    text = "Text Formatting",
                    bold = true,
                    fontSize = 14f,
                    color = 4281753489L,
                ),
            ),
            style = ParagraphStyle.HEADING1,
            alignment = TextAlign.Center,
            marginTop = 32f,
            lineHeightPercent = 1.15f,
        ),
    ),
    OdfContentBlock.Paragraph(
        paragraph = OdfParagraph(
            spans = listOf(
                OdfSpan(
                    text = "Inline formatting",
                    bold = true,
                    fontSize = 13f,
                    color = 4283400637L,
                ),
            ),
            style = ParagraphStyle.HEADING2,
            marginTop = 13.333333f,
            lineHeightPercent = 1.15f,
        ),
    ),
    OdfContentBlock.Paragraph(
        paragraph = OdfParagraph(
            spans = listOf(
                OdfSpan(
                    text =
                        "Here, we demonstrate various types of inline text formatting and the use of embedded fonts.",
                    fontSize = 12f,
                ),
            ),
            textIndent = 28.800001f,
            lineHeightPercent = 1.15f,
        ),
    ),
    OdfContentBlock.Paragraph(
        paragraph = OdfParagraph(
            spans = listOf(
                OdfSpan(
                    text = "Here is some ",
                    fontSize = 12f,
                ),
                OdfSpan(
                    text = "bold, ",
                    bold = true,
                    fontSize = 12f,
                ),
                OdfSpan(
                    text = "italic, ",
                    italic = true,
                    fontSize = 12f,
                ),
                OdfSpan(
                    text = "bold-italic, ",
                    bold = true,
                    italic = true,
                    fontSize = 12f,
                ),
                OdfSpan(
                    text = "underlined ",
                    fontSize = 12f,
                    underline = true,
                    underlineStyle = "solid",
                ),
                OdfSpan(
                    text = "and ",
                    fontSize = 12f,
                ),
                OdfSpan(
                    text = "struck out ",
                    fontSize = 12f,
                    strikethrough = true,
                ),
                OdfSpan(
                    text = " text. Then, we have a super",
                    fontSize = 12f,
                ),
                OdfSpan(
                    text = "script",
                    fontSize = 12f,
                    superscript = true,
                ),
                OdfSpan(
                    text = " and a sub",
                    fontSize = 12f,
                ),
                OdfSpan(
                    text = "script",
                    fontSize = 12f,
                    subscript = true,
                ),
                OdfSpan(
                    text = ". Now we see some ",
                    fontSize = 12f,
                ),
                OdfSpan(
                    text = "red",
                    fontSize = 12f,
                    color = 4294901760L,
                ),
                OdfSpan(
                    text = ", ",
                    fontSize = 12f,
                ),
                OdfSpan(
                    text = "green",
                    fontSize = 12f,
                    color = 4287811664L,
                ),
                OdfSpan(
                    text = " and ",
                    fontSize = 12f,
                ),
                OdfSpan(
                    text = "blue",
                    fontSize = 12f,
                    color = 4278218944L,
                ),
                OdfSpan(
                    text = " text. Some text with a ",
                    fontSize = 12f,
                ),
                OdfSpan(
                    text = "yellow highlight",
                    fontSize = 12f,
                    backgroundColor = 4294967040L,
                ),
                OdfSpan(
                    text = ". Some text in a",
                    fontSize = 12f,
                ),
                OdfSpan(
                    text = " ",
                    fontSize = 12f,
                ),
                OdfSpan(
                    text = "box",
                    fontSize = 12f,
                ),
                OdfSpan(
                    text = ". Some text",
                    fontSize = 12f,
                ),
                OdfSpan(
                    text = " in ",
                    fontSize = 12f,
                ),
                OdfSpan(
                    text = "inverse video",
                    fontSize = 12f,
                    color = 4294967295L,
                    backgroundColor = 4278190080L,
                ),
                OdfSpan(
                    text = ".",
                    fontSize = 12f,
                ),
            ),
            textIndent = 28.800001f,
            lineHeightPercent = 1.15f,
        ),
    ),
    OdfContentBlock.Paragraph(
        paragraph = OdfParagraph(
            spans = listOf(
                OdfSpan(
                    text = "A paragraph with styled text: ",
                    fontSize = 12f,
                ),
                OdfSpan(
                    text = "subtle emphasis",
                    italic = true,
                    fontSize = 12f,
                    color = 4286611584L,
                ),
                OdfSpan(
                    text = "  ",
                    italic = true,
                    fontSize = 12f,
                    color = 4286611584L,
                ),
                OdfSpan(
                    text = "f",
                    fontSize = 12f,
                ),
                OdfSpan(
                    text = "ollowed by ",
                    fontSize = 12f,
                ),
                OdfSpan(
                    text = "strong text ",
                    bold = true,
                    fontSize = 12f,
                ),
                OdfSpan(
                    text = "a",
                    fontSize = 12f,
                ),
                OdfSpan(
                    text = "nd ",
                    fontSize = 12f,
                ),
                OdfSpan(
                    text = "intense emphasis",
                    bold = true,
                    italic = true,
                    fontSize = 12f,
                    color = 4283400637L,
                ),
                OdfSpan(
                    text = ".",
                    fontSize = 12f,
                ),
                OdfSpan(
                    text =
                        " This paragraph uses document wide styles for styling rather than" +
                            "inline text properties as demonstrated in the previous paragraph",
                    fontSize = 12f,
                ),
                OdfSpan(
                    text = " — ",
                    fontSize = 12f,
                ),
                OdfSpan(
                    text = "calibre can handle both with equal ease.",
                    fontSize = 12f,
                ),
            ),
            textIndent = 28.800001f,
            lineHeightPercent = 1.15f,
        ),
    ),
    OdfContentBlock.Paragraph(
        paragraph = OdfParagraph(
            spans = listOf(
                OdfSpan(
                    text = "Fun with fonts",
                    bold = true,
                    fontSize = 13f,
                    color = 4283400637L,
                ),
            ),
            style = ParagraphStyle.HEADING2,
            marginTop = 13.333333f,
            lineHeightPercent = 1.15f,
        ),
    ),
    OdfContentBlock.Paragraph(
        paragraph = OdfParagraph(
            spans = listOf(
                OdfSpan(
                    text =
                        "This document has embedded the Ubuntu font family." +
                            "The body text is in the Ubuntu typeface, here is ",
                    fontSize = 12f,
                ),
                OdfSpan(
                    text = "some text in the Ubuntu Mono typeface",
                    fontSize = 12f,
                ),
                OdfSpan(
                    text = ", notice how every letter has the same width, even i and m",
                    fontSize = 12f,
                ),
                OdfSpan(
                    text =
                        ". Every embedded font will automatically be embedded in the output ebook during conversion.",
                    fontSize = 12f,
                ),
                OdfSpan(
                    text = " ",
                    fontSize = 12f,
                ),
            ),
            textIndent = 28.800001f,
            lineHeightPercent = 1.15f,
        ),
    ),
    OdfContentBlock.Paragraph(
        paragraph = OdfParagraph(
            spans = listOf(
                OdfSpan(
                    text = "Paragraph level formatting",
                    bold = true,
                    fontSize = 13f,
                    color = 4283400637L,
                ),
            ),
            style = ParagraphStyle.HEADING2,
            marginTop = 13.333333f,
            lineHeightPercent = 1.15f,
        ),
    ),
    OdfContentBlock.Paragraph(
        paragraph = OdfParagraph(
            spans = listOf(
                OdfSpan(
                    text =
                        "You can do crazy things with paragraphs, if the" +
                            "urge strikes you. For instance this paragraph" +
                            "is right aligned and has a right border. It has also been given a light gray background.",
                    fontSize = 12f,
                ),
            ),
            alignment = TextAlign.End,
            textIndent = 28.800001f,
            backgroundColor = 4292730333L,
            lineHeightPercent = 1.15f,
            borders = OdfBorders(
                right = "0.50pt solid #000000",
            ),
        ),
    ),
)
