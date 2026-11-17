package com.vayunmathur.office.data.odf

import android.content.Context
import android.net.Uri
import com.vayunmathur.library.ui.odf.HtmlOdfConverter
import com.vayunmathur.library.ui.odf.MarkdownOdfConverter
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.RtfOdfConverter
import com.vayunmathur.library.ui.odf.numberUnnumberedListItems

import com.vayunmathur.office.platform.save
/**
 * Routes an opened file to the right importer by extension, with a content sniff fallback:
 * ODF/flat-ODF -> [OdfParser.parse], OOXML -> [OoxmlImporter], CSV/TSV -> [OdfParser.parseCsv],
 * Markdown -> [MarkdownOdfConverter], plain text -> a simple paragraph-per-line document.
 */
object DocumentImporter {

    fun open(context: Context, uri: Uri, fileName: String): OdfDocument {
        val doc = import(context, uri, fileName)
        // Safety net: a numbered item an importer couldn't number would render as "1." forever.
        return if (doc is OdfDocument.TextDocument) numberUnnumberedListItems(doc) else doc
    }

    private fun import(context: Context, uri: Uri, fileName: String): OdfDocument {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "odt", "ods", "odp", "odg", "fodt", "fods", "fodp", "fodg", "xml" ->
                OdfParser.parse(context, uri, fileName)
            "docx", "docm", "dotx", "dotm",
            "xlsx", "xlsm", "xltx", "xltm",
            "pptx", "pptm", "potx", "potm", "ppsx", "ppsm" ->
                OoxmlImporter.import(readBytes(context, uri), fileName)
                    ?: throw IllegalArgumentException("Unsupported or corrupt Office file")
            "doc", "xls", "ppt" -> throw IllegalArgumentException(
                "Legacy Microsoft Office 97-2003 files (.doc/.xls/.ppt) aren't supported. " +
                    "Open the file in Office or LibreOffice and save it as .docx/.xlsx/.pptx (or ODF)."
            )
            "csv" -> OdfParser.parseCsv(readText(context, uri), fileName, ',')
            "tsv", "tab" -> OdfParser.parseCsv(readText(context, uri), fileName, '\t')
            "md", "markdown" -> MarkdownOdfConverter.markdownToOdf(
                readText(context, uri),
                fileName.substringBeforeLast('.'))
            "html", "htm", "xhtml" -> HtmlOdfConverter.htmlToOdf(
                readText(context, uri),
                fileName.substringBeforeLast('.'))
            "rtf" -> RtfOdfConverter.rtfToOdf(readText(context, uri), fileName.substringBeforeLast('.'))
            "txt", "text", "log" -> plainTextToDoc(readText(context, uri), fileName)
            else -> sniff(context, uri, fileName)
        }
    }

    /** Detects the format from the bytes when the extension is unknown/absent. */
    private fun sniff(context: Context, uri: Uri, fileName: String): OdfDocument {
        val bytes = readBytes(context, uri)
        // OLE/CFB compound file (legacy binary Office, or an encrypted OOXML container).
        if (OoxmlImporter.isEncryptedOfficeFile(bytes)) throw IllegalArgumentException(
            "This looks like a legacy or password-protected Office file. Save it as .docx/.xlsx/.pptx " +
                "(or ODF) without a password and try again."
        )
        val isZip = bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        return when {
            isZip && OoxmlImporter.looksLikeOoxml(bytes) ->
                OoxmlImporter.import(bytes, fileName) ?: OdfParser.parse(context, uri, fileName)
            isZip -> OdfParser.parse(context, uri, fileName) // assume an ODF package
            else -> {
                val text = bytes.toString(Charsets.UTF_8)
                if (text.contains("office:document")) OdfParser.parse(context, uri, fileName) // flat ODF
                else plainTextToDoc(text, fileName)
            }
        }
    }

    private fun plainTextToDoc(text: String, fileName: String): OdfDocument.TextDocument {
        val blocks = text.split("\n").map { line ->
            OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(line.trimEnd('\r')))))
        }
        return OdfDocument.TextDocument(
            fileName,
            blocks.ifEmpty { listOf(OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(""))))) }
        )
    }

    private fun readBytes(context: Context, uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)

    private fun readText(context: Context, uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() } ?: ""
}
