package com.vayunmathur.email.composer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.vayunmathur.library.ui.HtmlEditorController
import java.util.UUID

/**
 * Email-specific controller extending the generic HtmlEditorController.
 * Adds inline image (CID) support for WYSIWYG composer.
 */
class EmailHtmlEditorController(
    initialHtml: String = "",
) : HtmlEditorController(
    initialHtml = initialHtml,
    htmlSerializer = { spanned -> serializeEmailHtml(spanned) },
) {
    // Observable list of inline images currently in the editor (for UI chips / size guard)
    val inlineImages: SnapshotStateList<InlineImage> = mutableStateListOf()

    /**
     * Insert an image from [sourceUri] into the editor at current cursor.
     * Returns the generated CID or null on failure.
     *
     * Generation:
     *  - cid = "<uuid>@inline.local" without brackets stored, header will wrap <>.
     *  - bitmap decoded scaled to max 1024w to keep .eml reasonable.
     */
    fun insertInlineImage(
        context: Context,
        sourceUri: Uri,
        mimeType: String = "image/jpeg",
        fileName: String = "image.jpg",
    ): String? {
        val edit = editText ?: return null
        val editable = edit.text ?: return null
        val cid = "${UUID.randomUUID()}@inline.local"

        val bitmap = decodeSampledBitmap(context, sourceUri, 1024) ?: return null
        val drawable = BitmapDrawable(context.resources, bitmap)

        // Scale bounds: if bitmap wider than edit width, shrink proportionally.
        // We cap at 1024 but also try to fit within ~80% of EditText width if available.
        val maxWidth = 1024
        var w = bitmap.width
        var h = bitmap.height
        if (w > maxWidth) {
            val ratio = maxWidth.toFloat() / w
            w = maxWidth
            h = (h * ratio).toInt()
        }
        // Optionally cap to EditText width if known
        val etWidth = edit.width
        if (etWidth > 0) {
            val available = (etWidth * 0.85f).toInt().coerceAtLeast(100)
            if (w > available) {
                val ratio = available.toFloat() / w
                w = available
                h = (h * ratio).toInt()
            }
        }
        drawable.setBounds(0, 0, w, h)

        val span = CidImageSpan(cid, sourceUri, drawable, mimeType, fileName)

        // Insert placeholder char at cursor
        val cursor = (edit.selectionStart.coerceAtLeast(0)).coerceAtMost(editable.length)
        // Ensure we guard watcher
        updating = true
        try {
            editable.insert(cursor, "\uFFFC")
            editable.setSpan(span, cursor, cursor + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            // Move cursor after inserted image
            edit.setSelection((cursor + 1).coerceAtMost(editable.length))
        } finally {
            updating = false
        }
        // Track
        inlineImages.add(
            InlineImage(
                cid = cid,
                localUri = sourceUri,
                mimeType = mimeType,
                fileName = fileName,
            )
        )
        // Refresh html
        inlineCleanupOrphans()
        commitHtml(htmlSerializer(editable))
        return cid
    }

    fun removeInlineImage(cid: String) {
        val edit = editText ?: return
        val editable = edit.text ?: return
        val spans = editable.getSpans(0, editable.length, CidImageSpan::class.java)
        var removed = false
        updating = true
        try {
            for (sp in spans) {
                if (sp.cid == cid) {
                    val s = editable.getSpanStart(sp)
                    val e = editable.getSpanEnd(sp)
                    editable.removeSpan(sp)
                    if (s in 0 until editable.length && e in 0..editable.length && s < e) {
                        editable.delete(s, e)
                    }
                    removed = true
                }
            }
        } finally {
            updating = false
        }
        inlineImages.removeAll { it.cid == cid }
        if (removed) {
            commitHtml(htmlSerializer(editable))
        }
    }

    /**
     * Scan editable for orphan CidImageSpans that were deleted via backspace
     * and drop them from [inlineImages] list.
     */
    fun inlineCleanupOrphans() {
        val edit = editText ?: return
        val editable = edit.text ?: return
        val presentCids = editable.getSpans(0, editable.length, CidImageSpan::class.java)
            .map { it.cid }.toSet()
        if (presentCids.size != inlineImages.size) {
            inlineImages.removeAll { it.cid !in presentCids }
        }
    }

    /** Build list ready for sending: maps tracked InlineImage -> InlineAttachment */
    fun toInlineAttachments(): List<InlineAttachment> {
        // Deduplicate by cid, keep last uri
        return inlineImages.map {
            InlineAttachment(
                cid = it.cid,
                uri = it.localUri,
                mimeType = it.mimeType,
                fileName = it.fileName,
            )
        }
    }

    companion object {
        private fun decodeSampledBitmap(context: Context, uri: Uri, reqWidth: Int): Bitmap? {
            return try {
                // First decode with inJustDecodeBounds=true to get dimensions
                val optsBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, optsBounds)
                }
                val width = optsBounds.outWidth
                if (width <= 0) {
                    // Fallback direct decode
                    return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                }
                var sample = 1
                var w = width
                while (w / 2 >= reqWidth) {
                    w /= 2
                    sample *= 2
                }
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, opts)
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}
