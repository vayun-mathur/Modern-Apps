package com.vayunmathur.pdf.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class NativeDocumentTest {
    private fun minimalPdf(text: String = "Hello"): ByteArray {
        val content = "BT /F1 24 Tf 100 700 Td ($text) Tj ET"
        val objs = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
            "<< /Length ${content.length} >>\nstream\n$content\nendstream",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        )
        val sb = StringBuilder("%PDF-1.4\n")
        val offsets = mutableListOf<Int>()
        objs.forEachIndexed { i, body ->
            offsets.add(sb.length)
            sb.append("${i + 1} 0 obj\n$body\nendobj\n")
        }
        val xref = sb.length
        sb.append("xref\n0 ${objs.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { sb.append(it.toString().padStart(10, '0')).append(" 00000 n \n") }
        sb.append("trailer\n<< /Size ${objs.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF")
        return sb.toString().toByteArray(Charsets.US_ASCII)
    }

    @Test fun nativeAvailableAndRejectsGarbage() {
        assertTrue(PdfNative.isAvailable, "host pdf_render lib should be on java.library.path")
        assertEquals(0L, PdfNative.openDocument("not a pdf".toByteArray()))
        assertEquals(0, PdfNative.pdfPasswordState("not a pdf".toByteArray()))
        PdfNative.closeDocument(0L) // documented safe
    }

    @Test fun openRenderSearchAndSave() = runBlocking {
        val bytes = minimalPdf()
        assertEquals(0, PdfNative.pdfPasswordState(bytes))
        val handle = PdfNative.openDocument(bytes)
        assertTrue(handle != 0L)
        try {
            assertEquals(1, PdfNative.getPageCount(handle))
            val wire = PdfNative.renderPage(handle, 0)
            assertNotNull(wire)
            val page = SafePdfParser.parse(wire)
            assertEquals(612f, page.width); assertEquals(792f, page.height)
            assertTrue(page.primitives.isNotEmpty())
            assertNull(PdfNative.renderPage(handle, 99))
            val saved = PdfNative.saveDocument(handle)
            assertNotNull(saved); assertTrue(saved.isNotEmpty())
            val text = PdfNative.extractText(handle)
            assertTrue(text?.contains("Hello") == true, "extractText=$text")
        } finally { PdfNative.closeDocument(handle) }
    }

    @Test fun composeDocumentLifecycle() = runBlocking {
        val doc = ComposePdfDocument.create()
        try {
            assertEquals(0, doc.pageCount())
            assertEquals(0, doc.appendPdf("garbage".toByteArray()))
            assertEquals(1, doc.appendPdf(minimalPdf("One")))
            assertEquals(1, doc.appendPdf(minimalPdf("Two")))
            assertEquals(2, doc.pageCount())
            val page = doc.renderPage(0); assertNotNull(page); assertEquals(612f, page.width)
            assertNull(doc.renderPage(99))
            assertTrue(doc.movePage(0, 1))
            assertTrue(doc.removePage(1)); assertEquals(1, doc.pageCount())
            val saved = doc.save(); assertNotNull(saved)
            // Saved bytes reopen
            val h = PdfNative.openDocument(saved); assertTrue(h != 0L); PdfNative.closeDocument(h)
        } finally { doc.close(); doc.close() } // idempotent
        assertEquals(0, doc.pageCount())
        assertEquals(0, doc.appendPdf(minimalPdf()))
        assertNull(doc.save())
    }

    @Test fun encryptedSaveReopensWithPassword() = runBlocking {
        val handle = PdfNative.openDocument(minimalPdf())
        assertTrue(handle != 0L)
        try {
            val enc = PdfNative.saveEncrypted(handle, "userpw", "ownerpw")
            if (enc != null) {
                assertEquals(1, PdfNative.pdfPasswordState(enc))
                assertEquals(0L, PdfNative.openDocument(enc))
                val h2 = PdfNative.openDocumentWithPassword(enc, "userpw")
                assertTrue(h2 != 0L); PdfNative.closeDocument(h2)
                assertEquals(0L, PdfNative.openDocumentWithPassword(enc, "wrong"))
            }
        } finally { PdfNative.closeDocument(handle) }
    }
}
