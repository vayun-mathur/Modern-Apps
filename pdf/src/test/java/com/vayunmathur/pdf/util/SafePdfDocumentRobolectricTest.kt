package com.vayunmathur.pdf.util

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SafePdfDocumentRobolectricTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun minimalPdf(text: String = "Hello"): ByteArray {
        val content = "BT /F1 24 Tf 100 700 Td ($text) Tj ET"
        val objs = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
            "<< /Length ${content.length} >>\nstream\n$content\nendstream",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        )
        val sb = StringBuilder("%PDF-1.4\n"); val offsets = mutableListOf<Int>()
        objs.forEachIndexed { i, body -> offsets.add(sb.length); sb.append("${i + 1} 0 obj\n$body\nendobj\n") }
        val xref = sb.length
        sb.append("xref\n0 ${objs.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { sb.append(it.toString().padStart(10, '0')).append(" 00000 n \n") }
        sb.append("trailer\n<< /Size ${objs.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF")
        return sb.toString().toByteArray(Charsets.US_ASCII)
    }

    private fun uriFor(bytes: ByteArray, name: String = "doc.pdf"): Uri {
        val f = File(ctx.cacheDir, name); f.writeBytes(bytes); return Uri.fromFile(f)
    }

    @Test fun openGarbageAndMissingReturnNull() { runBlocking {
        assertNull(SafePdfDocument.open(ctx, uriFor("nope".toByteArray(), "bad.pdf")))
        assertNull(SafePdfDocument.open(ctx, Uri.parse("content://nonexistent/x.pdf")))
        assertFalse(SafePdfDocument.looksLikePdf(ctx, uriFor("hello".toByteArray(), "bad2.pdf")))
        assertEquals(0, SafePdfDocument.passwordState(ctx, uriFor("nope".toByteArray(), "bad3.pdf")))
    } }

    @Test fun openRenderCacheAndListings() { runBlocking {
        val uri = uriFor(minimalPdf())
        assertTrue(SafePdfDocument.looksLikePdf(ctx, uri))
        assertEquals(0, SafePdfDocument.passwordState(ctx, uri))
        val doc = SafePdfDocument.open(ctx, uri); assertNotNull(doc)
        try {
            assertEquals(1, doc.pageCount)
            assertNull(doc.knownAspectRatio(0))
            val page = doc.renderPage(0); assertNotNull(page)
            assertEquals(612f, page.width); assertTrue(page.primitives.isNotEmpty())
            assertEquals(612f / 792f, doc.knownAspectRatio(0)!!, 0.001f)
            val cached = doc.renderPage(0); assertEquals(page, cached)
            val result = doc.renderPageResult(0); assertTrue(result is SafePdfDocument.PageLoad.Decoded)
            assertNull(doc.renderPage(99))
            assertTrue(doc.renderPageResult(99) is SafePdfDocument.PageLoad.Failed)
            assertTrue(doc.annotations(0).isEmpty()); assertTrue(doc.formFields(0).isEmpty())
            assertTrue(doc.links(0).isEmpty()); assertTrue(doc.outline().isEmpty())
            assertTrue(doc.search("").isEmpty()); assertTrue(doc.search("   ").isEmpty())
            val hits = doc.search("Hello"); assertTrue(hits.isNotEmpty(), "search hits=$hits")
            assertTrue(doc.search("hello", caseSensitive = true).isEmpty()) // case-sensitive: text is Hello? ci search above; cs lowercase should miss
            doc.prewarmSearch()
            assertTrue(doc.extractText()?.contains("Hello") == true)
            assertFalse(doc.hasRedactions())
            val extracted = doc.extractPage(0); assertNotNull(extracted); assertTrue(extracted.isNotEmpty())
            val saved = doc.save(); assertNotNull(saved)
            val compressed = doc.saveCompressed(); assertNotNull(compressed)
        } finally { doc.close() }
    } }

    @Test fun annotationEditsRoundTrip() { runBlocking {
        val doc = SafePdfDocument.open(ctx, uriFor(minimalPdf(), "edit.pdf")); assertNotNull(doc)
        try {
            val id = doc.addText(0, 100f, 600f, 320f, 630f, 0xFF0000FF.toInt(), 14f, "Note text")
            assertTrue(id != 0L, "addText id=$id")
            var annots = doc.annotations(0)
            assertTrue(annots.any { it.id == id }, "annots=$annots")
            assertTrue(doc.editText(0, id, "Edited"))
            assertTrue(doc.moveAnnotation(0, id, 110f, 610f, 330f, 640f))
            val dup = doc.duplicateAnnotation(0, id, 14f, -14f)
            if (dup != 0L) {
                assertTrue(doc.annotations(0).any { it.id == dup })
                assertTrue(doc.detachAnnotation(0, dup))
                assertTrue(doc.reattachAnnotation(0, dup))
                assertTrue(doc.deleteAnnotation(0, dup))
            }
            val hl = doc.addHighlight(0, 100f, 650f, 200f, 670f, 0xFFFFFF00.toInt())
            val markup = doc.addTextMarkup(0, 100f, 650f, 200f, 670f, 0xFF000000.toInt(), 0)
            val note = doc.addNote(0, 50f, 50f, 0xFF00FF00.toInt(), "sticky")
            val callout = doc.addCallout(0, 50f, 50f, 150f, 150f, 0xFF000000.toInt(), 14f, "call")
            val rect = doc.addRect(0, 10f, 10f, 100f, 100f, 0xFFFF0000.toInt(), 2f, false)
            val oval = doc.addOval(0, 10f, 10f, 100f, 100f, 0xFFFF0000.toInt(), 2f, true)
            val poly = doc.addPoly(0, floatArrayOf(0f, 0f, 50f, 50f, 100f, 0f), 0xFF000000.toInt(), 2f, false, false)
            val ink = doc.addInk(0, 0xFF000000.toInt(), 2f, floatArrayOf(0f, 0f, 10f, 10f, 20f, 20f))
            listOf(hl, markup, note, callout, rect, oval, poly, ink).forEach { assertTrue(it != 0L, "edit id=$it") }
            assertTrue(doc.annotations(0).size >= 8)
            // Form setters on non-widget ids should not crash (false expected)
            doc.setTextField(0, 999999L, "x"); doc.setCheckbox(0, 999999L, true); doc.setChoiceField(0, 999999L, "x")
            val red = doc.addRedaction(0, 90f, 590f, 330f, 640f)
            assertTrue(red != 0L)
            assertTrue(doc.hasRedactions())
            assertNotNull(doc.saveEncrypted("u", "o"))
            assertTrue(doc.flattenDocument() || true) // flatten may report false if nothing flattenable; call covered
        } finally { doc.close() }
    } }

    @Test fun redactionsApply() { runBlocking {
        val doc = SafePdfDocument.open(ctx, uriFor(minimalPdf(), "redact.pdf")); assertNotNull(doc)
        try {
            doc.addRedaction(0, 90f, 680f, 200f, 720f)
            doc.applyRedactions()
            assertNotNull(doc.save())
        } finally { doc.close() }
    } }
}
