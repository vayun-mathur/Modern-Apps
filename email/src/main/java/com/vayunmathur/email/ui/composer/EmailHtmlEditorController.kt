@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.vayunmathur.email.ui.composer

import kotlin.uuid.Uuid
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.Spanned
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.core.graphics.drawable.toDrawable
import com.vayunmathur.library.ui.HtmlEditorController

/**
 * Email-specific controller extending the generic HtmlEditorController.
 * Adds inline image (CID) support for WYSIWYG composer, plus rich formatting
 * (headings, alignment, blockquote, inline code, colors, font size/family, hr).
 */
class EmailHtmlEditorController(
    initialHtml: String = "",
) : HtmlEditorController(
    initialHtml = initialHtml,
    htmlSerializer = { spanned -> serializeEmailHtml(spanned) },
) {
    // Custom TagHandler to restore rich formatting when setHtml re-parses the raw HTML
    val tagHandler = EmailHtmlTagHandler()

    // Observable list of inline images currently in the editor (for UI chips / size guard)
    val inlineImages: SnapshotStateList<InlineImage> = mutableStateListOf()

    /**
     * Override factory-used html injection to include our TagHandler.
     * HtmlEditor composable uses HtmlCompat.fromHtml directly with factory; we override the
     * refresh path and expose html that preserves raw html, but for setHtml (draft reload)
     * the AndroidView update block does fromHtml(html). To ensure rich tags parse, we must
     * subclass the HtmlEditor composable handling – but a simpler path is to override setHtml
     * to keep raw html and let the library HtmlEditor's factory be augmented in MainActivity.
     * For now we expose a helper that MainActivity's custom HtmlEditor copy can use;
     * however even without factory override, raw html is preserved via controller.html state
     * (commitHtml) which is what gets sent. Draft reload via setHtml will re-parse – losing colors
     * unless factory uses tagHandler. We provide the tagHandler for MainActivity to use.
     */

    override fun setTextColor(color: Int?) {
        if (color == null) super.setTextColor(null)
        else super.setTextColor(color)
    }

    override fun setHighlight(color: Int?) {
        if (color == null) super.setHighlight(null)
        else super.setHighlight(color)
    }

    /**
     * Insert an image from [sourceUri] into the editor at current cursor.
     * Returns the generated CID or null on failure.
     */
    fun insertInlineImage(
        context: Context,
        sourceUri: Uri,
        mimeType: String = "image/jpeg",
        fileName: String = "image.jpg",
    ): String? {
        val edit = editText ?: return null
        val editable = edit.text ?: return null
        val cid = "${Uuid.random()}@inline.local"

        val bitmap = decodeSampledBitmap(context, sourceUri, 1024) ?: return null
        val drawable = bitmap.toDrawable(context.resources)

        val maxWidth = 1024
        var w = bitmap.width
        var h = bitmap.height
        if (w > maxWidth) {
            val ratio = maxWidth.toFloat() / w
            w = maxWidth
            h = (h * ratio).toInt()
        }
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

        val cursor = (edit.selectionStart.coerceAtLeast(0)).coerceAtMost(editable.length)
        updating = true
        try {
            editable.insert(cursor, "\uFFFC")
            editable.setSpan(span, cursor, cursor + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            edit.setSelection((cursor + 1).coerceAtMost(editable.length))
        } finally {
            updating = false
        }
        inlineImages.add(
            InlineImage(
                cid = cid,
                localUri = sourceUri,
                mimeType = mimeType,
                fileName = fileName,
            )
        )
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

    fun inlineCleanupOrphans() {
        val edit = editText ?: return
        val editable = edit.text ?: return
        val presentCids = editable.getSpans(0, editable.length, CidImageSpan::class.java)
            .map { it.cid }.toSet()
        if (presentCids.size != inlineImages.size) {
            inlineImages.removeAll { it.cid !in presentCids }
        }
    }

    fun toInlineAttachments(): List<InlineAttachment> {
        return inlineImages.map {
            InlineAttachment(
                cid = it.cid,
                uri = it.localUri,
                mimeType = it.mimeType,
                fileName = it.fileName,
            )
        }
    }

    /** Provides sampled-bitmap decoding helpers for inline composer images. */
    companion object {
        private fun decodeSampledBitmap(context: Context, uri: Uri, reqWidth: Int): Bitmap? {
            return try {
                val optsBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, optsBounds)
                }
                val width = optsBounds.outWidth
                if (width <= 0) {
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
