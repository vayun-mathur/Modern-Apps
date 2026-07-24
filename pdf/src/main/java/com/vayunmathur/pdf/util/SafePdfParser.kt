package com.vayunmathur.pdf.util

import androidx.compose.ui.geometry.Offset
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes the compact little-endian primitive buffer produced by the native
 * renderer ([PdfNative.renderPage]) into a [SafePdfPage].
 *
 * Wire format v3 (must stay in sync with `pdf/rust/src/lib.rs` `wire` module):
 * ```
 * header: u32 MAGIC=0x50444657, u32 VERSION=3, f32 pageWidth, f32 pageHeight, u32 primitiveCount
 *  Legacy v1 fallback: header is f32 W,H,u32 count (no magic)
 *  v2 fallback: VERSION=2 (same as v2 spec)
 * per primitive: u8 tag, then payload
 *   1 Text:   f32 x, f32 y, f32 size, u32 argb, u16 len, [utf8 bytes], u8 hasStroke, u32 strokeArgb, f32 strokeWidth
 *   2 Fill:   u32 argb, u8 evenOdd, u16 nPts, [f32 x, f32 y]...
 *   3 Stroke: u32 argb, f32 width, u8 nDash, [f32 dash]..., f32 phase, u8 cap, u8 join, f32 miter, u16 nPts, [f32 x, f32 y]...
 *   4 Image:  6*f32 ctm, u32 w, u32 h, u8 format, u32 len, [bytes]
 *   5 ClipPush: u8 evenOdd, u16 nPts, [f32 x,y]...
 *   6 ClipPop: empty
 *   7 GroupPush: u8 isolated, u8 knockout, f32 alpha, u8 blend
 *   8 GroupPop: empty
 * ```
 * Pure function -> unit-testable with no Android dependencies beyond [Offset].
 * Enforces count guards to avoid OOM: max 50k primitives.
 */
object SafePdfParser {

    private const val TAG_TEXT = 1
    private const val TAG_FILL = 2
    private const val TAG_STROKE = 3
    private const val TAG_IMAGE = 4
    private const val TAG_CLIP_PUSH = 5
    private const val TAG_CLIP_POP = 6
    private const val TAG_GROUP_PUSH = 7
    private const val TAG_GROUP_POP = 8

    const val WIRE_MAGIC: Int = 0x50444657 // 'PDFW' little-endian as u32
    const val WIRE_VERSION: Int = 3
    private const val WIRE_VERSION_V2 = 2
    const val MAX_PRIMITIVES = 50000
    const val MAX_ANNOTATIONS = 10000

    fun parse(bytes: ByteArray): SafePdfPage {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.remaining() < 12) throw IllegalArgumentException("Buffer too small")

        val firstInt = buf.int
        val wireVersion: Int
        val width: Float
        val height: Float
        val countRaw: Int

        if (firstInt == WIRE_MAGIC) {
            if (buf.remaining() < 12) throw IllegalArgumentException("v2/v3 header truncated")
            wireVersion = buf.int
            width = buf.float
            height = buf.float
            countRaw = buf.int
        } else {
            // v1 legacy: firstInt was actually width bits, reinterpret
            wireVersion = 1
            width = java.lang.Float.intBitsToFloat(firstInt)
            height = buf.float
            countRaw = buf.int
        }

        // Safety guards: count caps, version enforcement, dimension sanity
        if (width <= 0f || height <= 0f || width > 20000f || height > 20000f) {
            throw IllegalArgumentException("Invalid page dimensions $width x $height")
        }
        if (countRaw < 0 || countRaw > MAX_PRIMITIVES) {
            throw IllegalArgumentException("Primitive count out of bounds: $countRaw")
        }

        val isV2OrV3 = wireVersion >= WIRE_VERSION_V2
        // Allow v2 backward compat for cached pages, but v3 preferred. Future versions >3 tolerated if same tags.
        if (wireVersion != 1 && wireVersion != WIRE_VERSION_V2 && wireVersion != WIRE_VERSION) {
            // Forward compat: if version >3, still try to parse if tags are known, but log.
            if (wireVersion > WIRE_VERSION) {
                android.util.Log.w("SafePdfParser", "Wire version $wireVersion > $WIRE_VERSION, attempting forward compat parse")
            } else {
                throw IllegalArgumentException("Unsupported wire version: $wireVersion")
            }
        }

        val primitives = ArrayList<PdfPrimitive>(countRaw.coerceAtLeast(0))
        repeat(countRaw) {
            if (!buf.hasRemaining()) return@repeat
            when (val tag = buf.get().toInt() and 0xFF) {
                TAG_TEXT -> {
                    val x = buf.float
                    val y = buf.float
                    val size = buf.float
                    val argb = buf.int
                    val len = buf.short.toInt() and 0xFFFF
                    if (len < 0 || len > 4096) throw IllegalArgumentException("Text length out of bounds $len")
                    if (buf.remaining() < len) throw IllegalArgumentException("Text length truncated")
                    val strBytes = ByteArray(len)
                    buf.get(strBytes)
                    val strokeColor: Int?
                    val strokeWidth: Float
                    if (isV2OrV3) {
                        if (buf.remaining() < 9) throw IllegalArgumentException("Text v2 truncated")
                        val hasStroke = buf.get().toInt() != 0
                        val sArgb = buf.int
                        val sWidth = buf.float
                        if (hasStroke) {
                            strokeColor = sArgb
                            strokeWidth = sWidth
                        } else {
                            strokeColor = null
                            strokeWidth = 0f
                        }
                    } else {
                        strokeColor = null
                        strokeWidth = 0f
                    }
                    // For accurate search rect, use advance estimated from size*charCount but refined later with Paint.measureText in Kotlin UI.
                    val txt = String(strBytes, Charsets.UTF_8)
                    val adv = size * 0.5f * txt.length.coerceAtLeast(1)
                    primitives.add(
                        PdfPrimitive.Text(
                            origin = Offset(x, y),
                            size = size,
                            color = argb,
                            text = txt,
                            strokeColor = strokeColor,
                            strokeWidth = strokeWidth,
                            advance = adv,
                        )
                    )
                }

                TAG_FILL -> {
                    val argb = buf.int
                    val evenOdd = buf.get().toInt() != 0
                    primitives.add(PdfPrimitive.FillPath(argb, evenOdd, readPoints(buf)))
                }

                TAG_STROKE -> {
                    val argb = buf.int
                    val strokeWidth = buf.float
                    val nDash = buf.get().toInt() and 0xFF
                    if (nDash < 0 || nDash > 32) throw IllegalArgumentException("Dash count out of bounds $nDash")
                    if (buf.remaining() < nDash*4+4) throw IllegalArgumentException("Stroke dash truncated")
                    val dash = FloatArray(nDash) { buf.float }
                    val dashPhase = buf.float
                    val cap: Int
                    val join: Int
                    val miter: Float
                    if (isV2OrV3) {
                        if (buf.remaining() < 6) throw IllegalArgumentException("Stroke v2 cap/join truncated")
                        cap = buf.get().toInt() and 0xFF
                        join = buf.get().toInt() and 0xFF
                        miter = buf.float
                    } else {
                        cap = 0; join = 0; miter = 10f
                    }
                    primitives.add(
                        PdfPrimitive.StrokePath(argb, strokeWidth, dash, dashPhase, readPoints(buf), cap, join, miter)
                    )
                }

                TAG_IMAGE -> {
                    val ctm = FloatArray(6) { buf.float }
                    val w = buf.int
                    val h = buf.int
                    if (w <= 0 || h <= 0 || w > 20000 || h > 20000) {
                        // Skip corrupt image payload if any? Need to know len; attempt to skip
                        if (buf.remaining() >= 5) {
                            val fmt = buf.get().toInt()
                            val len = buf.int
                            if (len >=0 && buf.remaining() >= len) {
                                buf.position(buf.position()+len)
                            }
                        }
                        return@repeat
                    }
                    if (w.toLong()*h.toLong() > 16*1024*1024) {
                        // Too large, skip
                        if (buf.remaining() >= 5) {
                            val fmt = buf.get().toInt()
                            val len = buf.int
                            if (len >=0 && buf.remaining() >= len) buf.position(buf.position()+len)
                        }
                        return@repeat
                    }
                    val format = buf.get().toInt()
                    val len = buf.int
                    if (len < 0 || len > 16*1024*1024) throw IllegalArgumentException("Image data length out of bounds $len")
                    if (buf.remaining() < len) throw IllegalArgumentException("Image data truncated")
                    val data = ByteArray(len)
                    buf.get(data)
                    primitives.add(PdfPrimitive.Image(ctm, decodeBitmap(w, h, format, data)))
                }

                TAG_CLIP_PUSH -> {
                    val evenOdd = buf.get().toInt() != 0
                    val pts = readPoints(buf)
                    // Degenerate clip guard (shoelace <1e-3 or <3 pts) already enforced in Rust, but double-guard in Kotlin saveCount restore.
                    if (pts.size >= 3) {
                        primitives.add(PdfPrimitive.ClipPush(evenOdd, pts))
                    }
                }

                TAG_CLIP_POP -> {
                    primitives.add(PdfPrimitive.ClipPop)
                }

                TAG_GROUP_PUSH -> {
                    if (buf.remaining() < 6) throw IllegalArgumentException("GroupPush truncated")
                    val isolated = buf.get().toInt() != 0
                    val knockout = buf.get().toInt() != 0
                    val alpha = buf.float.coerceIn(0f,1f)
                    val blendCode = buf.get().toInt() and 0xFF
                    primitives.add(PdfPrimitive.GroupPush(isolated, knockout, alpha, BlendMode.fromCode(blendCode)))
                }

                TAG_GROUP_POP -> {
                    primitives.add(PdfPrimitive.GroupPop)
                }

                else -> throw IllegalArgumentException("Unknown primitive tag: $tag wireVersion=$wireVersion width=$width")
            }
        }

        return SafePdfPage(width, height, primitives)
    }

    /** Decode the annotation listing buffer from `listAnnotations`. */
    fun parseAnnotations(bytes: ByteArray): List<SafeAnnotation> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = buf.int
        val out = ArrayList<SafeAnnotation>(count.coerceAtLeast(0))
        repeat(count) {
            val id = buf.long
            val subtype = buf.get().toInt()
            val x0 = buf.float; val y0 = buf.float; val x1 = buf.float; val y1 = buf.float
            val color = buf.int
            val contents = readString(buf)
            out.add(SafeAnnotation(id, subtype, x0, y0, x1, y1, color, contents))
        }
        return out
    }

    /** Decode the form-field listing buffer from `listFormFields`. */
    fun parseFormFields(bytes: ByteArray): List<SafeFormField> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = buf.int
        val out = ArrayList<SafeFormField>(count.coerceAtLeast(0))
        repeat(count) {
            val id = buf.long
            val type = buf.get().toInt()
            val x0 = buf.float; val y0 = buf.float; val x1 = buf.float; val y1 = buf.float
            val name = readString(buf)
            val value = readString(buf)
            val checked = buf.get().toInt() != 0
            out.add(SafeFormField(id, type, x0, y0, x1, y1, name, value, checked))
        }
        return out
    }

    /** Decode the search-match buffer from `searchDocument`. */
    fun parseSearchMatches(bytes: ByteArray): List<SafeSearchMatch> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = buf.int
        val out = ArrayList<SafeSearchMatch>(count.coerceAtLeast(0))
        repeat(count) {
            val page = buf.int
            out.add(SafeSearchMatch(page, buf.float, buf.float, buf.float, buf.float))
        }
        return out
    }

    /** Decode the link listing buffer from `listLinks`. */
    fun parseLinks(bytes: ByteArray): List<SafeLink> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = buf.int
        val out = ArrayList<SafeLink>(count.coerceAtLeast(0))
        repeat(count) {
            val x0 = buf.float; val y0 = buf.float; val x1 = buf.float; val y1 = buf.float
            val dest = buf.int
            val uri = readString(buf)
            out.add(SafeLink(x0, y0, x1, y1, dest, uri))
        }
        return out
    }

    private fun readString(buf: ByteBuffer): String {
        val len = buf.short.toInt() and 0xFFFF
        val b = ByteArray(len)
        buf.get(b)
        return String(b, Charsets.UTF_8)
    }

    /** Decode the outline buffer from `listOutline`. */
    fun parseOutline(bytes: ByteArray): List<SafeOutlineItem> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = buf.int
        val out = ArrayList<SafeOutlineItem>(count.coerceAtLeast(0))
        repeat(count) {
            val level = buf.short.toInt() and 0xFFFF
            val page = buf.int
            val title = readString(buf)
            out.add(SafeOutlineItem(level, page, title))
        }
        return out
    }

    private fun readPoints(buf: ByteBuffer): List<Offset> {
        val n = buf.short.toInt() and 0xFFFF
        val points = ArrayList<Offset>(n.coerceAtLeast(0))
        repeat(n) {
            if (buf.remaining() < 8) return@repeat
            val x = buf.float
            val y = buf.float
            points.add(Offset(x, y))
        }
        return points
    }

    /** Decode an image payload: format 1 = JPEG bytes, 0 = raw RGBA8888. */
    private fun decodeBitmap(w: Int, h: Int, format: Int, data: ByteArray): android.graphics.Bitmap? {
        if (w <= 0 || h <= 0 || w > 20000 || h > 20000 || w.toLong()*h.toLong() > 16*1024*1024) return null
        return try {
            when (format) {
                1 -> {
                    if (data.size > 16*1024*1024) {
                        android.util.Log.w("SafePdfParser", "JPEG too large ${data.size}")
                        null
                    } else {
                        android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)
                    }
                }
                0 -> {
                    if (data.size < w * h * 4) return null
                    val pixels = IntArray(w * h)
                    var p = 0
                    for (i in pixels.indices) {
                        val r = data[p].toInt() and 0xFF
                        val g = data[p + 1].toInt() and 0xFF
                        val b = data[p + 2].toInt() and 0xFF
                        val a = data[p + 3].toInt() and 0xFF
                        pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                        p += 4
                    }
                    android.graphics.Bitmap.createBitmap(
                        pixels, w, h, android.graphics.Bitmap.Config.ARGB_8888
                    )
                }
                else -> {
                    android.util.Log.w("SafePdfParser", "Unknown bitmap format $format, showing placeholder")
                    // Return placeholder gray for JBIG2 failure path instead of null to show gray box with warning (Phase 1 verification)
                    val pw = w.coerceAtMost(100)
                    val ph = h.coerceAtMost(100)
                    val placeholder = IntArray(pw*ph) { 0xFFCCCCCC.toInt() }
                    android.graphics.Bitmap.createBitmap(placeholder, pw, ph, android.graphics.Bitmap.Config.ARGB_8888)
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w("SafePdfParser", "decodeBitmap failed w=$w h=$h format=$format", t)
            null
        }
    }
}
