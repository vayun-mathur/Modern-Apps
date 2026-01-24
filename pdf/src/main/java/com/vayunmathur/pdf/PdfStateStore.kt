package com.vayunmathur.pdf

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
        println(pdfPoint.pageNum)
        println("${pdfPoint.x} ${pdfPoint.y}")
    }

    fun restore(context: Context, uri: Uri): (suspend (PdfViewerState) -> Unit)? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = uri.toString()
        val value = prefs.getString(key, null) ?: return null
        val parts = value.split(',')
        if (parts.size < 3) return null
        val zoom = parts[0].toFloatOrNull() ?: 0f
        val page = parts[1].toIntOrNull() ?: 0
        val left = parts[2].toFloatOrNull() ?: 0f
        val top = parts[3].toFloatOrNull() ?: 0f
        println(page)
        println("$left $top")
        return {
            it.scrollToPage(page)
            it.scrollToPosition(PdfPoint(page, left, top))
        }
    }
}

