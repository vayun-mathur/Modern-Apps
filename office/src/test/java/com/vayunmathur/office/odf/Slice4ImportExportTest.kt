package com.vayunmathur.office.odf

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Slice 4/4 importer/writer tests (Robolectric): [DocumentImporter] routing via
 * registered content-resolver streams, and [OdfWriter] save round-trips through
 * real files. No network, no ViewModel.
 */
@RunWith(AndroidJUnit4::class)
class Slice4ImportExportTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private fun uriFor(name: String, bytes: ByteArray): Uri {
        val uri = Uri.parse("content://com.vayunmathur.office.test/$name")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        return uri
    }

    /**
     * content:// output backed by a real file: Robolectric's ContentResolver
     * does not serve file:// output streams, so save() targets go through a
     * registered test provider that opens [outTarget].
     */
    private var outTarget: File?
        get() = Slice4Provider.outTarget
        set(v) { Slice4Provider.outTarget = v }

    init {
        // Repo-standard fake-provider hookup (see communicate's CarrierVoicemailTest):
        // serves content:// output streams backed by [outTarget].
        org.robolectric.Robolectric.setupContentProvider(
            Slice4Provider::class.java,
            "com.vayunmathur.office.slice4test")
    }

    private fun contentUri(name: String): Uri =
        Uri.parse("content://com.vayunmathur.office.slice4test/$name")

    class Slice4Provider : android.content.ContentProvider() {
        companion object {
            @Volatile var outTarget: File? = null
        }

        override fun onCreate(): Boolean = true
        override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor? {
            val test = outTarget ?: return null
            return android.os.ParcelFileDescriptor.open(
                test, android.os.ParcelFileDescriptor.MODE_READ_WRITE
                    or android.os.ParcelFileDescriptor.MODE_CREATE
                    or android.os.ParcelFileDescriptor.MODE_TRUNCATE)
        }
        override fun query(
            uri: Uri, projection: Array<String>?, selection: String?,
            selectionArgs: Array<String>?, sortOrder: String?,
        ): android.database.Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: android.content.ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
        override fun update(
            uri: Uri, values: android.content.ContentValues?,
            selection: String?, selectionArgs: Array<String>?,
        ): Int = 0
    }

    private fun textOf(doc: OdfDocument.TextDocument): String =
        doc.content.filterIsInstance<OdfContentBlock.Paragraph>()
            .joinToString("\n") { it.paragraph.spans.joinToString("") { s -> s.text } }

    // ---- DocumentImporter.open ----

    @Test fun plainTextByExtension() {
        val doc = DocumentImporter.open(
            context, uriFor("n.txt", "hello\nworld".encodeToByteArray()), "n.txt")
        assertIs<OdfDocument.TextDocument>(doc)
        assertEquals("hello\nworld", textOf(doc))
    }

    @Test fun plainTextFallbackForUnknownExtension() {
        val doc = DocumentImporter.open(
            context, uriFor("n.xyz", "just text".encodeToByteArray()), "n.xyz")
        assertIs<OdfDocument.TextDocument>(doc)
        assertEquals("just text", textOf(doc))
    }

    @Test fun markdownByExtension() {
        val doc = DocumentImporter.open(
            context, uriFor("n.md", "# Title\n\nbody".encodeToByteArray()), "n.md")
        assertIs<OdfDocument.TextDocument>(doc)
        assertTrue(textOf(doc).contains("Title"), textOf(doc))
    }

    @Test fun csvByExtension() {
        val doc = DocumentImporter.open(
            context, uriFor("n.csv", "a,b\nc,d\n".encodeToByteArray()), "n.csv")
        assertIs<OdfDocument.Spreadsheet>(doc)
        assertEquals(2, doc.sheets[0].rows.size)
    }

    @Test fun tsvByExtension() {
        val doc = DocumentImporter.open(
            context, uriFor("n.tsv", "a\tb\n".encodeToByteArray()), "n.tsv")
        assertIs<OdfDocument.Spreadsheet>(doc)
    }

    @Test fun legacyBinaryRejected() {
        // OLE/CFB magic without extension info -> encrypted/legacy error.
        val ole = byteArrayOf(
            0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte(),
            0xA1.toByte(), 0xB1.toByte(), 0x1A.toByte(), 0xE1.toByte())
        assertFailsWith<IllegalArgumentException> {
            DocumentImporter.open(context, uriFor("n.bin", ole), "n.bin")
        }
    }

    @Test fun legacyDocExtensionRejected() {
        assertFailsWith<IllegalArgumentException> {
            DocumentImporter.open(context, uriFor("n.doc", ByteArray(0)), "n.doc")
        }
    }

    @Test fun flatOdfSniffedFromBytes() {
        val flat = """<?xml version="1.0"?>
<office:document xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
 xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0">
<office:body><office:text><text:p>sniffed</text:p></office:text></office:body>
</office:document>""".encodeToByteArray()
        // Unknown extension + flat-ODF bytes: the sniff falls back to ODF parsing,
        // which accepts single-XML flat documents, not just zip packages.
        val doc = DocumentImporter.open(context, uriFor("n.fodt", flat), "n.fodt")
        assertIs<OdfDocument.TextDocument>(doc)
        assertTrue(textOf(doc).contains("sniffed"), textOf(doc))
    }

    @Test fun htmlAndRtfByExtension() {
        val html = DocumentImporter.open(
            context, uriFor("n.html", "<p>web</p>".encodeToByteArray()), "n.html")
        assertIs<OdfDocument.TextDocument>(html)
        val rtf = DocumentImporter.open(
            context, uriFor("n.rtf", "{\\rtf1 rich}".encodeToByteArray()), "n.rtf")
        assertIs<OdfDocument.TextDocument>(rtf)
    }

    // ---- OdfWriter.save ----

    private fun sampleDoc() = OdfDocument.TextDocument(
        "Saved",
        listOf(
            OdfContentBlock.Paragraph(
                com.vayunmathur.library.ui.odf.OdfParagraph(
                    listOf(com.vayunmathur.library.ui.odf.OdfSpan("round trip")))),
        ),
        metadata = com.vayunmathur.library.ui.odf.OdfMetadata(title = "Saved"),
    )

    private fun zipEntries(zip: File): Set<String> {
        val names = mutableSetOf<String>()
        java.util.zip.ZipFile(zip).use { z ->
            val e = z.entries()
            while (e.hasMoreElements()) names.add(e.nextElement().name)
        }
        return names
    }

    @Test fun saveNewDocumentWritesValidPackage() {
        val out = File(context.cacheDir, "slice4-new.odt")
        out.delete()
        outTarget = out
        try {
            OdfWriter.save(context, null, sampleDoc(), contentUri("slice4-new.odt"))
            assertTrue(out.exists(), "save produced no file")
            assertTrue(out.length() > 100, "save wrote ${out.length()} bytes")
            val entries = zipEntries(out)
            assertTrue(entries.contains("mimetype"), entries.toString())
            assertTrue(entries.contains("content.xml"), entries.toString())
            assertTrue(entries.contains("META-INF/manifest.xml"), entries.toString())
            // content.xml carries our paragraph text.
            java.util.zip.ZipFile(out).use { z ->
                val xml = z.getInputStream(z.getEntry("content.xml")).bufferedReader().readText()
                assertTrue(xml.contains("round trip"), xml)
            }
        } finally {
            out.delete()
            outTarget = null
        }
    }

    @Test fun savePreservesSourceEntries() {
        val first = File(context.cacheDir, "slice4-first.odt")
        val second = File(context.cacheDir, "slice4-second.odt")
        first.delete()
        second.delete()
        outTarget = first
        try {
            OdfWriter.save(context, null, sampleDoc(), contentUri("slice4-first.odt"))
            shadowOf(context.contentResolver).registerInputStream(
                contentUri("slice4-first.odt"), first.inputStream())
            outTarget = second
            OdfWriter.save(
                context,
                contentUri("slice4-first.odt"),
                sampleDoc(),
                contentUri("slice4-second.odt"))
            val entries = zipEntries(second)
            assertTrue(entries.contains("content.xml"), entries.toString())
            assertTrue(entries.contains("META-INF/manifest.xml"), entries.toString())
        } finally {
            first.delete()
            second.delete()
            outTarget = null
        }
    }

    @Test fun saveAsDelegatesToSave() {
        val out = File(context.cacheDir, "slice4-saveas.odt")
        out.delete()
        outTarget = out
        try {
            OdfWriter.saveAs(context, null, sampleDoc(), contentUri("slice4-saveas.odt"))
            assertTrue(out.exists())
        } finally {
            out.delete()
            outTarget = null
        }
    }
}
