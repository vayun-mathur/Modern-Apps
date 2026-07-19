package com.vayunmathur.notes.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import com.vayunmathur.library.ink.SerializedStroke
import com.vayunmathur.notes.data.Note
import com.vayunmathur.notes.data.NoteBlock
import com.vayunmathur.notes.data.body
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

/**
 * Exports a note as a SINGLE self-contained Markdown document:
 *  - text blocks are emitted verbatim,
 *  - images are inlined as base64 `data:` URIs (no external files),
 *  - drawings are converted to an inline `<svg>` element.
 *
 * Export-only: the stored JSON blocks stay the source of truth; this never
 * touches storage. Reads image files, so call it off the main thread.
 */
fun exportNoteMarkdown(context: Context, note: Note): String =
    note.body().blocks
        .map { block ->
            when (block) {
                is NoteBlock.Text -> block.markdown
                is NoteBlock.Image -> imageMarkdown(context, block)
                is NoteBlock.Ink -> inkSvg(block)
            }
        }
        .filter { it.isNotBlank() }
        .joinToString("\n\n")
        .trim() + "\n"

/**
 * Writes [markdown] to `cacheDir/shared_notes/<title>.md` and returns a shareable
 * FileProvider URI for it. Reuses the same cache dir + authority as
 * [NotesViewModel.requestShare], so no extra manifest setup is needed. Used by copy
 * so a note with inlined (multi-MB) images is passed as a URI instead of raw text.
 */
fun markdownCacheUri(context: Context, note: Note, markdown: String): Uri {
    val cachePath = File(context.cacheDir, "shared_notes")
    cachePath.mkdirs()
    val file = File(cachePath, "${note.title.ifBlank { "note" }}.md")
    file.writeText(markdown)
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

/** Longest-side pixel cap and JPEG quality used when inlining images, to keep the export small. */
private const val MAX_IMAGE_DIMENSION = 1024
private const val JPEG_QUALITY = 60

/** An image inlined as an HTML `<img>` with a base64 data URI, honoring [NoteBlock.Image.widthFraction]. */
private fun imageMarkdown(context: Context, block: NoteBlock.Image): String {
    val bytes = try {
        downscaledJpegBytes(NoteImageStore.fileFor(context, block.fileName))
    } catch (e: Exception) {
        return ""
    } ?: return ""
    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
    val widthPercent = (block.widthFraction * 100).roundToInt().coerceIn(1, 100)
    return "<img src=\"data:image/jpeg;base64,$base64\" style=\"width:$widthPercent%\" />"
}

/**
 * Decodes [file], downscales so the longest side is at most [MAX_IMAGE_DIMENSION]
 * (never upscales), and re-encodes as JPEG at [JPEG_QUALITY] so the inlined base64
 * stays small. Uses inSampleSize so large files aren't fully loaded into memory, and
 * recycles bitmaps. Returns null if the file can't be decoded.
 */
private fun downscaledJpegBytes(file: File): ByteArray? {
    val path = file.absolutePath

    // First pass: read only the dimensions (inJustDecodeBounds loads no pixels).
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    // Second pass: decode at a reduced sample size to save memory on big images.
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(longest, MAX_IMAGE_DIMENSION)
    }
    val decoded = BitmapFactory.decodeFile(path, options) ?: return null

    // Scale exactly to the cap if the sampled bitmap is still too large.
    val scaled = scaleToMax(decoded, MAX_IMAGE_DIMENSION)
    return ByteArrayOutputStream().use { out ->
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        if (scaled != decoded) scaled.recycle()
        decoded.recycle()
        out.toByteArray()
    }
}

/** Largest power-of-two sample size that keeps the decoded longest side at least [target]. */
private fun sampleSizeFor(longest: Int, target: Int): Int {
    var sample = 1
    while (longest / (sample * 2) >= target) sample *= 2
    return sample
}

/** [bitmap] scaled so its longest side is [max], or the original if it's already small enough. */
private fun scaleToMax(bitmap: Bitmap, max: Int): Bitmap {
    val longest = maxOf(bitmap.width, bitmap.height)
    if (longest <= max) return bitmap
    val ratio = max.toFloat() / longest
    val width = (bitmap.width * ratio).roundToInt().coerceAtLeast(1)
    val height = (bitmap.height * ratio).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, width, height, true)
}

/** A drawing as an inline `<svg>`, one `<path>` per stroke, using the raw point coordinates. */
private fun inkSvg(block: NoteBlock.Ink): String {
    // Points are in the drawing canvas' pixel space. Size the SVG to bound them
    // (so it stays self-contained and keeps the right proportions); fall back to
    // the canvas height for an empty drawing.
    var width = 0f
    var height = 0f
    block.strokes.forEach { stroke ->
        stroke.points.forEach { p ->
            if (p.x > width) width = p.x
            if (p.y > height) height = p.y
        }
    }
    width = width.coerceAtLeast(1f)
    height = height.coerceAtLeast(block.heightDp.toFloat())

    val paths = block.strokes.mapNotNull { strokePath(it) }.joinToString("\n")
    return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"${num(width)}\" height=\"${num(height)}\" " +
        "viewBox=\"0 0 ${num(width)} ${num(height)}\">\n$paths\n</svg>"
}

/** Builds an SVG `<path>` ("M x y L x y ...") for one stroke, or null if it has no points. */
private fun strokePath(stroke: SerializedStroke): String? {
    if (stroke.points.isEmpty()) return null
    val d = stroke.points.mapIndexed { i, p ->
        "${if (i == 0) "M" else "L"} ${num(p.x)} ${num(p.y)}"
    }.joinToString(" ")
    return "<path d=\"$d\" fill=\"none\" stroke=\"${cssColor(stroke.brushColor)}\" " +
        "stroke-width=\"${num(stroke.brushSize)}\" stroke-linecap=\"round\" stroke-linejoin=\"round\" />"
}

/** An ARGB color int as a CSS `rgba(...)`, preserving alpha (e.g. highlighter transparency). */
private fun cssColor(argb: Int): String {
    val a = (argb ushr 24) and 0xFF
    val r = (argb ushr 16) and 0xFF
    val g = (argb ushr 8) and 0xFF
    val b = argb and 0xFF
    return "rgba($r,$g,$b,${num(a / 255f)})"
}

/** Formats a float compactly (drops the ".0" for whole numbers) to keep the output small. */
private fun num(value: Float): String {
    val rounded = (value * 100).roundToInt() / 100f
    return if (rounded % 1f == 0f) rounded.toInt().toString() else rounded.toString()
}
