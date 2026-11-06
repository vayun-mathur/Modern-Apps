package com.vayunmathur.office.odf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 1/4 (Ooxml*): tests for the OOXML package layer — [OoxmlPackage] zip reading, path
 * normalization, relationship resolution, content-type lookup, media handling — plus the
 * [OoxmlImporter] entry point (format dispatch, zip/OLE sniffing, encrypted-file guards,
 * best-effort fallback).
 */
class OoxmlPackageSliceTest {

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(java.util.zip.ZipEntry(name))
                zos.write(content)
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun zipText(vararg entries: Pair<String, String>) =
        zip(*entries.map { (k, v) -> k to v.toByteArray(Charsets.UTF_8) }.toTypedArray())

    // ---- normalize ----

    @Test fun normalizeRelative() {
        assertEquals("word/media/a.png", OoxmlPackage.normalize("word/document.xml", "media/a.png"))
        assertEquals("media/a.png", OoxmlPackage.normalize("word/document.xml", "../media/a.png"))
        assertEquals("xl/styles.xml", OoxmlPackage.normalize("xl/workbook.xml", "/xl/styles.xml"))
        assertEquals("word/a.png", OoxmlPackage.normalize("word/document.xml", "./a.png"))
        assertEquals("word/a.png", OoxmlPackage.normalize("word/document.xml", "sub/../a.png"))
        // Climbing past the root stays at root.
        assertEquals("a.png", OoxmlPackage.normalize("document.xml", "../../a.png"))
        // Root-level part with a bare target.
        assertEquals("styles.xml", OoxmlPackage.normalize("workbook.xml", "styles.xml"))
    }

    // ---- read: entries vs media, BOM strip, robustness ----

    @Test fun readSplitsTextAndMedia() {
        val png = byteArrayOf(1.toByte(), 2.toByte(), 3.toByte())
        val pkg = OoxmlPackage.read(zipText("word/document.xml" to "<w:doc/>") + byteArrayOf())
        assertTrue(pkg.entries.containsKey("word/document.xml"))
        assertTrue(pkg.media.isEmpty())
        val pkg2 = OoxmlPackage.read(zip("word/media/i.png" to png, "word/document.xml" to "<w:doc/>".toByteArray()))
        assertTrue(pkg2.media.containsKey("word/media/i.png"))
        assertEquals(png.toList(), pkg2.media["word/media/i.png"]!!.toList())
    }

    @Test fun readIgnoresNonXmlNonMedia() {
        val pkg = OoxmlPackage.read(zipText("word/document.xml" to "<w:doc/>", "notes.txt" to "hi"))
        assertTrue(pkg.entries.containsKey("word/document.xml"))
        assertFalse(pkg.entries.containsKey("notes.txt"))
    }

    @Test fun readStripsBom() {
        val withBom = "\uFEFF<w:document/>".toByteArray(Charsets.UTF_8)
        val pkg = OoxmlPackage.read(zip("word/document.xml" to withBom))
        assertEquals("<w:document/>", pkg.entries["word/document.xml"])
    }

    @Test fun readNeverThrowsOnGarbage() {
        val pkg = OoxmlPackage.read(byteArrayOf(9, 8, 7, 6, 5))
        assertTrue(pkg.entries.isEmpty())
        assertTrue(pkg.media.isEmpty())
        val empty = OoxmlPackage.read(ByteArray(0))
        assertTrue(empty.entries.isEmpty())
    }

    @Test fun readMediaExtensionsCaseInsensitive() {
        val pkg = OoxmlPackage.read(zip("word/media/PHOTO.JPG" to byteArrayOf(1), "word/document.xml" to "<x/>".toByteArray()))
        assertTrue(pkg.media.containsKey("word/media/PHOTO.JPG"))
        val svg = OoxmlPackage.read(zip("word/media/v.SVG" to byteArrayOf(2), "word/document.xml" to "<x/>".toByteArray()))
        assertTrue(svg.media.containsKey("word/media/v.SVG"))
    }

    // ---- relsFor / resolve ----

    private val relsXml = "<Relationships xmlns=\"urn:r\">" +
        "<Relationship Id=\"rId1\" Type=\"http://t/wordprocessingml/header\" Target=\"header1.xml\"/>" +
        "<Relationship Id=\"rId2\" Type=\"http://t/hyperlink\" Target=\"https://example.com\" TargetMode=\"External\"/>" +
        "<Relationship Id=\"rId3\" Type=\"http://t/image\" Target=\"../media/i.png\"/>" +
        "</Relationships>"

    @Test fun relsResolveInternalAndExternal() {
        val pkg = OoxmlPackage(
            mapOf("word/_rels/document.xml.rels" to relsXml),
            emptyMap())
        val rels = pkg.relsFor("word/document.xml")
        assertEquals(3, rels.size)
        assertEquals("word/header1.xml", rels["rId1"]!!.target)
        assertFalse(rels["rId1"]!!.external)
        assertEquals("https://example.com", rels["rId2"]!!.target)
        assertTrue(rels["rId2"]!!.external)
        assertEquals("media/i.png", rels["rId3"]!!.target)
        // resolve() helper.
        assertEquals("word/header1.xml", pkg.resolve("word/document.xml", "rId1"))
        assertEquals("https://example.com", pkg.resolve("word/document.xml", "rId2"))
        assertNull(pkg.resolve("word/document.xml", "nope"))
        assertNull(pkg.resolve("word/document.xml", null))
    }

    @Test fun relsMissingFileGivesEmpty() {
        val pkg = OoxmlPackage(emptyMap(), emptyMap())
        assertTrue(pkg.relsFor("word/document.xml").isEmpty())
        assertNull(pkg.resolve("word/document.xml", "rId1"))
    }

    @Test fun relsSkipsEntriesMissingIdOrTarget() {
        val bad = "<Relationships xmlns=\"urn:r\">" +
            "<Relationship Type=\"http://t/x\" Target=\"a.xml\"/>" +
            "<Relationship Id=\"rX\" Type=\"http://t/x\"/>" +
            "<Relationship Id=\"rOk\" Type=\"http://t/x\" Target=\"ok.xml\"/>" +
            "</Relationships>"
        val pkg = OoxmlPackage(mapOf("word/_rels/document.xml.rels" to bad), emptyMap())
        val rels = pkg.relsFor("word/document.xml")
        assertEquals(listOf("rOk"), rels.keys.toList())
    }

    @Test fun relsAtRootPath() {
        val pkg = OoxmlPackage(
            mapOf("_rels/document.xml.rels" to relsXml),
            emptyMap())
        assertEquals(3, pkg.relsFor("document.xml").size)
    }

    @Test fun relsAreCached() {
        val pkg = OoxmlPackage(
            mapOf("word/_rels/document.xml.rels" to relsXml),
            emptyMap())
        assertTrue(pkg.relsFor("word/document.xml") === pkg.relsFor("word/document.xml"))
    }

    // ---- mediaBytes ----

    @Test fun mediaBytesToleratesLeadingSlash() {
        val bytes = byteArrayOf(7)
        val pkg = OoxmlPackage(emptyMap(), mapOf("word/media/i.png" to bytes))
        assertTrue(pkg.mediaBytes("word/media/i.png") === bytes)
        assertTrue(pkg.mediaBytes("/word/media/i.png") === bytes)
        assertNull(pkg.mediaBytes(null))
        assertNull(pkg.mediaBytes("word/media/missing.png"))
    }

    // ---- partsOfType ----

    @Test fun partsOfTypeMatchesOverrides() {
        val ct = "<Types xmlns=\"urn:t\">" +
            "<Default Extension=\"png\" ContentType=\"image/png\"/>" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.main.document\"/>" +
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.ms-excel.sheet\"/>" +
            "</Types>"
        val pkg = OoxmlPackage(mapOf("[Content_Types].xml" to ct), emptyMap())
        assertEquals(listOf("word/document.xml"), pkg.partsOfType("document"))
        assertEquals(listOf("xl/workbook.xml"), pkg.partsOfType("SHEET"))
        assertTrue(pkg.partsOfType("presentation").isEmpty())
    }

    @Test fun partsOfTypeWithoutContentTypes() {
        val pkg = OoxmlPackage(emptyMap(), emptyMap())
        assertTrue(pkg.partsOfType("document").isEmpty())
    }

    // ---- OoxmlImporter entry point ----

    private val minimalDocx = zipText(
        "word/document.xml" to "<w:document xmlns:w=\"w\"><w:body><w:p><w:r><w:t>Hi</w:t></w:r></w:p></w:body></w:document>"
    )

    @Test fun importDispatchesByExtension() {
        val doc = OoxmlImporter.import(minimalDocx, "memo.docx")
        assertTrue(doc is com.vayunmathur.library.ui.odf.OdfDocument.TextDocument)
        assertEquals("memo.docx", doc.title)
        val para = (doc.content[0] as com.vayunmathur.library.ui.odf.OdfContentBlock.Paragraph).paragraph
        assertEquals("Hi", para.spans[0].text)
    }

    @Test fun importDispatchesByContentWhenExtensionUnknown() {
        val doc = OoxmlImporter.import(minimalDocx, "mystery.bin")
        assertTrue(doc is com.vayunmathur.library.ui.odf.OdfDocument.TextDocument)
    }

    @Test fun importDocmExtension() {
        assertTrue(OoxmlImporter.import(minimalDocx, "m.docm") is com.vayunmathur.library.ui.odf.OdfDocument.TextDocument)
    }

    @Test fun importMinimalXlsxAndPptx() {
        val xlsx = zipText(
            "xl/workbook.xml" to "<workbook xmlns=\"s\"><sheets><sheet name=\"S\" sheetId=\"1\"/></sheets></workbook>",
            "xl/worksheets/sheet1.xml" to "<worksheet xmlns=\"s\"><sheetData><row r=\"1\"><c r=\"A1\"><v>5</v></c></row></sheetData></worksheet>"
        )
        val s = OoxmlImporter.import(xlsx, "b.xlsx")
        assertTrue(s is com.vayunmathur.library.ui.odf.OdfDocument.Spreadsheet)
        assertEquals(5.0, (s as com.vayunmathur.library.ui.odf.OdfDocument.Spreadsheet).sheets[0].rows[0].cells[0].numberValue!!, 0.0001)

        val pptx = zipText(
            "ppt/slides/slide1.xml" to "<p:sld xmlns:p=\"p\"><p:cSld><p:spTree/></p:cSld></p:sld>"
        )
        val p = OoxmlImporter.import(pptx, "d.pptx")
        assertTrue(p is com.vayunmathur.library.ui.odf.OdfDocument.Presentation)
    }

    @Test fun importUnknownReturnsNull() {
        assertNull(OoxmlImporter.import(zipText("a.txt" to "hello"), "notes.txt"))
    }

    @Test fun importPasswordProtectedThrows() {
        // OLE/CFB magic header.
        val ole = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A.toByte(), 0xE1.toByte())
        try {
            OoxmlImporter.import(ole, "locked.docx")
            assertTrue(false, "expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("password", ignoreCase = true))
        }
        assertTrue(OoxmlImporter.isEncryptedOfficeFile(ole))
        assertFalse(OoxmlImporter.isEncryptedOfficeFile(minimalDocx))
        assertFalse(OoxmlImporter.isEncryptedOfficeFile(ByteArray(0)))
    }

    @Test fun importEncryptionInfoEntryThrows() {
        val pkg = zipText(
            "EncryptionInfo" to "junk",
            "word/document.xml" to "<w:document xmlns:w=\"w\"><w:body/></w:document>"
        )
        try {
            OoxmlImporter.import(pkg, "locked.docx")
            assertTrue(false, "expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("password", ignoreCase = true))
        }
    }

    @Test fun looksLikeOoxmlSniffs() {
        assertTrue(OoxmlImporter.looksLikeOoxml(minimalDocx))
        assertFalse(OoxmlImporter.looksLikeOoxml(zipText("a.txt" to "hi")))
        assertFalse(OoxmlImporter.looksLikeOoxml("not a zip".toByteArray()))
        assertFalse(OoxmlImporter.looksLikeOoxml(ByteArray(0)))
    }

    @Test fun importMalformedFallsBackToEmptyDoc() {
        // A docx part that parses as XML but has an unexpected shape still imports best-effort.
        val weird = zipText("word/document.xml" to "<w:document xmlns:w=\"w\"><w:body><w:p><w:r>")
        val doc = OoxmlImporter.import(weird, "weird.docx")
        // Either a real (possibly empty) doc or the empty fallback — but never null/crash.
        assertNotNull(doc)
    }

    @Test fun importEmptyZipFallsBackByExtension() {
        val emptyZip = zipText()
        val doc = OoxmlImporter.import(emptyZip, "empty.docx")
        assertTrue(doc is com.vayunmathur.library.ui.odf.OdfDocument.TextDocument)
        assertTrue(doc.content.isEmpty())
        val sheet = OoxmlImporter.import(emptyZip, "empty.xlsx")
        assertTrue(sheet is com.vayunmathur.library.ui.odf.OdfDocument.Spreadsheet)
        val pres = OoxmlImporter.import(emptyZip, "empty.pptx")
        assertTrue(pres is com.vayunmathur.library.ui.odf.OdfDocument.Presentation)
    }
}
