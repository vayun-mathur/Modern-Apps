package com.vayunmathur.office.odf

import com.vayunmathur.library.ui.odf.*
import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.util.zip.ZipInputStream

object OdfParser {

    fun parse(context: Context, uri: Uri, fileName: String): OdfDocument {
        val entries = extractAllEntries(context, uri)
        // Encrypted ODF detection (J67): manifest declares per-file encryption-data.
        entries.textEntries["META-INF/manifest.xml"]?.let { manifest ->
            if (manifest.contains("manifest:encryption-data") || manifest.contains("encryption-data")) {
                throw IllegalArgumentException("This document is password-protected (encrypted ODF), which is not supported.")
            }
        }
        val contentXml = entries.textEntries["content.xml"]
            ?: throw IllegalArgumentException("Not a valid ODF file: missing content.xml")
        val stylesXml = entries.textEntries["styles.xml"]
        val metaXml = entries.textEntries["meta.xml"]

        val styleMap = mutableMapOf<String, StyleInfo>()
        stylesXml?.let { styleMap.putAll(parseStyles(it)) }
        styleMap.putAll(parseStyles(contentXml))

        val listStyleMap = mutableMapOf<String, ListStyleInfo>()
        stylesXml?.let { listStyleMap.putAll(parseListStyles(it)) }
        listStyleMap.putAll(parseListStyles(contentXml))

        val numberStyleMap = mutableMapOf<String, OdfNumberFormat>()
        stylesXml?.let { numberStyleMap.putAll(parseNumberStyles(it)) }
        numberStyleMap.putAll(parseNumberStyles(contentXml))

        gradientDefs = buildMap {
            stylesXml?.let { putAll(parseGradients(it)) }
            putAll(parseGradients(contentXml))
        }

        var metadata = metaXml?.let { parseMetadata(it) } ?: OdfMetadata()

        // File size
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                metadata = metadata.copy(fileSize = fd.statSize)
            }
        } catch (_: Exception) {}

        val images = entries.binaryEntries
        val objectContents = entries.textEntries.filterKeys { it.startsWith("Object") }
        // Freeze-pane config from settings.xml, keyed by sheet name. (C2)
        val freezeMap = entries.textEntries["settings.xml"]?.let { parseSettings(it) } ?: emptyMap()

        // Parse headers/footers from styles.xml
        val headerFooter = stylesXml?.let { parseHeaderFooter(it, styleMap) }
        // Parse page geometry from styles.xml (Priority 7)
        val pageSetup = stylesXml?.let { parsePageSetup(it) }

        val type = detectType(contentXml)
        return when (type) {
            DocType.TEXT -> parseTextDocument(contentXml, styleMap, listStyleMap, fileName, metadata, images, headerFooter, objectContents, pageSetup)
            DocType.SPREADSHEET -> parseSpreadsheet(contentXml, styleMap, numberStyleMap, fileName, metadata, images, objectContents, freezeMap)
            DocType.PRESENTATION -> parsePresentation(contentXml, styleMap, fileName, metadata, images, objectContents)
            DocType.DRAWING -> parseDrawing(contentXml, styleMap, fileName, metadata, images, objectContents)
        }
    }

    /** Parses settings.xml for per-sheet freeze-pane info: sheet name -> (freezeRows, freezeCols). (C2) */
    private fun parseSettings(xml: String): Map<String, Pair<Int, Int>> {
        val out = mutableMapOf<String, Pair<Int, Int>>()
        val parser = newParser(xml)
        var e = parser.eventType
        var inTables = false
        var tablesDepth = -1
        var curSheet: String? = null
        var entryDepth = -1
        var hMode = 0; var vMode = 0; var hPos = 0; var vPos = 0
        var curItem: String? = null
        val itemText = StringBuilder()
        while (e != XmlPullParser.END_DOCUMENT) {
            when (e) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "config-item-map-named" -> if (getAttr(parser, "name") == "Tables") { inTables = true; tablesDepth = parser.depth }
                    "config-item-map-entry" -> if (inTables && curSheet == null) { curSheet = getAttr(parser, "name"); entryDepth = parser.depth; hMode = 0; vMode = 0; hPos = 0; vPos = 0 }
                    "config-item" -> if (curSheet != null) { curItem = getAttr(parser, "name"); itemText.setLength(0) }
                }
                XmlPullParser.TEXT -> if (curItem != null) itemText.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "config-item" -> {
                        val v = itemText.toString().trim().toIntOrNull() ?: 0
                        when (curItem) {
                            "HorizontalSplitMode" -> hMode = v
                            "VerticalSplitMode" -> vMode = v
                            "HorizontalSplitPosition" -> hPos = v
                            "VerticalSplitPosition" -> vPos = v
                            "PositionRight" -> if (hPos == 0) hPos = v
                            "PositionBottom" -> if (vPos == 0) vPos = v
                        }
                        curItem = null
                    }
                    "config-item-map-entry" -> if (curSheet != null && parser.depth == entryDepth) {
                        val cols = if (hMode == 2) hPos else 0
                        val rows = if (vMode == 2) vPos else 0
                        if (rows > 0 || cols > 0) out[curSheet] = rows to cols
                        curSheet = null
                    }
                    "config-item-map-named" -> if (inTables && parser.depth == tablesDepth) inTables = false
                }
            }
            e = parser.next()
        }
        return out
    }

    // --- ZIP extraction ---

    private data class ZipEntries(
        val textEntries: Map<String, String>,
        val binaryEntries: Map<String, ByteArray>
    )

    private fun extractAllEntries(context: Context, uri: Uri): ZipEntries {
        val textEntries = mutableMapOf<String, String>()
        val binaryEntries = mutableMapOf<String, ByteArray>()
        val textFiles = setOf("content.xml", "styles.xml", "meta.xml", "settings.xml", "META-INF/manifest.xml")

        val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
        // Flat ODF (.fodt/.fods/.fodp) is a single XML file, not a zip. (J70)
        val isZip = raw.size >= 2 && raw[0] == 'P'.code.toByte() && raw[1] == 'K'.code.toByte()
        if (!isZip) {
            val xml = String(raw, Charsets.UTF_8)
            if (xml.contains("office:document")) {
                textEntries["content.xml"] = xml
                textEntries["styles.xml"] = xml
                textEntries["meta.xml"] = xml
            }
            return ZipEntries(textEntries, binaryEntries)
        }

        ZipInputStream(raw.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                when {
                    name in textFiles -> textEntries[name] = zip.bufferedReader().readText()
                    name.startsWith("Object") && name.endsWith("content.xml") -> textEntries[name] = zip.bufferedReader().readText()
                    !entry.isDirectory && (
                        name.startsWith("Pictures/") || name.startsWith("media/") ||
                        name.startsWith("ObjectReplacements") || name.startsWith("Thumbnails/")
                        ) -> binaryEntries[name] = zip.readBytes()
                }
                entry = zip.nextEntry
            }
        }
        return ZipEntries(textEntries, binaryEntries)
    }

    // --- Document type detection ---

    private enum class DocType { TEXT, SPREADSHEET, PRESENTATION, DRAWING }

    private fun detectType(contentXml: String): DocType = when {
        contentXml.contains("<office:text") -> DocType.TEXT
        contentXml.contains("<office:spreadsheet") -> DocType.SPREADSHEET
        contentXml.contains("<office:presentation") -> DocType.PRESENTATION
        contentXml.contains("<office:drawing") -> DocType.DRAWING
        else -> DocType.TEXT
    }

    // --- XML helpers ---

    private fun newParser(xml: String): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(xml.reader())
        return parser
    }

    private fun getAttr(parser: XmlPullParser, localName: String): String? {
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeName(i) == localName) return parser.getAttributeValue(i)
        }
        return null
    }

    private fun skipElement(parser: XmlPullParser) {
        val depth = parser.depth
        var eventType = parser.next()
        while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
            eventType = parser.next()
        }
    }

    private fun parseDimension(value: String?): Float {
        if (value == null) return 0f
        val numeric = value.replace(Regex("[^0-9.\\-]"), "")
        val base = numeric.toFloatOrNull() ?: 0f
        return when {
            value.endsWith("cm") -> base * 37.8f
            value.endsWith("mm") -> base * 3.78f
            value.endsWith("in") -> base * 96f
            value.endsWith("pc") -> base * 16f      // pica = 12pt
            value.endsWith("pt") -> base * 1.33f
            value.endsWith("em") -> base * 16f      // relative to a 12pt default
            else -> base
        }
    }

    private fun parseColor(hex: String): Long? {
        return try {
            val colorStr = hex.removePrefix("#")
            0xFF000000L or colorStr.toLong(16)
        } catch (_: Exception) { null }
    }

    /** Parses an fo:clip="rect(top right bottom left)" value (percent tokens) into [left, top, right, bottom] fractions. (Phase 5) */
    private fun parseClip(value: String?): FloatArray? {
        if (value == null) return null
        val inner = value.substringAfter("rect(", "").substringBefore(")")
        if (inner.isBlank()) return null
        val parts = inner.trim().split(Regex("[ ,]+"))
        if (parts.size < 4) return null
        fun f(s: String): Float {
            val t = s.trim()
            return when {
                t.endsWith("%") -> (t.dropLast(1).toFloatOrNull() ?: 0f) / 100f
                else -> 0f
            }.coerceIn(0f, 0.95f)
        }
        val top = f(parts[0]); val right = f(parts[1]); val bottom = f(parts[2]); val left = f(parts[3])
        if (top == 0f && right == 0f && bottom == 0f && left == 0f) return null
        return floatArrayOf(left, top, right, bottom)
    }

    /** Parses fo:clip="rect(top right bottom left)" absolute lengths into [left, top, right, bottom] fractions
     *  using the natural image size (px@96). Returns null if no crop. (A7) */
    private fun parseClipLengths(value: String?, wPx: Float, hPx: Float): FloatArray? {
        if (value == null || wPx <= 0f || hPx <= 0f) return null
        val inner = value.substringAfter("rect(", "").substringBefore(")")
        if (inner.isBlank()) return null
        val parts = inner.trim().split(Regex("[ ,]+"))
        if (parts.size < 4) return null
        // If any token is a percentage, fall back to percent parsing.
        if (parts.any { it.trim().endsWith("%") }) return parseClip(value)
        fun frac(token: String, basePx: Float): Float = (parseDimension(token.trim()) / basePx).coerceIn(0f, 0.95f)
        val top = frac(parts[0], hPx); val right = frac(parts[1], wPx); val bottom = frac(parts[2], hPx); val left = frac(parts[3], wPx)
        if (top == 0f && right == 0f && bottom == 0f && left == 0f) return null
        return floatArrayOf(left, top, right, bottom)
    }

    /** Decodes the intrinsic pixel size of an encoded image without allocating the full bitmap. (A7) */
    private fun decodeNaturalSize(bytes: ByteArray): Pair<Float, Float> {
        return try {
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            opts.outWidth.toFloat() to opts.outHeight.toFloat()
        } catch (_: Exception) { 0f to 0f }
    }

    /** Converts an ODF draw:transform rotate(theta) into degrees clockwise for Compose (E38). */
    private fun parseRotationDegrees(transform: String?): Float {
        if (transform == null) return 0f
        val m = Regex("rotate\\(([-0-9.]+)\\)").find(transform) ?: return 0f
        val rad = m.groupValues[1].toFloatOrNull() ?: return 0f
        return -(rad * 180.0 / Math.PI).toFloat()
    }

    // --- Style parsing ---

    private data class StyleInfo(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val fontSize: Float? = null,
        val fontFamily: String? = null,
        val parentStyle: String? = null,
        val underline: Boolean = false,
        val strikethrough: Boolean = false,
        val color: Long? = null,
        val backgroundColor: Long? = null,
        val superscript: Boolean = false,
        val subscript: Boolean = false,
        val textAlign: TextAlign? = null,
        val marginLeft: Float = 0f,
        val marginTop: Float = 0f,
        val marginBottom: Float = 0f,
        // Nullable so an explicit fo:text-indent="0" (override the parent to no indent) is distinct
        // from "unspecified" (inherit the parent). Matches LibreOffice; avoids phantom first-line indents.
        val textIndent: Float? = null,
        val paragraphBackgroundColor: Long? = null,
        val breakBefore: String? = null,
        val breakAfter: String? = null,
        val drawFillColor: Long? = null,
        val drawStrokeColor: Long? = null,
        val drawStrokeWidth: Float? = null,
        val cellBackgroundColor: Long? = null,
        val cellBorderColor: Long? = null,
        val writingMode: String? = null,
        val columnWidth: Float? = null,
        val lineHeightPercent: Float? = null,
        val paragraphBorderColor: Long? = null,
        val tabStops: List<Float> = emptyList(),
        val dataStyleName: String? = null,
        val cellWrap: Boolean = false,
        val clip: String? = null,
        val cellBorders: OdfBorders? = null,
        val paraBorders: OdfBorders? = null,
        val underlineStyle: String? = null,
        val underlineColor: Long? = null,
        val letterSpacing: Float? = null,
        val textTransform: String? = null,
        val language: String? = null,
        val country: String? = null,
        val marginRight: Float = 0f,
        val keepWithNext: Boolean = false,
        val keepTogether: Boolean = false,
        val widows: Int? = null,
        val orphans: Int? = null,
        val rowHeight: Float? = null,
        val imageOpacity: Float? = null,
        val imageColorMode: String? = null,
        val transitionType: String? = null,
        val transitionSpeed: String? = null,
        val conditionalMaps: List<Pair<String, String>> = emptyList(),
        val cellVerticalAlign: String? = null,
        val fillGradientName: String? = null,
        val columnCount: Int = 1,
        val strokeDashed: Boolean = false,
        val markerStart: Boolean = false,
        val markerEnd: Boolean = false,
        val tabStopDetails: List<OdfTabStop> = emptyList(),
        val dropCapLines: Int = 0,
        val dropCapLength: Int = 1,
        val padding: Float = 0f
    )

    private fun parseStyles(xml: String): Map<String, StyleInfo> {
        val styles = mutableMapOf<String, StyleInfo>()
        val parser = newParser(xml)
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "style" && parser.namespace?.contains("style") == true) {
                val styleName = getAttr(parser, "name")
                val parentStyle = getAttr(parser, "parent-style-name")
                val dataStyle = getAttr(parser, "data-style-name")
                if (styleName != null) {
                    styles[styleName] = parseStyleProperties(parser, parentStyle).copy(dataStyleName = dataStyle)
                }
            }
            eventType = parser.next()
        }
        return styles
    }

    private fun parseStyleProperties(parser: XmlPullParser, parentStyle: String?): StyleInfo {
        var bold = false; var italic = false; var fontSize: Float? = null; var fontFamily: String? = null
        var underline = false; var strikethrough = false
        var color: Long? = null; var bgColor: Long? = null
        var superscript = false; var subscript = false
        var textAlign: TextAlign? = null
        var marginLeft = 0f; var marginTop = 0f; var marginBottom = 0f; var textIndent: Float? = null
        var marginRight = 0f; var keepWithNext = false; var keepTogether = false
        var widows: Int? = null; var orphans: Int? = null
        var paraBgColor: Long? = null
        var breakBefore: String? = null; var breakAfter: String? = null
        var drawFillColor: Long? = null; var drawStrokeColor: Long? = null; var drawStrokeWidth: Float? = null
        var cellBgColor: Long? = null; var cellBorderColor: Long? = null
        var cellWrap = false
        var cellVerticalAlign: String? = null
        var writingMode: String? = null
        var columnWidth: Float? = null
        var rowHeight: Float? = null
        var lineHeightPercent: Float? = null
        var paraBorderColor: Long? = null
        val tabStops = mutableListOf<Float>()
        val tabStopDetails = mutableListOf<OdfTabStop>()
        var dropCapLines = 0; var dropCapLength = 1
        var padding = 0f
        var clipVal: String? = null
        var imageOpacity: Float? = null; var imageColorMode: String? = null
        var fillGradientName: String? = null
        var columnCount = 1
        var strokeDashed = false; var markerStart = false; var markerEnd = false
        var transitionType: String? = null; var transitionSpeed: String? = null
        val conditionalMaps = mutableListOf<Pair<String, String>>()
        // Raw per-edge border strings (Priority 4): uniform "border" plus optional edge overrides.
        var paraBorderAll: String? = null; var paraBorderT: String? = null; var paraBorderR: String? = null; var paraBorderB: String? = null; var paraBorderL: String? = null
        var cellBorderAll: String? = null; var cellBorderT: String? = null; var cellBorderR: String? = null; var cellBorderB: String? = null; var cellBorderL: String? = null
        // Extended character properties (Round 2 R2).
        var underlineStyle: String? = null; var underlineColor: Long? = null
        var letterSpacing: Float? = null; var textTransform: String? = null
        var language: String? = null; var country: String? = null

        val depth = parser.depth
        var eventType = parser.next()
        while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "text-properties" -> {
                        if (getAttr(parser, "font-weight") == "bold") bold = true
                        if (getAttr(parser, "font-style") == "italic") italic = true
                        getAttr(parser, "font-size")?.let { s -> fontSize = s.replace("pt", "").replace("px", "").toFloatOrNull() }
                        getAttr(parser, "font-name")?.let { fontFamily = it }
                        if (fontFamily == null) getAttr(parser, "font-family")?.let { fontFamily = it }
                        getAttr(parser, "text-underline-style")?.let { if (it != "none") { underline = true; underlineStyle = it } }
                        getAttr(parser, "text-underline-color")?.let { if (it != "font-color") underlineColor = parseColor(it) }
                        getAttr(parser, "text-line-through-style")?.let { if (it != "none") strikethrough = true }
                        getAttr(parser, "letter-spacing")?.let { ls -> if (ls != "normal") letterSpacing = ls.replace("pt", "").replace("cm", "").toFloatOrNull() }
                        getAttr(parser, "text-transform")?.let { if (it != "none") textTransform = it }
                        if (textTransform == null && getAttr(parser, "font-variant") == "small-caps") textTransform = "uppercase"
                        getAttr(parser, "language")?.let { language = it }
                        getAttr(parser, "country")?.let { country = it }
                        getAttr(parser, "color")?.let { color = parseColor(it) }
                        getAttr(parser, "background-color")?.let { if (it != "transparent") bgColor = parseColor(it) }
                        getAttr(parser, "text-position")?.let { tp ->
                            when {
                                tp.startsWith("super") || (tp.contains("%") && !tp.startsWith("-")) -> superscript = true
                                tp.startsWith("sub") || tp.startsWith("-") -> subscript = true
                            }
                        }
                    }
                    "paragraph-properties" -> {
                        textAlign = when (getAttr(parser, "text-align")) {
                            "start", "left" -> TextAlign.Start
                            "center" -> TextAlign.Center
                            "end", "right" -> TextAlign.End
                            "justify" -> TextAlign.Justify
                            else -> null
                        }
                        getAttr(parser, "margin-left")?.let { marginLeft = parseDimension(it) }
                        getAttr(parser, "margin-right")?.let { marginRight = parseDimension(it) }
                        getAttr(parser, "margin-top")?.let { marginTop = parseDimension(it) }
                        getAttr(parser, "margin-bottom")?.let { marginBottom = parseDimension(it) }
                        if (getAttr(parser, "keep-with-next").let { it != null && it != "auto" }) keepWithNext = true
                        if (getAttr(parser, "keep-together").let { it != null && it != "auto" }) keepTogether = true
                        getAttr(parser, "widows")?.toIntOrNull()?.let { widows = it }
                        getAttr(parser, "orphans")?.toIntOrNull()?.let { orphans = it }
                        getAttr(parser, "text-indent")?.let { textIndent = parseDimension(it) }
                        getAttr(parser, "padding")?.let { padding = parseDimension(it) }
                        if (padding == 0f) {
                            (getAttr(parser, "padding-left") ?: getAttr(parser, "padding-top")
                                ?: getAttr(parser, "padding-right") ?: getAttr(parser, "padding-bottom"))?.let { padding = parseDimension(it) }
                        }
                        getAttr(parser, "background-color")?.let { if (it != "transparent") paraBgColor = parseColor(it) }
                        breakBefore = getAttr(parser, "break-before")
                        breakAfter = getAttr(parser, "break-after")
                        getAttr(parser, "writing-mode")?.let { writingMode = it }
                        getAttr(parser, "line-height")?.let { lh ->
                            if (lh.endsWith("%")) lh.dropLast(1).toFloatOrNull()?.let { lineHeightPercent = it / 100f }
                        }
                        getAttr(parser, "border")?.let { b ->
                            paraBorderAll = b
                            b.split(" ").lastOrNull { it.startsWith("#") }?.let { paraBorderColor = parseColor(it) }
                        }
                        getAttr(parser, "border-top")?.let { paraBorderT = it }
                        getAttr(parser, "border-right")?.let { paraBorderR = it }
                        getAttr(parser, "border-bottom")?.let { paraBorderB = it }
                        getAttr(parser, "border-left")?.let { paraBorderL = it }
                    }
                    "tab-stop" -> {
                        getAttr(parser, "position")?.let { pos ->
                            val px = parseDimension(pos)
                            tabStops.add(px)
                            val type = getAttr(parser, "type")
                            val leader = getAttr(parser, "leader-char") ?: getAttr(parser, "leader-text")
                            tabStopDetails.add(OdfTabStop(px, type, leader))
                        }
                    }
                    "drop-cap" -> {
                        dropCapLines = getAttr(parser, "lines")?.toIntOrNull() ?: 0
                        dropCapLength = getAttr(parser, "length")?.toIntOrNull() ?: 1
                    }
                    "drawing-page-properties" -> {
                        if (getAttr(parser, "fill") == "solid") {
                            getAttr(parser, "fill-color")?.let { drawFillColor = parseColor(it) }
                        }
                        getAttr(parser, "fill-color")?.let { if (drawFillColor == null) drawFillColor = parseColor(it) }
                        getAttr(parser, "transition-style")?.let { transitionType = it }
                        getAttr(parser, "transition-speed")?.let { transitionSpeed = it }
                    }
                    "graphic-properties" -> {
                        if (getAttr(parser, "fill") == "solid" || getAttr(parser, "fill") == null) {
                            getAttr(parser, "fill-color")?.let { drawFillColor = parseColor(it) }
                        }
                        if (getAttr(parser, "fill") == "gradient") getAttr(parser, "fill-gradient-name")?.let { fillGradientName = it }
                        if (getAttr(parser, "stroke") == "dash") strokeDashed = true
                        if (getAttr(parser, "marker-start") != null) markerStart = true
                        if (getAttr(parser, "marker-end") != null) markerEnd = true
                        getAttr(parser, "stroke-color")?.let { drawStrokeColor = parseColor(it) }
                        getAttr(parser, "stroke-width")?.let { drawStrokeWidth = parseDimension(it) }
                        getAttr(parser, "clip")?.let { clipVal = it }
                        getAttr(parser, "image-opacity")?.let { imageOpacity = it.removeSuffix("%").toFloatOrNull() }
                        getAttr(parser, "color-mode")?.let { imageColorMode = it }
                    }
                    "map" -> {
                        val cond = getAttr(parser, "condition"); val applyStyle = getAttr(parser, "apply-style-name")
                        if (cond != null && applyStyle != null) conditionalMaps.add(cond to applyStyle)
                    }
                    "columns" -> getAttr(parser, "column-count")?.toIntOrNull()?.let { columnCount = it }
                    "table-cell-properties" -> {
                        getAttr(parser, "background-color")?.let { if (it != "transparent") cellBgColor = parseColor(it) }
                        getAttr(parser, "border")?.let { border ->
                            cellBorderAll = border
                            border.split(" ").lastOrNull { it.startsWith("#") }?.let { cellBorderColor = parseColor(it) }
                        }
                        getAttr(parser, "border-top")?.let { cellBorderT = it }
                        getAttr(parser, "border-right")?.let { cellBorderR = it }
                        getAttr(parser, "border-bottom")?.let { cellBorderB = it }
                        getAttr(parser, "border-left")?.let { cellBorderL = it }
                        if (cellBorderColor == null) {
                            (cellBorderT ?: cellBorderR ?: cellBorderB ?: cellBorderL)
                                ?.split(" ")?.lastOrNull { it.startsWith("#") }?.let { cellBorderColor = parseColor(it) }
                        }
                        if (getAttr(parser, "wrap-option") == "wrap") cellWrap = true
                        getAttr(parser, "vertical-align")?.let { cellVerticalAlign = it }
                    }
                    "table-column-properties" -> {
                        getAttr(parser, "column-width")?.let { columnWidth = parseDimension(it) }
                    }
                    "table-row-properties" -> {
                        getAttr(parser, "row-height")?.let { rowHeight = parseDimension(it) }
                    }
                }
            }
            eventType = parser.next()
        }
        return StyleInfo(
            bold, italic, fontSize, fontFamily, parentStyle,
            underline, strikethrough, color, bgColor, superscript, subscript,
            textAlign, marginLeft, marginTop, marginBottom, textIndent, paraBgColor,
            breakBefore, breakAfter, drawFillColor, drawStrokeColor, drawStrokeWidth,
            cellBgColor, cellBorderColor, writingMode, columnWidth,
            lineHeightPercent, paraBorderColor, tabStops, null, cellWrap, clipVal,
            buildBorders(cellBorderAll, cellBorderT, cellBorderR, cellBorderB, cellBorderL),
            buildBorders(paraBorderAll, paraBorderT, paraBorderR, paraBorderB, paraBorderL),
            underlineStyle, underlineColor, letterSpacing, textTransform, language, country,
            marginRight, keepWithNext, keepTogether, widows, orphans, rowHeight, imageOpacity, imageColorMode, transitionType, transitionSpeed, conditionalMaps, cellVerticalAlign, fillGradientName, columnCount, strokeDashed, markerStart, markerEnd,
            tabStopDetails, dropCapLines, dropCapLength, padding
        )
    }

    /** Combines a uniform fo:border with per-edge overrides into [OdfBorders], or null if none. */
    private fun buildBorders(all: String?, top: String?, right: String?, bottom: String?, left: String?): OdfBorders? {
        if (all == null && top == null && right == null && bottom == null && left == null) return null
        return OdfBorders(top ?: all, right ?: all, bottom ?: all, left ?: all)
    }

    private fun resolveStyle(name: String?, styles: Map<String, StyleInfo>): StyleInfo {
        if (name == null) return StyleInfo()
        val info = styles[name] ?: return StyleInfo()
        if (info.parentStyle != null) {
            val parent = resolveStyle(info.parentStyle, styles)
            return StyleInfo(
                bold = info.bold || parent.bold,
                italic = info.italic || parent.italic,
                fontSize = info.fontSize ?: parent.fontSize,
                fontFamily = info.fontFamily ?: parent.fontFamily,
                parentStyle = null,
                underline = info.underline || parent.underline,
                strikethrough = info.strikethrough || parent.strikethrough,
                color = info.color ?: parent.color,
                backgroundColor = info.backgroundColor ?: parent.backgroundColor,
                superscript = info.superscript || parent.superscript,
                subscript = info.subscript || parent.subscript,
                textAlign = info.textAlign ?: parent.textAlign,
                marginLeft = if (info.marginLeft != 0f) info.marginLeft else parent.marginLeft,
                marginTop = if (info.marginTop != 0f) info.marginTop else parent.marginTop,
                marginBottom = if (info.marginBottom != 0f) info.marginBottom else parent.marginBottom,
                textIndent = info.textIndent ?: parent.textIndent,
                paragraphBackgroundColor = info.paragraphBackgroundColor ?: parent.paragraphBackgroundColor,
                breakBefore = info.breakBefore ?: parent.breakBefore,
                breakAfter = info.breakAfter ?: parent.breakAfter,
                drawFillColor = info.drawFillColor ?: parent.drawFillColor,
                drawStrokeColor = info.drawStrokeColor ?: parent.drawStrokeColor,
                drawStrokeWidth = info.drawStrokeWidth ?: parent.drawStrokeWidth,
                cellBackgroundColor = info.cellBackgroundColor ?: parent.cellBackgroundColor,
                cellBorderColor = info.cellBorderColor ?: parent.cellBorderColor,
                writingMode = info.writingMode ?: parent.writingMode,
                columnWidth = info.columnWidth ?: parent.columnWidth,
                lineHeightPercent = info.lineHeightPercent ?: parent.lineHeightPercent,
                paragraphBorderColor = info.paragraphBorderColor ?: parent.paragraphBorderColor,
                tabStops = if (info.tabStops.isNotEmpty()) info.tabStops else parent.tabStops,
                dataStyleName = info.dataStyleName ?: parent.dataStyleName,
                cellWrap = info.cellWrap || parent.cellWrap,
                clip = info.clip ?: parent.clip,
                cellBorders = info.cellBorders ?: parent.cellBorders,
                paraBorders = info.paraBorders ?: parent.paraBorders,
                underlineStyle = info.underlineStyle ?: parent.underlineStyle,
                underlineColor = info.underlineColor ?: parent.underlineColor,
                letterSpacing = info.letterSpacing ?: parent.letterSpacing,
                textTransform = info.textTransform ?: parent.textTransform,
                language = info.language ?: parent.language,
                country = info.country ?: parent.country,
                marginRight = if (info.marginRight != 0f) info.marginRight else parent.marginRight,
                keepWithNext = info.keepWithNext || parent.keepWithNext,
                keepTogether = info.keepTogether || parent.keepTogether,
                widows = info.widows ?: parent.widows,
                orphans = info.orphans ?: parent.orphans,
                rowHeight = info.rowHeight ?: parent.rowHeight,
                imageOpacity = info.imageOpacity ?: parent.imageOpacity,
                imageColorMode = info.imageColorMode ?: parent.imageColorMode,
                transitionType = info.transitionType ?: parent.transitionType,
                transitionSpeed = info.transitionSpeed ?: parent.transitionSpeed,
                conditionalMaps = info.conditionalMaps.ifEmpty { parent.conditionalMaps },
                cellVerticalAlign = info.cellVerticalAlign ?: parent.cellVerticalAlign,
                fillGradientName = info.fillGradientName ?: parent.fillGradientName,
                columnCount = if (info.columnCount != 1) info.columnCount else parent.columnCount,
                strokeDashed = info.strokeDashed || parent.strokeDashed,
                markerStart = info.markerStart || parent.markerStart,
                markerEnd = info.markerEnd || parent.markerEnd,
                tabStopDetails = if (info.tabStopDetails.isNotEmpty()) info.tabStopDetails else parent.tabStopDetails,
                dropCapLines = if (info.dropCapLines != 0) info.dropCapLines else parent.dropCapLines,
                dropCapLength = if (info.dropCapLines != 0) info.dropCapLength else parent.dropCapLength,
                padding = if (info.padding != 0f) info.padding else parent.padding
            )
        }
        return info
    }

    // --- List style parsing ---

    data class ListLevelStyle(
        val numbered: Boolean,
        val numberFormat: String = "1",
        val bulletChar: String = "\u2022",
        val prefix: String = "",
        val suffix: String = ".",
        val startValue: Int = 1,
        val displayLevels: Int = 1,
        // Checkbox list level (loext:checkbox on the bullet level style). (Phase 2)
        val checkbox: Boolean = false,
        // Bullet image href (list-level-style-image xlink:href), preserved for round-trip. (Phase 2)
        val bulletImagePath: String? = null
    )

    data class ListStyleInfo(val levels: Map<Int, ListLevelStyle>) {
        fun levelStyle(level: Int): ListLevelStyle {
            if (levels.isEmpty()) return ListLevelStyle(numbered = false)
            return levels[level] ?: levels[levels.keys.minByOrNull { kotlin.math.abs(it - level) }] ?: ListLevelStyle(numbered = false)
        }
        val anyNumbered: Boolean get() = levels.values.any { it.numbered }
    }

    private fun parseListStyles(xml: String): Map<String, ListStyleInfo> {
        val map = mutableMapOf<String, ListStyleInfo>()
        val parser = newParser(xml)
        var eventType = parser.eventType
        var currentName: String? = null
        var levels = mutableMapOf<Int, ListLevelStyle>()

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "list-style" -> { currentName = getAttr(parser, "name"); levels = mutableMapOf() }
                    "outline-style" -> { currentName = "%outline%"; levels = mutableMapOf() }
                    "outline-level-style" -> {
                        val lvl = getAttr(parser, "level")?.toIntOrNull() ?: 1
                        val fmt = getAttr(parser, "num-format")
                        levels[lvl] = ListLevelStyle(
                            numbered = !fmt.isNullOrEmpty(),
                            numberFormat = fmt?.ifEmpty { "1" } ?: "1",
                            prefix = getAttr(parser, "num-prefix") ?: "",
                            suffix = getAttr(parser, "num-suffix") ?: "",
                            startValue = getAttr(parser, "start-value")?.toIntOrNull() ?: 1,
                            displayLevels = getAttr(parser, "display-levels")?.toIntOrNull() ?: 1
                        )
                    }
                    "list-level-style-number" -> {
                        val lvl = getAttr(parser, "level")?.toIntOrNull() ?: 1
                        levels[lvl] = ListLevelStyle(
                            numbered = true,
                            numberFormat = getAttr(parser, "num-format")?.ifEmpty { "1" } ?: "1",
                            prefix = getAttr(parser, "num-prefix") ?: "",
                            suffix = getAttr(parser, "num-suffix") ?: ".",
                            startValue = getAttr(parser, "start-value")?.toIntOrNull() ?: 1
                        )
                    }
                    "list-level-style-bullet" -> {
                        val lvl = getAttr(parser, "level")?.toIntOrNull() ?: 1
                        val bullet = getAttr(parser, "bullet-char")?.ifEmpty { "\u2022" } ?: "\u2022"
                        val isCheckbox = getAttr(parser, "checkbox") == "true" || bullet == "\u2610" || bullet == "\u2611" || bullet == "\u2612"
                        levels[lvl] = ListLevelStyle(
                            numbered = false,
                            bulletChar = bullet,
                            checkbox = isCheckbox
                        )
                    }
                    "list-level-style-image" -> {
                        val lvl = getAttr(parser, "level")?.toIntOrNull() ?: 1
                        levels[lvl] = ListLevelStyle(numbered = false, bulletChar = "\u25AA", bulletImagePath = getAttr(parser, "href"))
                    }
                }
                XmlPullParser.END_TAG -> if ((parser.name == "list-style" || parser.name == "outline-style") && currentName != null) {
                    map[currentName] = ListStyleInfo(levels.toMap())
                    currentName = null
                }
            }
            eventType = parser.next()
        }
        return map
    }

    // --- Number format (data style) parsing (H50) ---

    private fun parseNumberStyles(xml: String): Map<String, OdfNumberFormat> {
        val map = mutableMapOf<String, OdfNumberFormat>()
        val parser = newParser(xml)
        var e = parser.eventType
        var curName: String? = null
        var type = ""
        var decimals: Int? = null
        var currency: String? = null
        var grouping = false
        var isTime = false
        var isScientific = false
        var isFraction = false
        var fracDenomDigits = 1
        val dateTokens = mutableListOf<OdfNumberToken>()
        fun flush() {
            val n = curName ?: return
            map[n] = OdfNumberFormat(
                decimals = decimals,
                percent = type == "percentage",
                currencySymbol = currency,
                grouping = grouping,
                isDate = type == "date",
                isTime = isTime || type == "time",
                isScientific = isScientific,
                isFraction = isFraction,
                fractionDenominatorDigits = fracDenomDigits,
                dateTimeTokens = dateTokens.toList()
            )
        }
        // Local names of date/time component tokens preserved in order. (Phase 4)
        val dateTokenNames = setOf(
            "year", "month", "day", "day-of-week", "hours", "minutes", "seconds",
            "am-pm", "era", "quarter", "week-of-year"
        )
        while (e != XmlPullParser.END_DOCUMENT) {
            when (e) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "number-style", "percentage-style", "currency-style", "date-style", "time-style" -> {
                        curName = getAttr(parser, "name"); type = parser.name.removeSuffix("-style")
                        decimals = null; currency = null; grouping = false
                        isTime = false; isScientific = false; isFraction = false; fracDenomDigits = 1
                        dateTokens.clear()
                    }
                    in dateTokenNames -> if (type == "date" || type == "time") {
                        dateTokens.add(OdfNumberToken(
                            kind = parser.name,
                            style = getAttr(parser, "style"),
                            textual = getAttr(parser, "textual") == "true"
                        ))
                    }
                    "text" -> if (type == "date" || type == "time") {
                        val d = parser.depth; var ev = parser.next(); val sb = StringBuilder()
                        while (!(ev == XmlPullParser.END_TAG && parser.depth == d)) {
                            if (ev == XmlPullParser.TEXT) sb.append(parser.text)
                            if (ev == XmlPullParser.END_DOCUMENT) break
                            ev = parser.next()
                        }
                        dateTokens.add(OdfNumberToken(kind = "text", text = sb.toString()))
                    }
                    "number" -> {
                        getAttr(parser, "decimal-places")?.toIntOrNull()?.let { decimals = it }
                        if (getAttr(parser, "grouping") == "true") grouping = true
                    }
                    "scientific-number" -> {
                        isScientific = true
                        getAttr(parser, "decimal-places")?.toIntOrNull()?.let { decimals = it }
                    }
                    "fraction" -> {
                        isFraction = true
                        getAttr(parser, "min-denominator-digits")?.toIntOrNull()?.let { fracDenomDigits = it }
                    }
                    "currency-symbol" -> {
                        val d = parser.depth; var ev = parser.next(); val sb = StringBuilder()
                        while (!(ev == XmlPullParser.END_TAG && parser.depth == d)) {
                            if (ev == XmlPullParser.TEXT) sb.append(parser.text)
                            if (ev == XmlPullParser.END_DOCUMENT) break
                            ev = parser.next()
                        }
                        currency = sb.toString().trim()
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "number-style", "percentage-style", "currency-style", "date-style", "time-style" -> { flush(); curName = null }
                }
            }
            e = parser.next()
        }
        return map
    }

    // --- Metadata parsing ---

    private fun parseMetadata(xml: String): OdfMetadata {
        val parser = newParser(xml)
        var eventType = parser.eventType
        var title: String? = null; var creator: String? = null; var initialCreator: String? = null
        var creationDate: String? = null; var modifiedDate: String? = null
        var description: String? = null; var subject: String? = null
        val keywords = mutableListOf<String>()
        var pageCount: Int? = null; var wordCount: Int? = null
        var charCount: Int? = null; var paragraphCount: Int? = null
        var generator: String? = null; var editingCycles: Int? = null
        val userDefined = LinkedHashMap<String, String>()
        val userDefinedTypes = LinkedHashMap<String, String>()
        var udName: String? = null
        var udType: String? = null
        var currentTag = ""

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    if (parser.name == "document-statistic") {
                        pageCount = getAttr(parser, "page-count")?.toIntOrNull()
                        wordCount = getAttr(parser, "word-count")?.toIntOrNull()
                        charCount = getAttr(parser, "character-count")?.toIntOrNull()
                        paragraphCount = getAttr(parser, "paragraph-count")?.toIntOrNull()
                    }
                    if (parser.name == "user-defined") { udName = getAttr(parser, "name"); udType = getAttr(parser, "value-type") }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text.trim()
                    if (text.isNotEmpty()) when (currentTag) {
                        "title" -> title = text
                        "creator" -> creator = text
                        "initial-creator" -> initialCreator = text
                        "creation-date" -> creationDate = text
                        "date" -> modifiedDate = text
                        "description" -> description = text
                        "subject" -> subject = text
                        "keyword" -> keywords.add(text)
                        "generator" -> generator = text
                        "editing-cycles" -> editingCycles = text.toIntOrNull()
                        "user-defined" -> udName?.let { userDefined[it] = text; if (udType != null) userDefinedTypes[it] = udType }
                    }
                }
                XmlPullParser.END_TAG -> currentTag = ""
            }
            eventType = parser.next()
        }
        return OdfMetadata(title, creator ?: initialCreator, initialCreator, creationDate, modifiedDate, description, subject, keywords, pageCount, wordCount,
            generator = generator, editingCycles = editingCycles, charCount = charCount, paragraphCount = paragraphCount, userDefined = userDefined, userDefinedTypes = userDefinedTypes)
    }

    // --- Header/Footer parsing from styles.xml ---

    private data class HeaderFooterResult(
        val headerParagraphs: List<OdfParagraph>,
        val footerParagraphs: List<OdfParagraph>
    )

    /** Parses page geometry from the first style:page-layout-properties in styles.xml. (Priority 7) */
    private fun parsePageSetup(stylesXml: String): OdfPageSetup? {
        val parser = newParser(stylesXml)
        var e = parser.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG && parser.name == "page-layout-properties") {
                val def = OdfPageSetup()
                val mAll = getAttr(parser, "margin")?.let { parseDimension(it) }
                return OdfPageSetup(
                    widthPx = getAttr(parser, "page-width")?.let { parseDimension(it) } ?: def.widthPx,
                    heightPx = getAttr(parser, "page-height")?.let { parseDimension(it) } ?: def.heightPx,
                    marginLeftPx = getAttr(parser, "margin-left")?.let { parseDimension(it) } ?: mAll ?: def.marginLeftPx,
                    marginRightPx = getAttr(parser, "margin-right")?.let { parseDimension(it) } ?: mAll ?: def.marginRightPx,
                    marginTopPx = getAttr(parser, "margin-top")?.let { parseDimension(it) } ?: mAll ?: def.marginTopPx,
                    marginBottomPx = getAttr(parser, "margin-bottom")?.let { parseDimension(it) } ?: mAll ?: def.marginBottomPx
                )
            }
            e = parser.next()
        }
        return null
    }

    private fun parseHeaderFooter(stylesXml: String, styles: Map<String, StyleInfo>): HeaderFooterResult {
        val headerParas = mutableListOf<OdfParagraph>()
        val footerParas = mutableListOf<OdfParagraph>()
        val parser = newParser(stylesXml)
        var eventType = parser.eventType
        var inHeader = false; var inFooter = false

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "header" -> if (parser.namespace?.contains("style") == true) inHeader = true
                    "footer" -> if (parser.namespace?.contains("style") == true) inFooter = true
                    "p" -> if (inHeader || inFooter) {
                        val spans = parseInlineContent(parser, "p", styles)
                        if (spans.isNotEmpty()) {
                            val para = OdfParagraph(spans)
                            if (inHeader) headerParas.add(para)
                            else footerParas.add(para)
                        }
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "header" -> if (parser.namespace?.contains("style") == true) inHeader = false
                    "footer" -> if (parser.namespace?.contains("style") == true) inFooter = false
                }
            }
            eventType = parser.next()
        }
        return HeaderFooterResult(headerParas, footerParas)
    }

    // --- Inline content & span creation ---

    private fun makeSpan(text: String, styleName: String?, styles: Map<String, StyleInfo>, href: String? = null): OdfSpan {
        val resolved = resolveStyle(styleName, styles)
        return OdfSpan(
            text = text,
            bold = resolved.bold,
            italic = resolved.italic,
            fontSize = resolved.fontSize,
            fontFamily = resolved.fontFamily,
            underline = resolved.underline || href != null,
            strikethrough = resolved.strikethrough,
            color = if (href != null && resolved.color == null) LINK_COLOR else resolved.color,
            backgroundColor = resolved.backgroundColor,
            superscript = resolved.superscript,
            subscript = resolved.subscript,
            href = href,
            underlineStyle = resolved.underlineStyle,
            underlineColor = resolved.underlineColor,
            letterSpacing = resolved.letterSpacing,
            textTransform = resolved.textTransform,
            language = resolved.language,
            country = resolved.country
        )
    }

    private fun parseInlineContent(
        parser: XmlPullParser, endTag: String,
        styles: Map<String, StyleInfo>,
        images: Map<String, ByteArray> = emptyMap(),
        footnotes: MutableList<OdfFootnote>? = null,
        imagesOut: MutableList<OdfImage>? = null,
        objectContents: Map<String, String> = emptyMap(),
        chartsOut: MutableList<OdfChart>? = null,
        formulasOut: MutableList<String>? = null
    ): List<OdfSpan> {
        val spans = mutableListOf<OdfSpan>()
        val depth = parser.depth
        var eventType = parser.next()
        val textBuffer = StringBuilder()
        var currentStyleName: String? = null
        var currentHref: String? = null
        var pendingFrameW = 0f
        var pendingFrameH = 0f
        var pendingFrameClip: String? = null
        var pendingFrameStyleClip: String? = null
        // Track-change insertion ranges (changeId -> span start index) and a buffer flush helper. (Priority 6)
        val changeStack = ArrayDeque<Pair<String, Int>>()
        fun flushBuf() {
            if (textBuffer.isNotEmpty()) {
                spans.add(makeSpan(textBuffer.toString(), currentStyleName, styles, currentHref))
                textBuffer.clear()
            }
        }

        while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth && parser.name == endTag)) {
            when (eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "span" -> {
                        if (textBuffer.isNotEmpty()) {
                            spans.add(makeSpan(textBuffer.toString(), currentStyleName, styles, currentHref))
                            textBuffer.clear()
                        }
                        currentStyleName = getAttr(parser, "style-name")
                    }
                    "a" -> {
                        if (textBuffer.isNotEmpty()) {
                            spans.add(makeSpan(textBuffer.toString(), currentStyleName, styles, currentHref))
                            textBuffer.clear()
                        }
                        currentHref = getAttr(parser, "href")
                    }
                    "tab" -> textBuffer.append("\t")
                    "s" -> textBuffer.append(" ".repeat((getAttr(parser, "c")?.toIntOrNull() ?: 1)))
                    "line-break" -> textBuffer.append("\n")
                    "frame" -> {
                        pendingFrameW = parseDimension(getAttr(parser, "width"))
                        pendingFrameH = parseDimension(getAttr(parser, "height"))
                        pendingFrameClip = getAttr(parser, "clip")
                        pendingFrameStyleClip = resolveStyle(getAttr(parser, "style-name"), styles).clip
                    }
                    "image" -> {
                        val href = getAttr(parser, "href")
                        val w = pendingFrameW
                        val h = pendingFrameH
                        if (href != null && images.containsKey(href)) {
                            val bytes = images[href]!!
                            val (nw, nh) = decodeNaturalSize(bytes)
                            val clip = parseClip(pendingFrameClip) ?: parseClipLengths(pendingFrameStyleClip, nw, nh)
                            var img = OdfImage(path = href, imageData = bytes, width = w, height = h, naturalWidthPx = nw, naturalHeightPx = nh)
                            if (clip != null) img = img.copy(cropLeftPct = clip[0], cropTopPct = clip[1], cropRightPct = clip[2], cropBottomPct = clip[3])
                            imagesOut?.add(img)
                            skipElement(parser)
                        } else {
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
                                            val clip = parseClip(pendingFrameClip) ?: parseClipLengths(pendingFrameStyleClip, nw, nh)
                                            var img = OdfImage(path = "inline", imageData = bytes, width = w, height = h, naturalWidthPx = nw, naturalHeightPx = nh)
                                            if (clip != null) img = img.copy(cropLeftPct = clip[0], cropTopPct = clip[1], cropRightPct = clip[2], cropBottomPct = clip[3])
                                            imagesOut?.add(img)
                                        } catch (_: Exception) {}
                                    }
                                }
                                imgEvent = parser.next()
                            }
                            // No real image data found (e.g. an object-replacement preview for a chart);
                            // skip rather than emitting an empty "[Image]" placeholder.
                        }
                    }
                    "object" -> {
                        val href = getAttr(parser, "href")?.removePrefix("./")
                        val xml = href?.let { objectContents["$it/content.xml"] }
                        if (xml != null) {
                            val chart = parseChart(xml)
                            when {
                                chart != null -> chartsOut?.add(chart)
                                xml.contains("math") -> formulasOut?.add(xml)
                                xml.contains("office:spreadsheet") -> formulasOut?.add("\uD83D\uDCCA [Embedded spreadsheet]")
                                xml.contains("office:text") -> formulasOut?.add("\uD83D\uDCC4 [Embedded document]")
                                else -> formulasOut?.add("\uD83D\uDCE6 [Embedded object]")
                            }
                        } else if (href != null) {
                            formulasOut?.add("\uD83D\uDCE6 [Embedded object]")
                        }
                    }
                    "note" -> {
                        if (textBuffer.isNotEmpty()) {
                            spans.add(makeSpan(textBuffer.toString(), currentStyleName, styles, currentHref))
                            textBuffer.clear()
                        }
                        val fn = parseFootnote(parser, styles)
                        if (fn != null) {
                            footnotes?.add(fn)
                            spans.add(OdfSpan(text = fn.citation, superscript = true, color = LINK_COLOR))
                        }
                    }
                    "annotation" -> {
                        if (textBuffer.isNotEmpty()) {
                            spans.add(makeSpan(textBuffer.toString(), currentStyleName, styles, currentHref))
                            textBuffer.clear()
                        }
                        val annotation = parseAnnotation(parser, styles)
                        if (annotation != null) {
                            spans.add(OdfSpan(text = " \uD83D\uDCDD ", annotation = annotation))
                        }
                    }
                    "change-start" -> {
                        flushBuf()
                        getAttr(parser, "change-id")?.let { changeStack.addLast(it to spans.size) }
                    }
                    "change-end" -> {
                        flushBuf()
                        val id = getAttr(parser, "change-id")
                        val idx = changeStack.indexOfLast { it.first == id }
                        if (idx >= 0) {
                            val (cid, start) = changeStack.removeAt(idx)
                            for (i in start until spans.size) spans[i] = spans[i].copy(changeKind = "insertion", changeId = cid)
                        }
                    }
                    "change" -> {
                        // Deletion point: pull the deleted text from the tracked-changes region. (Priority 6)
                        flushBuf()
                        getAttr(parser, "change-id")?.let { id ->
                            spans.add(OdfSpan(text = trackedDeletionText[id] ?: "", changeKind = "deletion", changeId = id))
                        }
                    }
                    "reference-ref", "bookmark-ref" -> {
                        // Cross-reference to a reference-mark/bookmark, carrying its cached display text. (Priority 5)
                        if (textBuffer.isNotEmpty()) {
                            spans.add(makeSpan(textBuffer.toString(), currentStyleName, styles, currentHref))
                            textBuffer.clear()
                        }
                        val kind = parser.name
                        val refName = getAttr(parser, "ref-name")
                        val refFormat = getAttr(parser, "reference-format")
                        val d = parser.depth; val fb = StringBuilder(); var ev = parser.next()
                        while (!(ev == XmlPullParser.END_TAG && parser.depth == d)) {
                            if (ev == XmlPullParser.TEXT) fb.append(parser.text)
                            if (ev == XmlPullParser.END_DOCUMENT) break
                            ev = parser.next()
                        }
                        spans.add(makeSpan(fb.toString(), currentStyleName, styles, currentHref)
                            .copy(refKind = kind, refName = refName, refFormat = refFormat, color = LINK_COLOR))
                    }
                    "reference-mark", "reference-mark-start", "reference-mark-end" -> {
                        // Cross-reference target marker (zero-width). (Priority 5)
                        if (textBuffer.isNotEmpty()) {
                            spans.add(makeSpan(textBuffer.toString(), currentStyleName, styles, currentHref))
                            textBuffer.clear()
                        }
                        spans.add(OdfSpan(text = "", refKind = parser.name, refName = getAttr(parser, "name")))
                    }
                    "bookmark", "bookmark-start", "bookmark-end" -> {
                        // Inline bookmark / bookmark range marker (zero-width). (Priority 9)
                        if (textBuffer.isNotEmpty()) {
                            spans.add(makeSpan(textBuffer.toString(), currentStyleName, styles, currentHref))
                            textBuffer.clear()
                        }
                        spans.add(OdfSpan(text = "", refKind = parser.name, refName = getAttr(parser, "name")))
                    }
                    in FIELD_TAGS -> {
                        // ODF text field elements -> a span carrying the field kind + cached value. (Priority 2)
                        if (textBuffer.isNotEmpty()) {
                            spans.add(makeSpan(textBuffer.toString(), currentStyleName, styles, currentHref))
                            textBuffer.clear()
                        }
                        val kind = parser.name
                        val d = parser.depth
                        val fb = StringBuilder()
                        var ev = parser.next()
                        while (!(ev == XmlPullParser.END_TAG && parser.depth == d)) {
                            if (ev == XmlPullParser.TEXT) fb.append(parser.text)
                            if (ev == XmlPullParser.END_DOCUMENT) break
                            ev = parser.next()
                        }
                        spans.add(makeSpan(fb.toString(), currentStyleName, styles, currentHref).copy(field = kind))
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "span" -> {
                        if (textBuffer.isNotEmpty()) {
                            spans.add(makeSpan(textBuffer.toString(), currentStyleName, styles, currentHref))
                            textBuffer.clear()
                        }
                        currentStyleName = null
                    }
                    "a" -> {
                        if (textBuffer.isNotEmpty()) {
                            spans.add(makeSpan(textBuffer.toString(), currentStyleName, styles, currentHref))
                            textBuffer.clear()
                        }
                        currentHref = null
                    }
                }
                XmlPullParser.TEXT -> textBuffer.append(parser.text)
            }
            eventType = parser.next()
        }
        if (textBuffer.isNotEmpty()) {
            spans.add(makeSpan(textBuffer.toString(), currentStyleName, styles, currentHref))
        }
        return spans
    }

    // --- Footnotes ---

    private fun parseFootnote(parser: XmlPullParser, styles: Map<String, StyleInfo>): OdfFootnote? {
        val isEndnote = getAttr(parser, "note-class") == "endnote"
        var citation = ""
        val body = mutableListOf<OdfParagraph>()
        val depth = parser.depth
        var eventType = parser.next()

        while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (eventType == XmlPullParser.START_TAG) when (parser.name) {
                "note-citation" -> {
                    val citDepth = parser.depth
                    var citEvent = parser.next()
                    val sb = StringBuilder()
                    while (!(citEvent == XmlPullParser.END_TAG && parser.depth == citDepth)) {
                        if (citEvent == XmlPullParser.TEXT) sb.append(parser.text)
                        citEvent = parser.next()
                    }
                    citation = sb.toString().trim()
                }
                "note-body" -> {
                    val bodyDepth = parser.depth
                    var bodyEvent = parser.next()
                    while (!(bodyEvent == XmlPullParser.END_TAG && parser.depth == bodyDepth)) {
                        if (bodyEvent == XmlPullParser.START_TAG && parser.name == "p") {
                            val spans = parseInlineContent(parser, "p", styles)
                            if (spans.isNotEmpty()) body.add(OdfParagraph(spans))
                        }
                        bodyEvent = parser.next()
                    }
                }
            }
            eventType = parser.next()
        }
        return if (citation.isNotEmpty()) OdfFootnote(citation, body, isEndnote) else null
    }

    // --- Annotations ---

    private fun parseAnnotation(parser: XmlPullParser, styles: Map<String, StyleInfo>): OdfAnnotation? {
        var author: String? = null
        var date: String? = null
        val paragraphs = mutableListOf<OdfParagraph>()
        val depth = parser.depth
        var eventType = parser.next()
        var currentTag = ""

        while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    if (parser.name == "p" && parser.namespace?.contains("text") == true) {
                        val spans = parseInlineContent(parser, "p", styles)
                        if (spans.isNotEmpty()) paragraphs.add(OdfParagraph(spans))
                        currentTag = ""
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text.trim()
                    if (text.isNotEmpty()) when (currentTag) {
                        "creator" -> author = text
                        "date" -> date = text
                    }
                }
                XmlPullParser.END_TAG -> currentTag = ""
            }
            eventType = parser.next()
        }
        return OdfAnnotation(author, date, paragraphs)
    }

    // --- Text Document ---

    private fun parseTextDocument(
        xml: String, styles: Map<String, StyleInfo>, listStyles: Map<String, ListStyleInfo>,
        title: String, metadata: OdfMetadata, images: Map<String, ByteArray>,
        headerFooter: HeaderFooterResult?, objectContents: Map<String, String> = emptyMap(),
        pageSetup: OdfPageSetup? = null
    ): OdfDocument.TextDocument {
        val content = mutableListOf<OdfContentBlock>()
        val footnotes = mutableListOf<OdfFootnote>()
        val bookmarks = mutableListOf<OdfBookmark>()
        val changes = mutableListOf<OdfChange>()
        trackedDeletionText = emptyMap()
        val parser = newParser(xml)
        var inBody = false
        var listDepth = 0
        val listTypeStack = mutableListOf<ListType>()
        val listItemCounter = mutableListOf<Int>()
        val listStyleStack = mutableListOf<ListStyleInfo?>()
        var pendingListItemChecked = false
        val outlineStyle = listStyles["%outline%"]
        val outlineCounters = IntArray(11)
        var eventType = parser.eventType

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> when {
                    parser.name == "text" && parser.namespace?.contains("office") == true -> inBody = true
                    inBody && parser.name == "tracked-changes" -> {
                        val (parsed, delText) = parseTrackedChanges(parser)
                        changes.addAll(parsed)
                        trackedDeletionText = delText
                    }
                    inBody && (parser.name == "bookmark" || parser.name == "bookmark-start") -> {
                        val bkName = getAttr(parser, "name")
                        if (bkName != null) bookmarks.add(OdfBookmark(bkName, content.size))
                    }
                    inBody && parser.name == "h" -> {
                        val level = getAttr(parser, "outline-level")?.toIntOrNull() ?: 1
                        val paraStyle = when (level) { 1 -> ParagraphStyle.HEADING1; 2 -> ParagraphStyle.HEADING2; 3 -> ParagraphStyle.HEADING3; else -> ParagraphStyle.HEADING4 }
                        val styleName = getAttr(parser, "style-name")
                        val resolved = resolveStyle(styleName, styles)
                        val inlineImages = mutableListOf<OdfImage>()
                        val inlineCharts = mutableListOf<OdfChart>()
                        val inlineFormulas = mutableListOf<String>()
                        val spans = parseInlineContent(parser, "h", styles, images, footnotes, inlineImages, objectContents, inlineCharts, inlineFormulas)
                        if (resolved.breakBefore == "page") content.add(OdfContentBlock.PageBreak)
                        val direction = parseDirection(resolved.writingMode)
                        val hLevelStyle = if (listDepth > 0) listStyleStack.lastOrNull()?.levelStyle(listDepth) else null
                        // Automatic outline heading numbering (text:outline-style). (Round 3)
                        val outlineNum: String? = outlineStyle?.let { os ->
                            val lvlStyle = os.levels[level]
                            if (lvlStyle?.numbered != true) null else {
                                outlineCounters[level]++
                                if (outlineCounters[level] < lvlStyle.startValue) outlineCounters[level] = lvlStyle.startValue
                                for (i in level + 1..10) outlineCounters[i] = 0
                                val d = lvlStyle.displayLevels.coerceIn(1, level)
                                ((level - d + 1)..level).joinToString(".") { lv ->
                                    formatListNumber(outlineCounters[lv].coerceAtLeast(1), os.levels[lv]?.numberFormat ?: "1")
                                } + lvlStyle.suffix
                            }
                        }
                        content.add(OdfContentBlock.Paragraph(OdfParagraph(
                            spans = spans, style = paraStyle,
                            alignment = resolved.textAlign, marginLeft = resolved.marginLeft,
                            marginTop = resolved.marginTop, marginBottom = resolved.marginBottom,
                            textIndent = resolved.textIndent ?: 0f, backgroundColor = resolved.paragraphBackgroundColor,
                            direction = direction,
                            lineHeightPercent = resolved.lineHeightPercent,
                            borderColor = resolved.paragraphBorderColor,
                            borders = resolved.paraBorders,
                            marginRight = resolved.marginRight,
                            keepWithNext = resolved.keepWithNext,
                            keepTogether = resolved.keepTogether,
                            widows = resolved.widows,
                            orphans = resolved.orphans,
                            tabStops = resolved.tabStops,
                            tabStopDetails = resolved.tabStopDetails,
                            dropCapLines = resolved.dropCapLines,
                            dropCapLength = resolved.dropCapLength,
                            padding = resolved.padding,
                            listLevel = if (listDepth > 0) listDepth else 0,
                            listType = if (hLevelStyle?.numbered == true) ListType.NUMBERED else ListType.BULLET,
                            listItemIndex = if (listItemCounter.isNotEmpty()) listItemCounter.last() else 0,
                            listNumberFormat = hLevelStyle?.numberFormat ?: "1",
                            listBulletChar = hLevelStyle?.bulletChar ?: "\u2022",
                            listNumberPrefix = hLevelStyle?.prefix ?: "",
                            listNumberSuffix = hLevelStyle?.suffix ?: ".",
                            outlineNumber = outlineNum
                        )))
                        for (img in inlineImages) content.add(OdfContentBlock.Image(img))
                        for (ch in inlineCharts) content.add(OdfContentBlock.Chart(ch))
                        for (f in inlineFormulas) content.add(if (f.contains("math")) OdfContentBlock.Formula(f) else OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(text = f, italic = true)), alignment = TextAlign.Center)))
                        if (resolved.breakAfter == "page") content.add(OdfContentBlock.PageBreak)
                    }
                    inBody && parser.name == "p" && parser.namespace?.contains("text") == true -> {
                        val styleName = getAttr(parser, "style-name")
                        val resolved = resolveStyle(styleName, styles)
                        if (resolved.breakBefore == "page") content.add(OdfContentBlock.PageBreak)
                        val style = if (listDepth > 0) ParagraphStyle.LIST_ITEM else ParagraphStyle.BODY
                        val inlineImages = mutableListOf<OdfImage>()
                        val inlineCharts = mutableListOf<OdfChart>()
                        val inlineFormulas = mutableListOf<String>()
                        val spans = parseInlineContent(parser, "p", styles, images, footnotes, inlineImages, objectContents, inlineCharts, inlineFormulas)
                        val direction = parseDirection(resolved.writingMode)
                        if (spans.isNotEmpty() || listDepth > 0) {
                            val itemIdx = if (listItemCounter.isNotEmpty()) listItemCounter.last() else 0
                            val levelStyle = listStyleStack.lastOrNull()?.levelStyle(listDepth)
                            val listTypeResolved = when {
                                levelStyle?.checkbox == true -> ListType.CHECKBOX
                                levelStyle != null -> if (levelStyle.numbered) ListType.NUMBERED else ListType.BULLET
                                listTypeStack.isNotEmpty() -> listTypeStack.last()
                                else -> ListType.BULLET
                            }
                            content.add(OdfContentBlock.Paragraph(OdfParagraph(
                                spans = spans, style = style,
                                alignment = resolved.textAlign, marginLeft = resolved.marginLeft,
                                marginTop = resolved.marginTop, marginBottom = resolved.marginBottom,
                                textIndent = resolved.textIndent ?: 0f, backgroundColor = resolved.paragraphBackgroundColor,
                                listLevel = listDepth,
                                listType = listTypeResolved,
                                listItemIndex = itemIdx,
                                listChecked = pendingListItemChecked,
                                direction = direction,
                                lineHeightPercent = resolved.lineHeightPercent,
                                borderColor = resolved.paragraphBorderColor,
                                borders = resolved.paraBorders,
                                marginRight = resolved.marginRight,
                                keepWithNext = resolved.keepWithNext,
                                keepTogether = resolved.keepTogether,
                                widows = resolved.widows,
                                orphans = resolved.orphans,
                                tabStops = resolved.tabStops,
                                tabStopDetails = resolved.tabStopDetails,
                                dropCapLines = resolved.dropCapLines,
                                dropCapLength = resolved.dropCapLength,
                                padding = resolved.padding,
                                listNumberFormat = levelStyle?.numberFormat ?: "1",
                                listBulletChar = levelStyle?.bulletChar ?: "\u2022",
                                listNumberPrefix = levelStyle?.prefix ?: "",
                                listNumberSuffix = levelStyle?.suffix ?: "."
                            )))
                        }
                        for (img in inlineImages) content.add(OdfContentBlock.Image(img))
                        for (ch in inlineCharts) content.add(OdfContentBlock.Chart(ch))
                        for (f in inlineFormulas) content.add(if (f.contains("math")) OdfContentBlock.Formula(f) else OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(text = f, italic = true)), alignment = TextAlign.Center)))
                        if (resolved.breakAfter == "page") content.add(OdfContentBlock.PageBreak)
                    }
                    inBody && parser.name == "section" -> {
                        val nm = getAttr(parser, "name") ?: "Section"
                        val cols = resolveStyle(getAttr(parser, "style-name"), styles).columnCount
                        content.add(OdfContentBlock.SectionStart(nm, cols))
                    }
                    inBody && parser.name == "list" -> {
                        listDepth++
                        val styleName = getAttr(parser, "style-name")
                        val styleInfo = when {
                            styleName != null -> listStyles[styleName] ?: listStyleStack.lastOrNull()
                            else -> listStyleStack.lastOrNull()
                        }
                        val type = when {
                            styleInfo != null -> if (styleInfo.levelStyle(listDepth).numbered) ListType.NUMBERED else ListType.BULLET
                            listTypeStack.isNotEmpty() -> listTypeStack.last()
                            else -> ListType.BULLET
                        }
                        listTypeStack.add(type)
                        listItemCounter.add(0)
                        listStyleStack.add(styleInfo)
                    }
                    inBody && parser.name == "list-item" -> {
                        if (listItemCounter.isNotEmpty()) {
                            listItemCounter[listItemCounter.size - 1]++
                        }
                        pendingListItemChecked = getAttr(parser, "checkbox-status")?.let { it == "checked" || it == "true" } ?: false
                    }
                    inBody && parser.name == "table" && parser.namespace?.contains("table") == true -> {
                        content.add(OdfContentBlock.Table(parseTextTable(parser, styles)))
                    }
                    inBody && parser.name == "table-of-content" -> {
                        parseTocContent(parser, styles, content)
                    }
                    inBody && parser.name == "frame" && parser.namespace?.contains("draw") == true -> {
                        val frame = parseSingleFrame(parser, styles, images)
                        frame.image?.let { content.add(OdfContentBlock.Image(it)) }
                        for (para in frame.paragraphs) {
                            content.add(OdfContentBlock.Paragraph(para))
                        }
                    }
                }
                XmlPullParser.END_TAG -> when {
                    parser.name == "text" && parser.namespace?.contains("office") == true -> inBody = false
                    parser.name == "list" && inBody -> {
                        listDepth--
                        if (listTypeStack.isNotEmpty()) listTypeStack.removeAt(listTypeStack.size - 1)
                        if (listItemCounter.isNotEmpty()) listItemCounter.removeAt(listItemCounter.size - 1)
                        if (listStyleStack.isNotEmpty()) listStyleStack.removeAt(listStyleStack.size - 1)
                    }
                    parser.name == "section" && inBody -> content.add(OdfContentBlock.SectionEnd)
                }
            }
            eventType = parser.next()
        }
        return OdfDocument.TextDocument(title, content, metadata, images, footnotes,
            headerParagraphs = headerFooter?.headerParagraphs ?: emptyList(),
            footerParagraphs = headerFooter?.footerParagraphs ?: emptyList(),
            bookmarks = bookmarks, changes = changes, pageSetup = pageSetup)
    }

    private fun parseDirection(writingMode: String?): LayoutDirection? = when {
        writingMode == null -> null
        writingMode.startsWith("rl") -> LayoutDirection.Rtl
        writingMode.startsWith("lr") -> LayoutDirection.Ltr
        else -> null
    }

    // --- Tables in text documents ---

    private fun parseTextTable(parser: XmlPullParser, styles: Map<String, StyleInfo>): OdfTable {
        val tableName = getAttr(parser, "name") ?: ""
        val columns = mutableListOf<OdfTableColumn>()
        val rows = mutableListOf<OdfTableRow>()
        var headerRowCount = 0
        var inHeader = false
        val depth = parser.depth
        var eventType = parser.next()

        while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (eventType == XmlPullParser.START_TAG) when (parser.name) {
                "table-column" -> {
                    val repeated = getAttr(parser, "number-columns-repeated")?.toIntOrNull() ?: 1
                    val styleName = getAttr(parser, "style-name")
                    val width = resolveStyle(styleName, styles).columnWidth
                    repeat(repeated.coerceAtMost(100)) { columns.add(OdfTableColumn(width = width)) }
                }
                "table-row" -> { rows.add(OdfTableRow(parseTableCells(parser, styles))); if (inHeader) headerRowCount++ }
                "table-header-rows" -> inHeader = true
            } else if (eventType == XmlPullParser.END_TAG && parser.name == "table-header-rows") {
                inHeader = false
            }
            eventType = parser.next()
        }
        return OdfTable(tableName, columns, rows, headerRowCount)
    }

    private fun parseTableCells(parser: XmlPullParser, styles: Map<String, StyleInfo>): List<OdfTableCell> {
        val cells = mutableListOf<OdfTableCell>()
        val depth = parser.depth
        var eventType = parser.next()

        while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (eventType == XmlPullParser.START_TAG) when (parser.name) {
                "table-cell" -> {
                    val colSpan = getAttr(parser, "number-columns-spanned")?.toIntOrNull() ?: 1
                    val rowSpan = getAttr(parser, "number-rows-spanned")?.toIntOrNull() ?: 1
                    val styleName = getAttr(parser, "style-name")
                    val resolved = resolveStyle(styleName, styles)
                    cells.add(OdfTableCell(
                        paragraphs = parseTableCellContent(parser, styles),
                        colSpan = colSpan, rowSpan = rowSpan,
                        backgroundColor = resolved.cellBackgroundColor,
                        borderColor = resolved.cellBorderColor,
                        formula = getAttr(parser, "formula"),
                        verticalAlign = resolved.cellVerticalAlign
                    ))
                }
                "covered-table-cell" -> {
                    val repeated = getAttr(parser, "number-columns-repeated")?.toIntOrNull() ?: 1
                    repeat(repeated.coerceAtMost(100)) { cells.add(OdfTableCell(isCovered = true)) }
                    skipElement(parser)
                }
            }
            eventType = parser.next()
        }
        return cells
    }

    private fun parseTableCellContent(parser: XmlPullParser, styles: Map<String, StyleInfo>): List<OdfParagraph> {
        val paragraphs = mutableListOf<OdfParagraph>()
        val depth = parser.depth
        var eventType = parser.next()
        while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "p") {
                val spans = parseInlineContent(parser, "p", styles)
                paragraphs.add(OdfParagraph(spans))
            }
            eventType = parser.next()
        }
        return paragraphs
    }

    // --- TOC ---

    private fun parseTocContent(parser: XmlPullParser, styles: Map<String, StyleInfo>, content: MutableList<OdfContentBlock>) {
        val depth = parser.depth
        var eventType = parser.next()
        var inIndexBody = false
        var inIndexTitle = false
        var title = "Table of Contents"
        val entries = mutableListOf<OdfParagraph>()

        while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
            when {
                eventType == XmlPullParser.START_TAG && parser.name == "index-body" -> inIndexBody = true
                eventType == XmlPullParser.END_TAG && parser.name == "index-body" -> inIndexBody = false
                eventType == XmlPullParser.START_TAG && parser.name == "index-title" -> inIndexTitle = true
                eventType == XmlPullParser.END_TAG && parser.name == "index-title" -> inIndexTitle = false
                eventType == XmlPullParser.START_TAG && parser.name == "p" && inIndexBody -> {
                    val spans = parseInlineContent(parser, "p", styles)
                    val text = spans.joinToString("") { it.text }.trim()
                    if (inIndexTitle) {
                        if (text.isNotEmpty()) title = text
                    } else if (spans.isNotEmpty()) {
                        entries.add(OdfParagraph(spans))
                    }
                }
            }
            eventType = parser.next()
        }
        content.add(OdfContentBlock.TableOfContents(title, entries))
    }

    /** Parses text:tracked-changes into change metadata + a map of deletion text by change-id. (Priority 6) */
    private fun parseTrackedChanges(parser: XmlPullParser): Pair<List<OdfChange>, Map<String, String>> {
        val list = mutableListOf<OdfChange>()
        val delText = HashMap<String, String>()
        val depth = parser.depth
        fun collectText(): String {
            val d = parser.depth; val sb = StringBuilder(); var ev = parser.next()
            while (!(ev == XmlPullParser.END_TAG && parser.depth == d)) {
                if (ev == XmlPullParser.TEXT) sb.append(parser.text)
                if (ev == XmlPullParser.END_DOCUMENT) break
                ev = parser.next()
            }
            return sb.toString()
        }
        var curId: String? = null
        var curType: String? = null
        var author: String? = null
        var date: String? = null
        val delBuf = StringBuilder()
        var inDeletion = false
        fun flush() {
            val id = curId ?: return
            val type = curType ?: "insertion"
            list.add(OdfChange(id, type, author, date))
            if (type == "deletion") delText[id] = delBuf.toString()
        }
        var ev = parser.next()
        while (!(ev == XmlPullParser.END_TAG && parser.depth == depth)) {
            when (ev) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "changed-region" -> {
                        curId = getAttr(parser, "id"); curType = null; author = null; date = null
                        delBuf.clear(); inDeletion = false
                    }
                    "insertion" -> curType = "insertion"
                    "deletion" -> { curType = "deletion"; inDeletion = true }
                    "format-change" -> curType = "format-change"
                    "creator" -> author = collectText().trim()
                    "date" -> date = collectText().trim()
                    "p" -> if (inDeletion) { if (delBuf.isNotEmpty()) delBuf.append("\n"); delBuf.append(collectText()) }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "changed-region" -> { flush(); curId = null }
                    "deletion" -> inDeletion = false
                }
            }
            ev = parser.next()
        }
        return list to delText
    }

    // --- Embedded charts ---

    private fun parseChart(xml: String): OdfChart? {
        val chartStyles = parseChartStyles(xml)
        val parser = newParser(xml)
        var eventType = parser.eventType
        var chartClass: String? = null
        var stacked = false
        val seriesStyleNames = mutableListOf<String?>()
        var inTable = false
        var inCell = false
        var curRow: MutableList<Pair<String, Float?>>? = null
        var cellText = StringBuilder()
        var cellValue: Float? = null
        val rows = mutableListOf<List<Pair<String, Float?>>>()
        // Chart styling round-trip: legend, title/subtitle, axis titles. (Priority 8)
        var legend = false
        var title: String? = null; var subtitle: String? = null
        var xAxisTitle: String? = null; var yAxisTitle: String? = null
        var axisDim: String? = null
        var captureTarget: String? = null
        val titleBuf = StringBuilder()

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "chart" -> if (chartClass == null) chartClass = getAttr(parser, "class")
                    "legend" -> legend = true
                    "plot-area" -> {
                        if (getAttr(parser, "stacked") == "true" || getAttr(parser, "percentage") == "true") stacked = true
                    }
                    "chart-properties" -> {
                        if (getAttr(parser, "stacked") == "true" || getAttr(parser, "percentage") == "true") stacked = true
                    }
                    "series" -> seriesStyleNames.add(getAttr(parser, "style-name"))
                    "axis" -> axisDim = getAttr(parser, "dimension")
                    "title" -> { captureTarget = when (axisDim) { "x" -> "x"; "y" -> "y"; else -> "main" }; titleBuf.setLength(0) }
                    "subtitle" -> { captureTarget = "sub"; titleBuf.setLength(0) }
                    "table" -> if (getAttr(parser, "name") == "local-table") inTable = true
                    "table-row" -> if (inTable) curRow = mutableListOf()
                    "table-cell" -> if (inTable && curRow != null) { inCell = true; cellText = StringBuilder(); cellValue = getAttr(parser, "value")?.toFloatOrNull() }
                }
                XmlPullParser.TEXT -> if (inCell) cellText.append(parser.text) else if (captureTarget != null) titleBuf.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "title" -> { val t = titleBuf.toString().trim().ifEmpty { null }; when (captureTarget) { "x" -> xAxisTitle = t; "y" -> yAxisTitle = t; "main" -> title = t }; captureTarget = null }
                    "subtitle" -> { subtitle = titleBuf.toString().trim().ifEmpty { null }; captureTarget = null }
                    "axis" -> axisDim = null
                    "table" -> if (inTable) inTable = false
                    "table-cell" -> if (inCell) { curRow?.add(cellText.toString().trim() to cellValue); inCell = false }
                    "table-row" -> if (curRow != null) { rows.add(curRow); curRow = null }
                }
            }
            eventType = parser.next()
        }
        if (rows.size < 2) return null
        val header = rows[0]
        val seriesCount = (header.size - 1).coerceAtLeast(0)
        if (seriesCount == 0) return null
        val seriesNames = (1..seriesCount).map { header.getOrNull(it)?.first?.ifEmpty { "Series $it" } ?: "Series $it" }
        val seriesValues = List(seriesCount) { mutableListOf<Float>() }
        val categories = mutableListOf<String>()
        for (i in 1 until rows.size) {
            val r = rows[i]
            categories.add(r.getOrNull(0)?.first ?: "")
            for (j in 0 until seriesCount) {
                val cell = r.getOrNull(j + 1)
                seriesValues[j].add(cell?.second ?: cell?.first?.toFloatOrNull() ?: 0f)
            }
        }
        val type = when {
            chartClass?.contains("line") == true -> ChartType.LINE
            chartClass?.contains("scatter") == true -> ChartType.SCATTER
            chartClass?.contains("ring") == true -> ChartType.DONUT
            chartClass?.contains("circle") == true -> ChartType.PIE
            chartClass?.contains("radar") == true -> ChartType.RADAR
            chartClass?.contains("bubble") == true -> ChartType.BUBBLE
            chartClass?.contains("area") == true -> ChartType.AREA
            stacked && chartClass?.contains("bar") == true -> ChartType.STACKED_BAR
            else -> ChartType.BAR
        }
        val series = seriesNames.mapIndexed { idx, n ->
            val styleAttrs = seriesStyleNames.getOrNull(idx)?.let { chartStyles[it] }
            OdfChartSeries(n, seriesValues[idx], color = styleAttrs?.first, dataLabels = styleAttrs?.second ?: false)
        }
        return if (categories.isEmpty()) null else OdfChart(type, categories, series, title, subtitle, legend, xAxisTitle, yAxisTitle, stacked = stacked)
    }

    /** Parses chart automatic styles: chart style-name -> (fill color, data-labels enabled). (Phase 5) */
    private fun parseChartStyles(xml: String): Map<String, Pair<Long?, Boolean>> {
        val map = mutableMapOf<String, Pair<Long?, Boolean>>()
        val parser = newParser(xml)
        var e = parser.eventType
        var curName: String? = null
        var fill: Long? = null
        var dataLabels = false
        val depthStack = ArrayDeque<Int>()
        while (e != XmlPullParser.END_DOCUMENT) {
            when (e) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "style" -> if (getAttr(parser, "family") == "chart" || getAttr(parser, "family") == null) {
                        getAttr(parser, "name")?.let { curName = it; fill = null; dataLabels = false; depthStack.addLast(parser.depth) }
                    }
                    "graphic-properties" -> if (curName != null) getAttr(parser, "fill-color")?.let { fill = parseColor(it) }
                    "chart-properties" -> if (curName != null) {
                        val dln = getAttr(parser, "data-label-number")
                        if ((dln != null && dln != "none") || getAttr(parser, "data-label-text") == "true") dataLabels = true
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "style" && curName != null && depthStack.isNotEmpty() && parser.depth == depthStack.last()) {
                    depthStack.removeLast()
                    map[curName] = fill to dataLabels
                    curName = null
                }
            }
            e = parser.next()
        }
        return map
    }

    private fun parseFormulaText(xml: String): String? {
        if (!xml.contains("math")) return null
        val ann = Regex("<annotation[^>]*>(.*?)</annotation>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)
        if (!ann.isNullOrBlank()) return unescapeXml(ann.trim())
        var toks = Regex("<m:(mi|mn|mo)[^>]*>(.*?)</m:(mi|mn|mo)>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { it.groupValues[2] }.toList()
        if (toks.isEmpty()) toks = Regex("<(mi|mn|mo)[^>]*>(.*?)</(mi|mn|mo)>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { it.groupValues[2] }.toList()
        val joined = toks.joinToString(" ").trim()
        return if (joined.isBlank()) null else unescapeXml(joined)
    }

    private fun unescapeXml(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")

    // --- Spreadsheet ---

    private fun parseSpreadsheet(
        xml: String, styles: Map<String, StyleInfo>, numberStyles: Map<String, OdfNumberFormat>,
        title: String, metadata: OdfMetadata, images: Map<String, ByteArray>, objectContents: Map<String, String> = emptyMap(),
        freezeMap: Map<String, Pair<Int, Int>> = emptyMap()
    ): OdfDocument.Spreadsheet {
        val sheets = mutableListOf<OdfSheet>()
        val namedRanges = mutableListOf<OdfNamedRange>()
        val validations = mutableListOf<OdfDataValidation>()
        val parser = newParser(xml)
        var eventType = parser.eventType

        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "table" && parser.namespace?.contains("table") == true) {
                val name = getAttr(parser, "name") ?: "Sheet ${sheets.size + 1}"
                val printRanges = getAttr(parser, "print-ranges")
                val r = parseSheetContent(parser, styles, numberStyles, images, objectContents)
                val freeze = freezeMap[name]
                sheets.add(OdfSheet(name, r.rows, r.columnWidths, r.floating, freeze?.first ?: 0, freeze?.second ?: 0,
                    rowHeights = r.rowHeights, hiddenRows = r.hiddenRows, hiddenCols = r.hiddenCols, printRanges = printRanges))
            } else if (eventType == XmlPullParser.START_TAG && parser.name == "named-range") {
                val nm = getAttr(parser, "name")
                val addr = getAttr(parser, "cell-range-address")
                if (nm != null && addr != null) namedRanges.add(OdfNamedRange(nm, addr, getAttr(parser, "base-cell-address")))
            } else if (eventType == XmlPullParser.START_TAG && parser.name == "content-validation") {
                val nm = getAttr(parser, "name")
                val cond = getAttr(parser, "condition")
                if (nm != null && cond != null) validations.add(OdfDataValidation(nm, cond, getAttr(parser, "allow-empty-cell") != "false"))
            }
            eventType = parser.next()
        }
        return OdfDocument.Spreadsheet(title, sheets, metadata, images, namedRanges, validations)
    }

    private data class SheetParse(
        val rows: List<OdfRow>, val columnWidths: List<Float?>, val floating: List<OdfSlideElement>,
        val rowHeights: List<Float?>, val hiddenRows: Set<Int>, val hiddenCols: Set<Int>
    )

    private fun parseSheetContent(parser: XmlPullParser, styles: Map<String, StyleInfo>, numberStyles: Map<String, OdfNumberFormat>, images: Map<String, ByteArray>, objectContents: Map<String, String> = emptyMap()): SheetParse {
        val rows = mutableListOf<OdfRow>()
        val columnWidths = mutableListOf<Float?>()
        val floating = mutableListOf<OdfSlideElement>()
        val rowHeights = mutableListOf<Float?>()
        val hiddenRows = mutableSetOf<Int>()
        val hiddenCols = mutableSetOf<Int>()
        var colIndex = 0
        val depth = parser.depth
        var eventType = parser.next()

        while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (eventType == XmlPullParser.START_TAG) {
                val isDraw = parser.namespace?.contains("draw") == true
                when (parser.name) {
                    "table-column" -> {
                        val repeated = getAttr(parser, "number-columns-repeated")?.toIntOrNull() ?: 1
                        val styleName = getAttr(parser, "style-name")
                        val width = resolveStyle(styleName, styles).columnWidth
                        val hidden = getAttr(parser, "visibility").let { it == "collapse" || it == "filter" }
                        repeat(repeated.coerceAtMost(200)) {
                            if (hidden) hiddenCols.add(colIndex)
                            columnWidths.add(width); colIndex++
                        }
                    }
                    "table-row" -> {
                        val repeated = getAttr(parser, "number-rows-repeated")?.toIntOrNull() ?: 1
                        val styleName = getAttr(parser, "style-name")
                        val rh = resolveStyle(styleName, styles).rowHeight
                        val hidden = getAttr(parser, "visibility").let { it == "collapse" || it == "filter" }
                        val cells = parseSpreadsheetCells(parser, styles, numberStyles)
                        repeat(repeated.coerceAtMost(1000)) {
                            if (hidden) hiddenRows.add(rows.size)
                            rows.add(OdfRow(cells)); rowHeights.add(rh)
                        }
                    }
                    "frame" -> if (isDraw) floating.add(OdfSlideElement.Frame(parseSingleFrame(parser, styles, images, objectContents)))
                    "rect" -> if (isDraw) floating.add(OdfSlideElement.Shape(parseShape(parser, styles, "rect")))
                    "ellipse" -> if (isDraw) floating.add(OdfSlideElement.Shape(parseShape(parser, styles, "ellipse")))
                    "line" -> if (isDraw) floating.add(OdfSlideElement.Shape(parseShape(parser, styles, "line")))
                    "custom-shape" -> if (isDraw) floating.add(OdfSlideElement.Shape(parseShape(parser, styles, "custom-shape")))
                    "polyline" -> if (isDraw) floating.add(OdfSlideElement.Shape(parseShape(parser, styles, "polyline")))
                    "polygon" -> if (isDraw) floating.add(OdfSlideElement.Shape(parseShape(parser, styles, "polygon")))
                    "path" -> if (isDraw) floating.add(OdfSlideElement.Shape(parsePathShape(parser, styles)))
                }
            }
            eventType = parser.next()
        }
        // Trim trailing all-empty rows.
        while (rows.isNotEmpty() && rows.last().cells.all { it.text.isEmpty() && it.formula == null }) {
            rows.removeAt(rows.size - 1)
            if (rowHeights.isNotEmpty()) rowHeights.removeAt(rowHeights.size - 1)
        }
        return SheetParse(rows, columnWidths, floating, rowHeights, hiddenRows.filter { it < rows.size }.toSet(), hiddenCols)
    }

    private fun parseSpreadsheetCells(parser: XmlPullParser, styles: Map<String, StyleInfo>, numberStyles: Map<String, OdfNumberFormat>): List<OdfCell> {
        val cells = mutableListOf<OdfCell>()
        val depth = parser.depth
        var eventType = parser.next()

        while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (eventType == XmlPullParser.START_TAG) when (parser.name) {
                "table-cell" -> {
                    val spanned = getAttr(parser, "number-columns-spanned")?.toIntOrNull() ?: 1
                    val rowSpan = getAttr(parser, "number-rows-spanned")?.toIntOrNull() ?: 1
                    val repeated = getAttr(parser, "number-columns-repeated")?.toIntOrNull() ?: 1
                    val styleName = getAttr(parser, "style-name")
                    val resolved = resolveStyle(styleName, styles)
                    val formula = getAttr(parser, "formula")
                    val valueType = getAttr(parser, "value-type")
                    val numberValue = getAttr(parser, "value")?.toDoubleOrNull()
                    val numberFormat = resolved.dataStyleName?.let { numberStyles[it] }
                    val validationName = getAttr(parser, "content-validation-name")
                    val condFormats = resolved.conditionalMaps.map { (cond, sn) ->
                        val t = resolveStyle(sn, styles); OdfCondFormat(cond, t.cellBackgroundColor, t.color)
                    }
                    val cellContent = parseCellContent(parser, styles)
                    repeat(repeated.coerceAtMost(100)) {
                        cells.add(OdfCell(
                            text = cellContent.first, spannedColumns = spanned, rowSpan = rowSpan,
                            backgroundColor = resolved.cellBackgroundColor, textColor = resolved.color,
                            bold = resolved.bold, italic = resolved.italic, alignment = resolved.textAlign,
                            borderColor = resolved.cellBorderColor,
                            borders = resolved.cellBorders,
                            formula = formula, valueType = valueType, numberValue = numberValue,
                            numberFormat = numberFormat, wrap = resolved.cellWrap,
                            annotation = cellContent.second, validationName = validationName,
                            condFormats = condFormats
                        ))
                    }
                }
                "covered-table-cell" -> {
                    val repeated = getAttr(parser, "number-columns-repeated")?.toIntOrNull() ?: 1
                    repeat(repeated.coerceAtMost(100)) { cells.add(OdfCell(text = "", isCovered = true)) }
                    skipElement(parser)
                }
            }
            eventType = parser.next()
        }
        return cells
    }

    /** Parses a spreadsheet cell's text plus an optional office:annotation (cell comment). (Round 3) */
    private fun parseCellContent(parser: XmlPullParser, styles: Map<String, StyleInfo>): Pair<String, OdfAnnotation?> {
        val sb = StringBuilder()
        var annotation: OdfAnnotation? = null
        val depth = parser.depth
        var eventType = parser.next()
        while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "annotation") {
                annotation = parseAnnotation(parser, styles)
            } else if (eventType == XmlPullParser.TEXT) {
                sb.append(parser.text)
            }
            eventType = parser.next()
        }
        return sb.toString().trim() to annotation
    }

    // --- Presentation / Drawing ---

    private fun parsePresentation(
        xml: String, styles: Map<String, StyleInfo>,
        title: String, metadata: OdfMetadata, images: Map<String, ByteArray>,
        objectContents: Map<String, String> = emptyMap()
    ): OdfDocument.Presentation =
        OdfDocument.Presentation(title, parseSlides(xml, styles, images, objectContents), metadata, images)

    private fun parseDrawing(
        xml: String, styles: Map<String, StyleInfo>,
        title: String, metadata: OdfMetadata, images: Map<String, ByteArray>,
        objectContents: Map<String, String> = emptyMap()
    ): OdfDocument.Drawing =
        OdfDocument.Drawing(title, parseSlides(xml, styles, images, objectContents), metadata, images)

    private fun parseSlides(xml: String, styles: Map<String, StyleInfo>, images: Map<String, ByteArray>, objectContents: Map<String, String> = emptyMap()): List<OdfSlide> {
        val slides = mutableListOf<OdfSlide>()
        val parser = newParser(xml)
        var eventType = parser.eventType

        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "page" && parser.namespace?.contains("draw") == true) {
                val name = getAttr(parser, "name") ?: "Slide ${slides.size + 1}"
                val drawStyleName = getAttr(parser, "style-name")
                val masterName = getAttr(parser, "master-page-name")
                val resolved = resolveStyle(drawStyleName, styles)
                val result = parseSlideContent(parser, styles, images, objectContents)
                slides.add(OdfSlide(
                    name = name, elements = result.elements,
                    backgroundColor = resolved.drawFillColor, notes = result.notes,
                    transitionType = resolved.transitionType, transitionSpeed = resolved.transitionSpeed,
                    masterName = masterName
                ))
            }
            eventType = parser.next()
        }
        return slides
    }

    private data class SlideParseResult(val elements: List<OdfSlideElement>, val notes: List<OdfParagraph>)

    private fun parseSlideContent(parser: XmlPullParser, styles: Map<String, StyleInfo>, images: Map<String, ByteArray>, objectContents: Map<String, String> = emptyMap()): SlideParseResult {
        val elements = mutableListOf<OdfSlideElement>()
        val notes = mutableListOf<OdfParagraph>()
        val depth = parser.depth
        var eventType = parser.next()

        while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (eventType == XmlPullParser.START_TAG) when (parser.name) {
                "frame" -> elements.add(OdfSlideElement.Frame(parseSingleFrame(parser, styles, images, objectContents)))
                "rect" -> elements.add(OdfSlideElement.Shape(parseShape(parser, styles, "rect")))
                "ellipse" -> elements.add(OdfSlideElement.Shape(parseShape(parser, styles, "ellipse")))
                "line" -> elements.add(OdfSlideElement.Shape(parseShape(parser, styles, "line")))
                "custom-shape" -> elements.add(OdfSlideElement.Shape(parseShape(parser, styles, "custom-shape")))
                "polyline" -> elements.add(OdfSlideElement.Shape(parseShape(parser, styles, "polyline")))
                "polygon" -> elements.add(OdfSlideElement.Shape(parseShape(parser, styles, "polygon")))
                "path" -> elements.add(OdfSlideElement.Shape(parsePathShape(parser, styles)))
                "notes" -> {
                    val noteDepth = parser.depth
                    var noteEvent = parser.next()
                    while (!(noteEvent == XmlPullParser.END_TAG && parser.depth == noteDepth)) {
                        if (noteEvent == XmlPullParser.START_TAG && parser.name == "frame") {
                            val frame = parseSingleFrame(parser, styles, images, objectContents)
                            for (para in frame.paragraphs) {
                                if (para.spans.isNotEmpty()) notes.add(para)
                            }
                        }
                        noteEvent = parser.next()
                    }
                }
            }
            eventType = parser.next()
        }
        return SlideParseResult(elements, notes)
    }

    private fun parseSingleFrame(parser: XmlPullParser, styles: Map<String, StyleInfo>, images: Map<String, ByteArray>, objectContents: Map<String, String> = emptyMap()): OdfFrame {
        val x = parseDimension(getAttr(parser, "x"))
        val y = parseDimension(getAttr(parser, "y"))
        val w = parseDimension(getAttr(parser, "width"))
        val h = parseDimension(getAttr(parser, "height"))
        val rot = parseRotationDegrees(getAttr(parser, "transform"))
        val clip = parseClip(getAttr(parser, "clip"))
        val anchor = getAttr(parser, "anchor-type") ?: ""
        val styleName = getAttr(parser, "style-name")
        val resolved = resolveStyle(styleName, styles)

        val paragraphs = mutableListOf<OdfParagraph>()
        var image: OdfImage? = null
        var chart: OdfChart? = null
        var altTitle: String? = null
        var altDesc: String? = null
        val depth = parser.depth
        var eventType = parser.next()

        while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (eventType == XmlPullParser.START_TAG) when (parser.name) {
                "title", "desc" -> if (parser.namespace?.contains("svg") == true) {
                    val d = parser.depth; val sb = StringBuilder(); var ev = parser.next()
                    while (!(ev == XmlPullParser.END_TAG && parser.depth == d)) {
                        if (ev == XmlPullParser.TEXT) sb.append(parser.text)
                        if (ev == XmlPullParser.END_DOCUMENT) break
                        ev = parser.next()
                    }
                    val t = sb.toString().trim().ifEmpty { null }
                    if (parser.name == "title") altTitle = t else altDesc = t
                }
                "text-box" -> {
                    val boxDepth = parser.depth
                    var boxEvent = parser.next()
                    while (!(boxEvent == XmlPullParser.END_TAG && parser.depth == boxDepth)) {
                        if (boxEvent == XmlPullParser.START_TAG && parser.name == "p" && parser.namespace?.contains("text") == true) {
                            val spans = parseInlineContent(parser, "p", styles, images)
                            if (spans.isNotEmpty()) paragraphs.add(OdfParagraph(spans))
                        } else if (boxEvent == XmlPullParser.START_TAG && parser.name == "list") {
                            parseListInFrame(parser, styles, images, paragraphs)
                        }
                        boxEvent = parser.next()
                    }
                }
                "object" -> {
                    // Embedded chart object referenced by ./Object N. (A8)
                    val href = getAttr(parser, "href")?.removePrefix("./")
                    val xml = href?.let { objectContents["$it/content.xml"] }
                    if (xml != null) parseChart(xml)?.let { chart = it }
                }
                "image" -> {
                    val href = getAttr(parser, "href")
                    if (href != null && images.containsKey(href)) {
                        val bytes = images[href]!!
                        val (nw, nh) = decodeNaturalSize(bytes)
                        image = OdfImage(path = href, imageData = bytes, width = w, height = h, anchorType = anchor, rotationDegrees = rot, naturalWidthPx = nw, naturalHeightPx = nh, opacityPercent = resolved.imageOpacity ?: 100f, colorMode = resolved.imageColorMode)
                    }
                    val imgDepth = parser.depth
                    var imgEvent = parser.next()
                    while (!(imgEvent == XmlPullParser.END_TAG && parser.depth == imgDepth)) {
                        if (imgEvent == XmlPullParser.START_TAG && parser.name == "binary-data") {
                            imgEvent = parser.next()
                            if (imgEvent == XmlPullParser.TEXT) {
                                try {
                                    val bytes = Base64.decode(parser.text.trim(), Base64.DEFAULT)
                                    val (nw, nh) = decodeNaturalSize(bytes)
                                    image = OdfImage(path = "inline", imageData = bytes, width = w, height = h, anchorType = anchor, rotationDegrees = rot, naturalWidthPx = nw, naturalHeightPx = nh, opacityPercent = resolved.imageOpacity ?: 100f, colorMode = resolved.imageColorMode)
                                } catch (_: Exception) { }
                            }
                        }
                        imgEvent = parser.next()
                    }
                }
                "p" -> if (parser.namespace?.contains("text") == true) {
                    val spans = parseInlineContent(parser, "p", styles, images)
                    if (spans.isNotEmpty()) paragraphs.add(OdfParagraph(spans))
                }
            }
            eventType = parser.next()
        }
        if (image != null) {
            // Prefer the legacy inline percent clip; otherwise resolve absolute lengths from the graphic style. (A7)
            val frac = clip ?: parseClipLengths(resolved.clip, image.naturalWidthPx, image.naturalHeightPx)
            if (frac != null) image = image.copy(cropLeftPct = frac[0], cropTopPct = frac[1], cropRightPct = frac[2], cropBottomPct = frac[3])
            if (altTitle != null || altDesc != null) image = image.copy(altTitle = altTitle, altDesc = altDesc)
        }
        return OdfFrame(x, y, w, h, paragraphs, image, chart = chart, fillColor = resolved.drawFillColor, strokeColor = resolved.drawStrokeColor, strokeWidth = resolved.drawStrokeWidth, fillGradient = resolved.fillGradientName?.let { gradientDefs[it] })
    }

    private fun parseListInFrame(parser: XmlPullParser, styles: Map<String, StyleInfo>, images: Map<String, ByteArray>, paragraphs: MutableList<OdfParagraph>) {
        val depth = parser.depth
        var eventType = parser.next()
        while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "p" && parser.namespace?.contains("text") == true) {
                val spans = parseInlineContent(parser, "p", styles, images)
                if (spans.isNotEmpty()) paragraphs.add(OdfParagraph(spans, ParagraphStyle.LIST_ITEM, listLevel = 1))
            } else if (eventType == XmlPullParser.START_TAG && parser.name == "list") {
                parseListInFrame(parser, styles, images, paragraphs)
            }
            eventType = parser.next()
        }
    }

    // --- Shapes ---

    private fun parseShape(parser: XmlPullParser, styles: Map<String, StyleInfo>, shapeName: String): OdfShape {
        val x = parseDimension(getAttr(parser, "x"))
        val y = parseDimension(getAttr(parser, "y"))
        val w = parseDimension(getAttr(parser, "width"))
        val h = parseDimension(getAttr(parser, "height"))
        val x2 = if (shapeName == "line") parseDimension(getAttr(parser, "x2")) else 0f
        val y2 = if (shapeName == "line") parseDimension(getAttr(parser, "y2")) else 0f
        val polyPoints = if (shapeName == "polyline" || shapeName == "polygon") {
            val vb = getAttr(parser, "viewBox")?.trim()?.split(Regex("\\s+"))?.mapNotNull { it.toFloatOrNull() }
            parsePolyPoints(getAttr(parser, "points"), vb, x, y, w, h)
        } else emptyList()
        val styleName = getAttr(parser, "style-name")
        val resolved = resolveStyle(styleName, styles)
        val rot = parseRotationDegrees(getAttr(parser, "transform"))
        val grad = resolved.fillGradientName?.let { gradientDefs[it] }
        val cornerRadius = if (shapeName == "rect") parseDimension(getAttr(parser, "corner-radius")) else 0f

        val text = mutableListOf<OdfParagraph>()
        val depth = parser.depth
        var eventType = parser.next()
        while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "p" && parser.namespace?.contains("text") == true) {
                val spans = parseInlineContent(parser, "p", styles)
                if (spans.isNotEmpty()) text.add(OdfParagraph(spans))
            }
            eventType = parser.next()
        }

        return when (shapeName) {
            "rect" -> OdfShape.Rect(x, y, w, h, resolved.drawFillColor, resolved.drawStrokeColor, resolved.drawStrokeWidth, text, cornerRadius = cornerRadius, rotationDegrees = rot, fillGradient = grad, strokeDashed = resolved.strokeDashed)
            "ellipse" -> OdfShape.Ellipse(x, y, w, h, resolved.drawFillColor, resolved.drawStrokeColor, resolved.drawStrokeWidth, text, rotationDegrees = rot, fillGradient = grad, strokeDashed = resolved.strokeDashed)
            "line" -> OdfShape.Line(x, y, w, h, resolved.drawFillColor, resolved.drawStrokeColor, resolved.drawStrokeWidth, text, x2, y2, rotationDegrees = rot, strokeDashed = resolved.strokeDashed, markerStart = resolved.markerStart, markerEnd = resolved.markerEnd)
            "polyline" -> OdfShape.Polyline(x, y, w, h, resolved.drawFillColor, resolved.drawStrokeColor, resolved.drawStrokeWidth, text, polyPoints, closed = false, rotationDegrees = rot, fillGradient = grad, strokeDashed = resolved.strokeDashed)
            "polygon" -> OdfShape.Polyline(x, y, w, h, resolved.drawFillColor, resolved.drawStrokeColor, resolved.drawStrokeWidth, text, polyPoints, closed = true, rotationDegrees = rot, fillGradient = grad, strokeDashed = resolved.strokeDashed)
            else -> OdfShape.CustomShape(x, y, w, h, resolved.drawFillColor, resolved.drawStrokeColor, resolved.drawStrokeWidth, text, rotationDegrees = rot, fillGradient = grad, strokeDashed = resolved.strokeDashed)
        }
    }

    /** Parses a draw:path freeform shape, sampling svg:d into an OdfShape.Polyline. (Phase 2) */
    private fun parsePathShape(parser: XmlPullParser, styles: Map<String, StyleInfo>): OdfShape {
        val x = parseDimension(getAttr(parser, "x"))
        val y = parseDimension(getAttr(parser, "y"))
        val w = parseDimension(getAttr(parser, "width"))
        val h = parseDimension(getAttr(parser, "height"))
        val vb = getAttr(parser, "viewBox")?.trim()?.split(Regex("\\s+"))?.mapNotNull { it.toFloatOrNull() }
        val d = getAttr(parser, "d")
        val styleName = getAttr(parser, "style-name")
        val resolved = resolveStyle(styleName, styles)
        val rot = parseRotationDegrees(getAttr(parser, "transform"))
        val grad = resolved.fillGradientName?.let { gradientDefs[it] }
        val (points, closed) = sampleSvgPath(d, vb, x, y, w, h)

        val text = mutableListOf<OdfParagraph>()
        val depth = parser.depth
        var eventType = parser.next()
        while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "p" && parser.namespace?.contains("text") == true) {
                val spans = parseInlineContent(parser, "p", styles)
                if (spans.isNotEmpty()) text.add(OdfParagraph(spans))
            }
            eventType = parser.next()
        }
        return OdfShape.Polyline(x, y, w, h, resolved.drawFillColor, resolved.drawStrokeColor, resolved.drawStrokeWidth, text, points, closed = closed, rotationDegrees = rot, fillGradient = grad, strokeDashed = resolved.strokeDashed)
    }

    /** Flattens an SVG path 'd' (viewBox space) into absolute px@96 vertices; returns (points, closed). (Phase 2) */
    private fun sampleSvgPath(d: String?, vb: List<Float>?, x: Float, y: Float, w: Float, h: Float): Pair<List<Pair<Float, Float>>, Boolean> {
        if (d.isNullOrBlank()) return emptyList<Pair<Float, Float>>() to false
        val vbMinX = vb?.getOrNull(0) ?: 0f; val vbMinY = vb?.getOrNull(1) ?: 0f
        val vbW = vb?.getOrNull(2)?.takeIf { it != 0f } ?: 1f; val vbH = vb?.getOrNull(3)?.takeIf { it != 0f } ?: 1f
        fun map(px: Float, py: Float) = (x + (px - vbMinX) / vbW * w) to (y + (py - vbMinY) / vbH * h)
        // Tokenize commands + numbers.
        val tokens = Regex("[MmLlHhVvCcSsQqTtAaZz]|-?\\d*\\.?\\d+(?:[eE][-+]?\\d+)?").findAll(d).map { it.value }.toList()
        val raw = ArrayList<Pair<Float, Float>>()
        var closed = false
        var i = 0
        var cx = 0f; var cy = 0f; var startX = 0f; var startY = 0f
        var cmd = ' '
        fun num(): Float { return (tokens.getOrNull(i++)?.toFloatOrNull() ?: 0f) }
        fun cubic(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            val steps = 8
            for (s in 1..steps) {
                val t = s / steps.toFloat(); val u = 1 - t
                val px = u * u * u * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x3
                val py = u * u * u * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y3
                raw.add(px to py)
            }
        }
        fun quad(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float) {
            val steps = 8
            for (s in 1..steps) {
                val t = s / steps.toFloat(); val u = 1 - t
                val px = u * u * x0 + 2 * u * t * x1 + t * t * x2
                val py = u * u * y0 + 2 * u * t * y1 + t * t * y2
                raw.add(px to py)
            }
        }
        while (i < tokens.size) {
            val tk = tokens[i]
            if (tk.length == 1 && tk[0].isLetter()) { cmd = tk[0]; i++ }
            val rel = cmd.isLowerCase()
            when (cmd.uppercaseChar()) {
                'M' -> { var nx = num(); var ny = num(); if (rel) { nx += cx; ny += cy }; cx = nx; cy = ny; startX = cx; startY = cy; raw.add(cx to cy); cmd = if (rel) 'l' else 'L' }
                'L' -> { var nx = num(); var ny = num(); if (rel) { nx += cx; ny += cy }; cx = nx; cy = ny; raw.add(cx to cy) }
                'H' -> { var nx = num(); if (rel) nx += cx; cx = nx; raw.add(cx to cy) }
                'V' -> { var ny = num(); if (rel) ny += cy; cy = ny; raw.add(cx to cy) }
                'C' -> { var x1 = num(); var y1 = num(); var x2 = num(); var y2 = num(); var nx = num(); var ny = num(); if (rel) { x1 += cx; y1 += cy; x2 += cx; y2 += cy; nx += cx; ny += cy }; cubic(cx, cy, x1, y1, x2, y2, nx, ny); cx = nx; cy = ny }
                'S' -> { var x2 = num(); var y2 = num(); var nx = num(); var ny = num(); if (rel) { x2 += cx; y2 += cy; nx += cx; ny += cy }; cubic(cx, cy, cx, cy, x2, y2, nx, ny); cx = nx; cy = ny }
                'Q' -> { var x1 = num(); var y1 = num(); var nx = num(); var ny = num(); if (rel) { x1 += cx; y1 += cy; nx += cx; ny += cy }; quad(cx, cy, x1, y1, nx, ny); cx = nx; cy = ny }
                'T' -> { var nx = num(); var ny = num(); if (rel) { nx += cx; ny += cy }; raw.add(nx to ny); cx = nx; cy = ny }
                'A' -> { num(); num(); num(); num(); num(); var nx = num(); var ny = num(); if (rel) { nx += cx; ny += cy }; raw.add(nx to ny); cx = nx; cy = ny }
                'Z' -> { closed = true; cx = startX; cy = startY; raw.add(startX to startY) }
                else -> i++
            }
        }
        return raw.map { map(it.first, it.second) } to closed
    }

    /** Maps a draw:points string (viewBox space) into absolute px@96 vertices. (Priority 8) */
    private fun parsePolyPoints(raw: String?, vb: List<Float>?, x: Float, y: Float, w: Float, h: Float): List<Pair<Float, Float>> {
        if (raw.isNullOrBlank()) return emptyList()
        val vbMinX = vb?.getOrNull(0) ?: 0f; val vbMinY = vb?.getOrNull(1) ?: 0f
        val vbW = vb?.getOrNull(2)?.takeIf { it != 0f } ?: 1f; val vbH = vb?.getOrNull(3)?.takeIf { it != 0f } ?: 1f
        return raw.trim().split(Regex("\\s+")).mapNotNull { pair ->
            val xy = pair.split(","); if (xy.size != 2) return@mapNotNull null
            val vx = xy[0].toFloatOrNull() ?: return@mapNotNull null
            val vy = xy[1].toFloatOrNull() ?: return@mapNotNull null
            (x + (vx - vbMinX) / vbW * w) to (y + (vy - vbMinY) / vbH * h)
        }
    }

    // --- CSV parsing ---

    fun parseCsv(text: String, fileName: String, delimiter: Char = ','): OdfDocument.Spreadsheet {
        val rows = mutableListOf<OdfRow>()
        val lines = text.lines()
        for (line in lines) {
            if (line.isBlank()) continue
            val cells = parseCsvLine(line, delimiter).map { OdfCell(text = it) }
            rows.add(OdfRow(cells))
        }
        return OdfDocument.Spreadsheet(fileName, listOf(OdfSheet("Sheet 1", rows)))
    }

    private fun parseCsvLine(line: String, delimiter: Char = ','): List<String> {
        val fields = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && !inQuotes -> inQuotes = true
                c == '"' && inQuotes -> {
                    if (i + 1 < line.length && line[i + 1] == '"') { sb.append('"'); i++ }
                    else inQuotes = false
                }
                c == delimiter && !inQuotes -> { fields.add(sb.toString()); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        fields.add(sb.toString())
        return fields
    }

    private const val LINK_COLOR = 0xFF0066CCL
    // Deletion-region text keyed by change-id, populated while parsing text:tracked-changes
    // and consumed by the inline parser to render struck-through deleted text. (Priority 6)
    private var trackedDeletionText: Map<String, String> = emptyMap()
    /** Gradient definitions (draw:gradient name -> def), set during parse(). (Round 3) */
    private var gradientDefs: Map<String, OdfGradient> = emptyMap()

    /** Parses draw:gradient definitions from a styles/content XML. (Round 3) */
    private fun parseGradients(xml: String): Map<String, OdfGradient> {
        val map = mutableMapOf<String, OdfGradient>()
        val parser = newParser(xml)
        var e = parser.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG && parser.name == "gradient") {
                val nm = getAttr(parser, "name")
                val start = getAttr(parser, "start-color")?.let { parseColor(it) }
                val end = getAttr(parser, "end-color")?.let { parseColor(it) }
                if (nm != null && start != null && end != null) {
                    val angle = getAttr(parser, "angle")?.let { a ->
                        // ODF gradient angle is in 1/10 degree, or with "deg" suffix in newer files.
                        a.removeSuffix("deg").toFloatOrNull()?.let { if (a.endsWith("deg")) it else it / 10f }
                    } ?: 0f
                    map[nm] = OdfGradient(start, end, angle, getAttr(parser, "style") ?: "linear")
                }
            }
            e = parser.next()
        }
        return map
    }
    // ODF inline text-field element local names recognized by the inline parser. (Priority 2)
    private val FIELD_TAGS = setOf(
        "date", "time", "page-number", "page-count", "file-name", "author-name",
        "author-initials", "title", "subject", "description", "chapter", "sheet-name",
        "creation-date", "modification-date", "editing-cycles",
        // Additional field elements (Phase 2).
        "variable-set", "variable-get", "user-field-get", "sequence", "placeholder",
        "conditional-text", "hidden-text", "page-continuation", "bibliography-mark"
    )
}
