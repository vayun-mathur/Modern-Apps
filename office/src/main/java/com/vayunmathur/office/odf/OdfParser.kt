package com.vayunmathur.office.odf

import android.content.Context
import android.net.Uri
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfGradient
import com.vayunmathur.library.ui.odf.OdfMetadata
import com.vayunmathur.library.ui.odf.OdfNumberFormat
import java.util.zip.ZipInputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/** Singleton holding odf parser state and operations. */
object OdfParser {

    fun parse(context: Context, uri: Uri, fileName: String): OdfDocument {
        val entries = extractAllEntries(context, uri)
        checkNotEncrypted(entries)
        val contentXml = entries.textEntries["content.xml"]
            ?: throw IllegalArgumentException("Not a valid ODF file: missing content.xml")
        val stylesXml = entries.textEntries["styles.xml"]
        val bundle = buildParseBundle(entries, stylesXml, contentXml, context, uri)

        val type = detectType(contentXml)
        return when (type) {
            DocType.TEXT -> parseTextDoc(contentXml, bundle, fileName)
            DocType.SPREADSHEET -> parseSheetDoc(contentXml, bundle, fileName)
            DocType.PRESENTATION -> parsePresentationDoc(contentXml, bundle, fileName)
            DocType.DRAWING -> parseDrawingDoc(contentXml, bundle, fileName)
        }
    }

    /** Text document from the bundle. */
    private fun OdfParser.parseTextDoc(
        contentXml: String,
        bundle: ParseBundle,
        fileName: String,
    ): OdfDocument.TextDocument {
        return parseTextDocument(
            contentXml,
            bundle.styleMap,
            bundle.listStyleMap,
            fileName,
            bundle.metadata,
            bundle.images,
            bundle.headerFooter,
            bundle.objectContents,
            bundle.pageSetup)
    }

    /** Spreadsheet from the bundle. */
    private fun OdfParser.parseSheetDoc(
        contentXml: String,
        bundle: ParseBundle,
        fileName: String,
    ): OdfDocument.Spreadsheet {
        return parseSpreadsheet(
            contentXml,
            bundle.styleMap,
            bundle.numberStyleMap,
            fileName,
            bundle.metadata,
            bundle.images,
            bundle.objectContents,
            bundle.freezeMap)
    }

    /** Presentation from the bundle. */
    private fun OdfParser.parsePresentationDoc(
        contentXml: String,
        bundle: ParseBundle,
        fileName: String,
    ): OdfDocument.Presentation {
        return parsePresentation(
            contentXml,
            bundle.styleMap,
            fileName,
            bundle.metadata,
            bundle.images,
            bundle.objectContents)
    }

    /** Drawing from the bundle. */
    private fun OdfParser.parseDrawingDoc(
        contentXml: String,
        bundle: ParseBundle,
        fileName: String,
    ): OdfDocument.Drawing {
        return parseDrawing(
            contentXml,
            bundle.styleMap,
            fileName,
            bundle.metadata,
            bundle.images,
            bundle.objectContents)
    }

    /** Everything parse() needs besides contentXml. */
    private class ParseBundle(
        val styleMap: Map<String, StyleInfo>,
        val listStyleMap: Map<String, ListStyleInfo>,
        val numberStyleMap: Map<String, OdfNumberFormat>,
        val metadata: OdfMetadata,
        val images: Map<String, ByteArray>,
        val objectContents: Map<String, String>,
        val freezeMap: Map<String, Pair<Int, Int>>,
        val headerFooter: HeaderFooterResult?,
        val pageSetup: com.vayunmathur.library.ui.odf.OdfPageSetup?,
    )

    /** Style maps, metadata, images and layout config for a parse. */
    private fun buildParseBundle(
        entries: ZipEntries,
        stylesXml: String?,
        contentXml: String,
        context: Context,
        uri: Uri,
    ): ParseBundle {
        val styleMap = parseStyleMaps(stylesXml, contentXml)
        val listStyleMap = parseListStyleMaps(stylesXml, contentXml)
        val numberStyleMap = parseNumberStyleMaps(stylesXml, contentXml)

        gradientDefs = buildMap {
            stylesXml?.let { putAll(parseGradients(it)) }
            putAll(parseGradients(contentXml))
        }

        val metaXml = entries.textEntries["meta.xml"]
        var metadata = metaXml?.let { parseMetadata(it) } ?: OdfMetadata()
        metadata = withFileSize(context, uri, metadata)

        val images = entries.binaryEntries
        val objectContents = entries.textEntries.filterKeys { it.startsWith("Object") }
        // Freeze-pane config from settings.xml, keyed by sheet name. (C2)
        val freezeMap = entries.textEntries["settings.xml"]?.let { parseSettings(it) } ?: emptyMap()

        // Parse headers/footers from styles.xml
        val headerFooter = stylesXml?.let { parseHeaderFooter(it, styleMap) }
        // Parse page geometry from styles.xml (Priority 7)
        val pageSetup = stylesXml?.let { parsePageSetup(it) }
        return ParseBundle(
            styleMap,
            listStyleMap,
            numberStyleMap,
            metadata,
            images,
            objectContents,
            freezeMap,
            headerFooter,
            pageSetup)
    }

    /** Metadata with the package file size attached (best-effort). */
    private fun withFileSize(context: Context, uri: Uri, metadata: OdfMetadata): OdfMetadata {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                metadata.copy(fileSize = fd.statSize)
            } ?: metadata
        } catch (_: Exception) {
            metadata
        }
    }

    /** Throw when the manifest declares encryption (unsupported). */
    private fun checkNotEncrypted(entries: ZipEntries) {
        // Encrypted ODF detection (J67): manifest declares per-file encryption-data.
        entries.textEntries["META-INF/manifest.xml"]?.let { manifest ->
            if (manifest.contains("manifest:encryption-data") || manifest.contains("encryption-data")) {
                throw IllegalArgumentException("This document is password-protected" +
                    "(encrypted ODF), which is not supported.")
            }
        }
    }

    /** Style maps from styles.xml + content.xml. */
    private fun parseStyleMaps(stylesXml: String?, contentXml: String): Map<String, StyleInfo> {
        val styleMap = mutableMapOf<String, StyleInfo>()
        stylesXml?.let { styleMap.putAll(parseStyles(it)) }
        styleMap.putAll(parseStyles(contentXml))
        return styleMap
    }

    /** List-style maps from styles.xml + content.xml. */
    private fun parseListStyleMaps(stylesXml: String?, contentXml: String): Map<String, ListStyleInfo> {
        val listStyleMap = mutableMapOf<String, ListStyleInfo>()
        stylesXml?.let { listStyleMap.putAll(parseListStyles(it)) }
        listStyleMap.putAll(parseListStyles(contentXml))
        return listStyleMap
    }

    /** Number-style maps from styles.xml + content.xml. */
    private fun parseNumberStyleMaps(
        stylesXml: String?,
        contentXml: String,
    ): Map<String, OdfNumberFormat> {
        val numberStyleMap = mutableMapOf<String, OdfNumberFormat>()
        stylesXml?.let { numberStyleMap.putAll(parseNumberStyles(it)) }
        numberStyleMap.putAll(parseNumberStyles(contentXml))
        return numberStyleMap
    }

    /** Parses a flat-ODF/content.xml string as a text document. Test seam for the body parser. */
    internal fun parseTextXml(xml: String, fileName: String = "test"): OdfDocument.TextDocument =
        parseTextDocument(xml, parseStyles(xml), parseListStyles(xml), fileName, OdfMetadata(), emptyMap(), null)

    /** Parses settings.xml for per-sheet freeze-pane info: sheet name -> (freezeRows, freezeCols). (C2) */
    private fun parseSettings(xml: String): Map<String, Pair<Int, Int>> {
        val out = mutableMapOf<String, Pair<Int, Int>>()
        val parser = newParser(xml)
        val state = SettingsAcc()
        var e = parser.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            when (e) {
                XmlPullParser.START_TAG -> applySettingsStart(parser, state)
                XmlPullParser.TEXT -> if (state.curItem != null) state.itemText.append(parser.text)
                XmlPullParser.END_TAG -> applySettingsEnd(parser, state, out)
            }
            e = parser.next()
        }
        return out
    }

    /** Settings-parse accumulation state. */
    private class SettingsAcc(
        var inTables: Boolean = false,
        var tablesDepth: Int = -1,
        var curSheet: String? = null,
        var entryDepth: Int = -1,
        var hMode: Int = 0,
        var vMode: Int = 0,
        var hPos: Int = 0,
        var vPos: Int = 0,
        var curItem: String? = null,
        val itemText: StringBuilder = StringBuilder(),
    )

    /** Apply one settings start tag. */
    private fun applySettingsStart(parser: XmlPullParser, state: SettingsAcc) {
        when (parser.name) {
            "config-item-map-named" -> if (getAttr(
                parser,
                "name") == "Tables") { state.inTables = true; state.tablesDepth = parser.depth }
            "config-item-map-entry" -> if (state.inTables && state.curSheet == null) {
                state.curSheet = getAttr(
                    parser,
                    "name")
                state.entryDepth = parser.depth
                state.hMode = 0; state.vMode = 0; state.hPos = 0; state.vPos = 0
            }
            "config-item" -> if (state.curSheet != null) {
                state.curItem = getAttr(parser, "name")
                state.itemText.setLength(0)
            }
        }
    }

    /** Apply one settings end tag. */
    private fun applySettingsEnd(
        parser: XmlPullParser,
        state: SettingsAcc,
        out: MutableMap<String, Pair<Int, Int>>,
    ) {
        when (parser.name) {
            "config-item" -> applyConfigItemEnd(state)
            "config-item-map-entry" -> applySheetEntryEnd(parser, state, out)
            "config-item-map-named" -> applyTablesEnd(parser, state)
        }
    }

    /** Fold one config-item value into split state. */
    private fun applyConfigItemEnd(state: SettingsAcc) {
        val v = state.itemText.toString().trim().toIntOrNull() ?: 0
        applySplitValue(state, state.curItem, v)
        state.curItem = null
    }

    /** One split-mode/position value. */
    private fun applySplitValue(state: SettingsAcc, item: String?, v: Int) {
        when (item) {
            "HorizontalSplitMode" -> state.hMode = v
            "VerticalSplitMode" -> state.vMode = v
            "HorizontalSplitPosition" -> state.hPos = v
            "VerticalSplitPosition" -> state.vPos = v
            "PositionRight" -> if (state.hPos == 0) state.hPos = v
            "PositionBottom" -> if (state.vPos == 0) state.vPos = v
        }
    }

    /** Close one sheet entry, recording its freeze panes. */
    private fun applySheetEntryEnd(
        parser: XmlPullParser,
        state: SettingsAcc,
        out: MutableMap<String, Pair<Int, Int>>,
    ) {
        if (state.curSheet == null || parser.depth != state.entryDepth) return
        val cols = if (state.hMode == SPLIT_MODE_FROZEN) state.hPos else 0
        val rows = if (state.vMode == SPLIT_MODE_FROZEN) state.vPos else 0
        if (rows > 0 || cols > 0) out[state.curSheet!!] = rows to cols
        state.curSheet = null
    }

    /** Close the Tables map. */
    private fun applyTablesEnd(parser: XmlPullParser, state: SettingsAcc) {
        if (state.inTables && parser.depth == state.tablesDepth) {
            state.inTables = false
        }
    }

    private const val SPLIT_MODE_FROZEN = 2

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
        if (!isZip(raw)) {
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
                extractZipEntry(zip, entry, textEntries, binaryEntries, textFiles)
                entry = zip.nextEntry
            }
        }
        return ZipEntries(textEntries, binaryEntries)
    }

    /** True when the bytes start with the ZIP signature. */
    private fun isZip(raw: ByteArray): Boolean =
        raw.size >= 2 && raw[0] == 'P'.code.toByte() && raw[1] == 'K'.code.toByte()

    /** Route one ZIP entry to the text or binary map. */
    private fun extractZipEntry(
        zip: ZipInputStream,
        entry: java.util.zip.ZipEntry,
        textEntries: MutableMap<String, String>,
        binaryEntries: MutableMap<String, ByteArray>,
        textFiles: Set<String>,
    ) {
        val name = entry.name
        when {
            name in textFiles -> textEntries[name] = zip.bufferedReader().readText()
            name.startsWith("Object") && name.endsWith("content.xml") -> textEntries[name] =
                zip.bufferedReader().readText()
            !entry.isDirectory && (
                name.startsWith("Pictures/") || name.startsWith("media/") ||
                name.startsWith("ObjectReplacements") || name.startsWith("Thumbnails/")
                ) -> binaryEntries[name] = zip.readBytes()
        }
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

    // --- XML helpers (shared with the OdfParser*.kt extension files) ---

    internal fun newParser(xml: String): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(xml.reader())
        return parser
    }

    internal fun getAttr(parser: XmlPullParser, localName: String): String? {
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeName(i) == localName) return parser.getAttributeValue(i)
        }
        return null
    }

    internal fun skipElement(parser: XmlPullParser) {
        val depth = parser.depth
        var eventType = parser.next()
        while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
            eventType = parser.next()
        }
    }

    private const val PX_PER_CM = 37.8f
    private const val PX_PER_MM = 3.78f
    private const val PX_PER_INCH = 96f
    private const val PX_PER_PICA = 16f
    private const val PX_PER_PT = 1.33f
    private const val PX_PER_EM = 16f
    private const val SHORT_HEX_LENGTH = 3
    private const val FULL_HEX_LENGTH = 6
    private const val FULL_ALPHA = 0xFF000000L
    private const val HEX_RADIX = 16
    private const val PERCENT_DIVISOR = 100f
    private const val RECT_TOP = 0
    private const val RECT_RIGHT = 1
    private const val RECT_BOTTOM = 2
    private const val RECT_LEFT = 3
    private const val RECT_PARTS = 4
    private const val MAX_FRACTION = 0.95f
    private const val DEGREES_HALF_CIRCLE = 180.0

    internal fun parseDimension(value: String?): Float {
        if (value == null) return 0f
        val numeric = value.replace(Regex("[^0-9.\\-]"), "")
        val base = numeric.toFloatOrNull() ?: 0f
        return when {
            value.endsWith("cm") -> base * PX_PER_CM
            value.endsWith("mm") -> base * PX_PER_MM
            value.endsWith("in") -> base * PX_PER_INCH
            value.endsWith("pc") -> base * PX_PER_PICA      // pica = 12pt
            value.endsWith("pt") -> base * PX_PER_PT
            value.endsWith("em") -> base * PX_PER_EM      // relative to a 12pt default
            else -> base
        }
    }

    internal fun parseColor(hex: String): Long? {
        return try {
            var colorStr = hex.trim().removePrefix("#")
            // Expand 3-digit shorthand (#FFF -> #FFFFFF).
            if (colorStr.length == SHORT_HEX_LENGTH) {
                colorStr = colorStr.map { "$it$it" }.joinToString("")
            }
            if (colorStr.length != FULL_HEX_LENGTH) return null
            FULL_ALPHA or colorStr.toLong(HEX_RADIX)
        } catch (_: Exception) { null }
    }

    /* Parses an fo:clip="rect(top right bottom left)" value (percent tokens) into [left, top, right, bottom]
     * fractions. (Phase 5) */
    internal fun parseClip(value: String?): FloatArray? {
        if (value == null) return null
        val inner = value.substringAfter("rect(", "").substringBefore(")")
        if (inner.isBlank()) return null
        val parts = inner.trim().split(Regex("[ ,]+"))
        if (parts.size < RECT_PARTS) return null
        fun f(s: String): Float {
            val t = s.trim()
            return when {
                t.endsWith("%") -> (t.dropLast(1).toFloatOrNull() ?: 0f) / PERCENT_DIVISOR
                else -> 0f
            }.coerceIn(0f, MAX_FRACTION)
        }
        val top = f(parts[RECT_TOP])
        val right = f(parts[RECT_RIGHT])
        val bottom = f(parts[RECT_BOTTOM])
        val left = f(parts[RECT_LEFT])
        if (isZeroCrop(top, right, bottom, left)) return null
        return floatArrayOf(left, top, right, bottom)
    }

    /** True when all four crop insets are zero. */
    private fun isZeroCrop(top: Float, right: Float, bottom: Float, left: Float): Boolean =
        top == 0f && right == 0f && bottom == 0f && left == 0f

    /** Parses fo:clip="rect(top right bottom left)" absolute lengths into [left, top, right, bottom] fractions
     *  using the natural image size (px@96). Returns null if no crop. (A7) */
    internal fun parseClipLengths(value: String?, wPx: Float, hPx: Float): FloatArray? {
        if (value == null || wPx <= 0f || hPx <= 0f) return null
        val inner = value.substringAfter("rect(", "").substringBefore(")")
        if (inner.isBlank()) return null
        val parts = inner.trim().split(Regex("[ ,]+"))
        if (parts.size < RECT_PARTS) return null
        // If any token is a percentage, fall back to percent parsing.
        if (parts.any { it.trim().endsWith("%") }) return parseClip(value)
        fun frac(token: String, basePx: Float): Float =
            (parseDimension(token.trim()) / basePx).coerceIn(0f, MAX_FRACTION)
        val top =
            frac(parts[0], hPx); val right = frac(parts[1], wPx); val bottom = frac(parts[2], hPx); val left = frac(
                parts[3],
                wPx)
        if (isZeroCrop(top, right, bottom, left)) return null
        return floatArrayOf(left, top, right, bottom)
    }

    /** Decodes the intrinsic pixel size of an encoded image without allocating the full bitmap. (A7) */
    internal fun decodeNaturalSize(bytes: ByteArray): Pair<Float, Float> {
        return try {
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            opts.outWidth.toFloat() to opts.outHeight.toFloat()
        } catch (_: Exception) { 0f to 0f }
    }

    /** Converts an ODF draw:transform rotate(theta) into degrees clockwise for Compose (E38). */
    internal fun parseRotationDegrees(transform: String?): Float {
        if (transform == null) return 0f
        val m = Regex("rotate\\(([-0-9.]+)\\)").find(transform) ?: return 0f
        val rad = m.groupValues[1].toFloatOrNull() ?: return 0f
        return -(rad * DEGREES_HALF_CIRCLE / Math.PI).toFloat()
    }

    internal const val LINK_COLOR = 0xFF0066CCL
    // Deletion-region text keyed by change-id, populated while parsing text:tracked-changes
    // and consumed by the inline parser to render struck-through deleted text. (Priority 6)
    internal var trackedDeletionText: Map<String, String> = emptyMap()
    /** Gradient definitions (draw:gradient name -> def), set during parse(). (Round 3) */
    internal var gradientDefs: Map<String, OdfGradient> = emptyMap()
    // ODF inline text-field element local names recognized by the inline parser. (Priority 2)
    internal val FIELD_TAGS = setOf(
        "date", "time", "page-number", "page-count", "file-name", "author-name",
        "author-initials", "title", "subject", "description", "chapter", "sheet-name",
        "creation-date", "modification-date", "editing-cycles",
        // Additional field elements (Phase 2).
        "variable-set", "variable-get", "user-field-get", "sequence", "placeholder",
        "conditional-text", "hidden-text", "page-continuation", "bibliography-mark"
    )
}
