package com.vayunmathur.office.data.odf

import android.util.Base64
import com.vayunmathur.library.ui.odf.OdfImage
import com.vayunmathur.library.ui.odf.OdfSpan
import org.xmlpull.v1.XmlPullParser

/** Inline image/object/note/annotation appliers (split from OdfParserInline.kt). */

/** Inline image (packaged or base64). */
internal fun OdfParser.applyInlineImage(
    parser: XmlPullParser,
    acc: InlineAcc,
    images: Map<String, ByteArray>,
    imagesOut: MutableList<OdfImage>?,
) {
    val href = getAttr(parser, "href")
    val w = acc.pendingFrameW
    val h = acc.pendingFrameH
    if (href != null && images.containsKey(href)) {
        val bytes = images[href]!!
        imagesOut?.add(packagedImage(bytes, href, w, h, acc))
        skipElement(parser)
    } else {
        readInlineImageData(parser, acc, imagesOut, w, h)
        // No real image data found (e.g. an object-replacement preview for a chart);
        // skip rather than emitting an empty "[Image]" placeholder.
    }
}

/** Packaged image with clip. */
private fun OdfParser.packagedImage(
    bytes: ByteArray,
    href: String,
    w: Float,
    h: Float,
    acc: InlineAcc,
): OdfImage {
    val (nw, nh) = decodeNaturalSize(bytes)
    val clip = parseClip(acc.pendingFrameClip) ?: parseClipLengths(acc.pendingFrameStyleClip, nw, nh)
    var img = OdfImage(
        path = href, imageData = bytes, width = w, height = h,
        naturalWidthPx = nw, naturalHeightPx = nh)
    if (clip != null) img = img.copy(
        cropLeftPct = clip[0], cropTopPct = clip[1], cropRightPct = clip[2], cropBottomPct = clip[3])
    return img
}

/** Inline base64 binary-data image. */
private fun OdfParser.readInlineImageData(
    parser: XmlPullParser,
    acc: InlineAcc,
    imagesOut: MutableList<OdfImage>?,
    w: Float,
    h: Float,
) {
    // Look for inline base64 binary-data. (A2/E37)
    val imgDepth = parser.depth
    var imgEvent = parser.next()
    while (!(imgEvent == XmlPullParser.END_TAG && parser.depth == imgDepth)) {
        if (imgEvent == XmlPullParser.START_TAG && parser.name == "binary-data") {
            imgEvent = parser.next()
            if (imgEvent == XmlPullParser.TEXT) {
                try {
                    val bytes = Base64.decode(parser.text.trim(), Base64.DEFAULT)
                    val (nw, nh) = decodeNaturalSize(bytes)
                    val clip = parseClip(acc.pendingFrameClip) ?: parseClipLengths(
                        acc.pendingFrameStyleClip,
                        nw,
                        nh)
                    var img = OdfImage(
                        path = "inline", imageData = bytes, width = w, height = h,
                        naturalWidthPx = nw, naturalHeightPx = nh)
                    if (clip != null) img = img.copy(
                        cropLeftPct = clip[0], cropTopPct = clip[1],
                        cropRightPct = clip[2], cropBottomPct = clip[3])
                    imagesOut?.add(img)
                } catch (_: Exception) {}
            }
        }
        imgEvent = parser.next()
    }
}

/** Inline embedded object (chart/math/sheet/doc). */
internal fun OdfParser.applyInlineObject(
    parser: XmlPullParser,
    objectContents: Map<String, String>,
    chartsOut: MutableList<com.vayunmathur.library.ui.odf.OdfChart>?,
    formulasOut: MutableList<String>?,
) {
    val href = getAttr(parser, "href")?.removePrefix("./")
    val xml = href?.let { objectContents["$it/content.xml"] }
    if (xml != null) {
        val chart = parseChart(xml)
        when {
            chart != null -> chartsOut?.add(chart)
            xml.contains("math") -> formulasOut?.add(xml)
            xml.contains("office:spreadsheet") -> formulasOut?.add("📊 [Embedded spreadsheet]")
            xml.contains("office:text") -> formulasOut?.add("📄 [Embedded document]")
            else -> formulasOut?.add("📦 [Embedded object]")
        }
    } else if (href != null) {
        formulasOut?.add("📦 [Embedded object]")
    }
}

/** Inline footnote. */
internal fun OdfParser.applyInlineNote(
    parser: XmlPullParser,
    acc: InlineAcc,
    styles: Map<String, StyleInfo>,
    spans: MutableList<OdfSpan>,
    footnotes: MutableList<com.vayunmathur.library.ui.odf.OdfFootnote>?,
) {
    if (acc.textBuffer.isNotEmpty()) {
        spans.add(makeSpan(acc.textBuffer.toString(), acc.currentStyleName, styles, acc.currentHref))
        acc.textBuffer.clear()
    }
    val fn = parseFootnote(parser, styles)
    if (fn != null) {
        footnotes?.add(fn)
        spans.add(OdfSpan(text = fn.citation, superscript = true, color = LINK_COLOR))
    }
}

/** Inline annotation. */
internal fun OdfParser.applyInlineAnnotation(
    parser: XmlPullParser,
    acc: InlineAcc,
    styles: Map<String, StyleInfo>,
    spans: MutableList<OdfSpan>,
) {
    if (acc.textBuffer.isNotEmpty()) {
        spans.add(makeSpan(acc.textBuffer.toString(), acc.currentStyleName, styles, acc.currentHref))
        acc.textBuffer.clear()
    }
    val annotation = parseAnnotation(parser, styles)
    if (annotation != null) {
        spans.add(OdfSpan(text = " 📝 ", annotation = annotation))
    }
}
