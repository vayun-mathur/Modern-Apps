package com.vayunmathur.pdf.platform
import android.content.Context
import android.net.Uri
import androidx.core.content.edit

/** Singleton holding pdf state store state and operations. */
object PdfStateStore {
    private const val PREFS_NAME = "pdf_viewer_state"

    // Key hashing to avoid unbounded SharedPreferences growth + illegal chars (high #7)
    private fun safeKey(uri: Uri): String {
        // Use stable hash of uri string + last segment for debugging
        val uriStr = uri.toString()
        val hash = uriStr.hashCode().toString(16)
        val last = uri.lastPathSegment?.take(20)?.replace(Regex("[^a-zA-Z0-9_]"), "_") ?: "doc"
        return "safe_page_${last}_$hash"
    }

    // --- Safe (Rust) viewer: remember the first-visible page per document. ---

    fun saveSafePage(context: Context, uri: Uri, page: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { putInt(safeKey(uri), page) }
    }

    fun restoreSafePage(context: Context, uri: Uri): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return try {
            prefs.getInt(safeKey(uri), 0)
        } catch (_: ClassCastException) {
            0
        }
    }
}
