package com.vayunmathur.pdf.util
import android.content.Context
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.core.content.edit
import androidx.pdf.PdfPoint
import androidx.pdf.compose.PdfViewerState

object PdfStateStore {
    private const val PREFS_NAME = "pdf_viewer_state"

    // Save state as a simple comma-separated string: "page,left,top"
    fun save(context: Context, uri: Uri, centerOffset: Offset, state: PdfViewerState) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = uri.toString()
        val pdfPoint = state.visibleOffsetToPdfPoint(centerOffset) ?: return
        val value = "${state.zoom},${pdfPoint.pageNum},${pdfPoint.x},${pdfPoint.y}"
        prefs.edit { putString(key, value) }
    }

    fun restore(context: Context, uri: Uri): (suspend (PdfViewerState) -> Unit)? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = uri.toString()
        val value = try {
            prefs.getString(key, null)
        } catch (_: ClassCastException) {
            prefs.edit { remove(key) }
            null
        } ?: return null
        val parts = value.split(',')
        if (parts.size < 3) return null
        val page = parts[1].toIntOrNull() ?: 0
        val left = parts[2].toFloatOrNull() ?: 0f
        val top = parts[3].toFloatOrNull() ?: 0f
        return {
            it.scrollToPage(page)
            it.scrollToPosition(PdfPoint(page, left, top))
        }
    }

    // --- Safe (Rust) viewer: remember the first-visible page per document. ---

    fun saveSafePage(context: Context, uri: Uri, page: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { putInt("safe_page_" + uri.toString(), page) }
    }

    fun restoreSafePage(context: Context, uri: Uri): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return try {
            prefs.getInt("safe_page_" + uri.toString(), 0)
        } catch (_: ClassCastException) {
            0
        }
    }
}
