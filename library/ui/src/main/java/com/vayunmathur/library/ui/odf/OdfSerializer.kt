package com.vayunmathur.library.ui.odf

import androidx.compose.ui.text.style.TextAlign
import java.util.Locale

/** Serializes the ODF model to package XML (content, styles, manifest, meta). */
object OdfSerializer {

    /** Extra package parts generated during serialization (inline images promoted to the package,
     *  embedded chart objects, and the manifest media types for those objects). (A6/A8) */
    data class SerResult(
        val contentXml: String,
        val images: Map<String, ByteArray>,
        val objects: Map<String, String>,
        val manifest: Map<String, String>
    )

    private class SerCtx(val flat: Boolean) {
        val images = LinkedHashMap<String, ByteArray>()
        val objects = LinkedHashMap<String, String>()
        val manifest = LinkedHashMap<String, String>()
        private var imgN = 0
        private var objN = 0
        fun nextImagePath(ext: String): String { imgN++; return "Pictures/inline$imgN.$ext" }
        fun nextObjectDir(): String { objN++; return "Object Chart $objN" }
    }

    /** Content-only serialization (used for in-package content.xml via [serializePackaged] and flat export). */
    fun serialize(document: OdfDocument): String = serializeInner(document, SerCtx(flat = true))

    /** Full package serialization: content.xml plus generated inline images and embedded chart objects. */
    fun serializePackaged(document: OdfDocument): SerResult {
        val ctx = SerCtx(flat = false)
        val xml = serializeInner(document, ctx)
        return SerResult(xml, ctx.images, ctx.objects, ctx.manifest)
    }

    private fun serializeInner(document: OdfDocument, ctx: SerCtx): String {
        return when (document) {
            is OdfDocument.TextDocument -> serializeTextDocument(document, ctx)
            is OdfDocument.Spreadsheet -> serializeSpreadsheet(document, ctx)
            is OdfDocument.Presentation -> serializePresentation(document, ctx)
            is OdfDocument.Drawing -> serializePresentation(
                OdfDocument.Presentation(document.title, document.pages, document.metadata, document.images), ctx
            )
        }
    }

    /** Serializes to a flat ODF (.fodt/.fods/.fodp) single-XML document (K75). */
    fun serializeFlat(document: OdfDocument): String {
        val content = serialize(document)
        val mimetype = when (document) {
            is OdfDocument.TextDocument -> "application/vnd.oasis.opendocument.text"
            is OdfDocument.Spreadsheet -> "application/vnd.oasis.opendocument.spreadsheet"
            is OdfDocument.Presentation -> "application/vnd.oasis.opendocument.presentation"
            is OdfDocument.Drawing -> "application/vnd.oasis.opendocument.graphics"
        }
        val metaFull = serializeMeta(document.metadata)
        val metaInner = if (metaFull.contains("<office:meta>"))
            "<office:meta>" + metaFull.substringAfter("<office:meta>").substringBefore("</office:meta>") + "</office:meta>"
        else ""
        var s = content.replace("<office:document-content", "<office:document")
        s = s.replace("""office:version="1.3">""", """office:mimetype="$mimetype" office:version="1.3">$metaInner""")
        s = s.replace("</office:document-content>", "</office:document>")
        return s
    }

    /** Minimal valid styles.xml for packages that don't already carry one (new documents). (Priority 1) */
    fun serializeStyles(document: OdfDocument): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.append("""<office:document-styles""")
        sb.append(""" xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"""")
        sb.append(""" xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0"""")
        sb.append(""" xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"""")
        sb.append(""" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"""")
        sb.append(""" xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0"""")
        sb.append(""" xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"""")
        sb.append(""" xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0"""")
        sb.append(""" office:version="1.3">""")
        // Font declarations referenced by the default + named styles. (Round 2 R10)
        sb.append("<office:font-face-decls>")
        sb.append("""<style:font-face style:name="Liberation Serif" svg:font-family="&apos;Liberation Serif&apos;" style:font-family-generic="roman" style:font-pitch="variable"/>""")
        sb.append("""<style:font-face style:name="Liberation Sans" svg:font-family="&apos;Liberation Sans&apos;" style:font-family-generic="swiss" style:font-pitch="variable"/>""")
        sb.append("</office:font-face-decls>")
        // Common named/default styles so a brand-new document opens with proper paragraph/heading
        // styles in LibreOffice rather than only ad-hoc automatic styles. (Round 2 R10)
        sb.append("<office:styles>")
        sb.append("""<style:default-style style:family="paragraph"><style:paragraph-properties fo:hyphenation-ladder-count="no-limit"/><style:text-properties style:font-name="Liberation Serif" fo:font-size="12pt" fo:language="en" fo:country="US"/></style:default-style>""")
        sb.append("""<style:style style:name="Standard" style:family="paragraph" style:class="text"/>""")
        sb.append("""<style:style style:name="Text_20_body" style:display-name="Text body" style:family="paragraph" style:parent-style-name="Standard" style:class="text"><style:paragraph-properties fo:margin-top="0cm" fo:margin-bottom="0.247cm"/></style:style>""")
        sb.append("""<style:style style:name="Heading" style:family="paragraph" style:parent-style-name="Standard" style:next-style-name="Text_20_body" style:class="text"><style:paragraph-properties fo:margin-top="0.423cm" fo:margin-bottom="0.212cm" fo:keep-with-next="always"/><style:text-properties style:font-name="Liberation Sans" fo:font-size="14pt"/></style:style>""")
        for (lvl in 1..4) {
            val size = when (lvl) { 1 -> 28; 2 -> 21; 3 -> 16; else -> 14 }
            sb.append("""<style:style style:name="Heading_20_$lvl" style:display-name="Heading $lvl" style:family="paragraph" style:parent-style-name="Heading" style:next-style-name="Text_20_body" style:default-outline-level="$lvl" style:class="text"><style:text-properties fo:font-size="${size}pt" fo:font-weight="bold"/></style:style>""")
        }
        sb.append("""<style:style style:name="List" style:family="paragraph" style:parent-style-name="Standard" style:class="list"/>""")
        sb.append("</office:styles>")
        sb.append("<office:automatic-styles>")
        // A default A4 portrait page layout + master page so text/presentation docs paginate sanely.
        val ps = (document as? OdfDocument.TextDocument)?.pageSetup
        val plp = if (ps != null) {
            """fo:page-width="${cm(ps.widthPx)}" fo:page-height="${cm(ps.heightPx)}" fo:margin-top="${cm(ps.marginTopPx)}" fo:margin-bottom="${cm(ps.marginBottomPx)}" fo:margin-left="${cm(ps.marginLeftPx)}" fo:margin-right="${cm(ps.marginRightPx)}" style:print-orientation="${if (ps.isLandscape) "landscape" else "portrait"}""""
        } else {
            """fo:page-width="21cm" fo:page-height="29.7cm" fo:margin-top="2cm" fo:margin-bottom="2cm" fo:margin-left="2cm" fo:margin-right="2cm""""
        }
        sb.append("""<style:page-layout style:name="pm1"><style:page-layout-properties $plp/></style:page-layout>""")
        sb.append("</office:automatic-styles>")
        sb.append("""<office:master-styles><style:master-page style:name="Standard" style:page-layout-name="pm1"/></office:master-styles>""")
        sb.append("</office:document-styles>")
        return sb.toString()
    }

    /** Generates settings.xml carrying freeze-pane (split) config when any sheet is frozen. Returns null if none. (C2) */
    fun serializeSettings(document: OdfDocument): String? {
        if (document !is OdfDocument.Spreadsheet) return null
        val frozen = document.sheets.filter { it.freezeRows > 0 || it.freezeCols > 0 }
        if (frozen.isEmpty()) return null
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.append("""<office:document-settings xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:config="urn:oasis:names:tc:opendocument:xmlns:config:1.0" xmlns:ooo="http://openoffice.org/2004/office" office:version="1.3">""")
        sb.append("<office:settings>")
        sb.append("""<config:config-item-set config:name="ooo:view-settings">""")
        sb.append("""<config:config-item-map-indexed config:name="Views"><config:config-item-map-entry>""")
        sb.append("""<config:config-item config:name="ViewId" config:type="string">view1</config:config-item>""")
        sb.append("""<config:config-item-map-named config:name="Tables">""")
        for (sheet in document.sheets) {
            val rows = sheet.freezeRows; val cols = sheet.freezeCols
            if (rows <= 0 && cols <= 0) continue
            sb.append("""<config:config-item-map-entry config:name="${esc(sheet.name)}">""")
            sb.append("""<config:config-item config:name="HorizontalSplitMode" config:type="short">${if (cols > 0) 2 else 0}</config:config-item>""")
            sb.append("""<config:config-item config:name="VerticalSplitMode" config:type="short">${if (rows > 0) 2 else 0}</config:config-item>""")
            sb.append("""<config:config-item config:name="HorizontalSplitPosition" config:type="int">$cols</config:config-item>""")
            sb.append("""<config:config-item config:name="VerticalSplitPosition" config:type="int">$rows</config:config-item>""")
            sb.append("""<config:config-item config:name="PositionRight" config:type="int">$cols</config:config-item>""")
            sb.append("""<config:config-item config:name="PositionBottom" config:type="int">$rows</config:config-item>""")
            sb.append("""<config:config-item config:name="ActiveSplitRange" config:type="short">3</config:config-item>""")
            sb.append("</config:config-item-map-entry>")
        }
        sb.append("</config:config-item-map-named>")
        sb.append("</config:config-item-map-entry></config:config-item-map-indexed>")
        sb.append("</config:config-item-set>")
        sb.append("</office:settings></office:document-settings>")
        return sb.toString()
    }

    /** Generates meta.xml so edited document metadata persists on save (G47). */
    fun serializeMeta(meta: OdfMetadata): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.append("""<office:document-meta xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:meta="urn:oasis:names:tc:opendocument:xmlns:meta:1.0" office:version="1.3"><office:meta>""")
        sb.append("<meta:generator>${esc(meta.generator ?: "ModernApps/Office")}</meta:generator>")
        meta.title?.let { sb.append("<dc:title>${esc(it)}</dc:title>") }
        meta.creator?.let { sb.append("<meta:initial-creator>${esc(it)}</meta:initial-creator>") }
        meta.author?.let { sb.append("<dc:creator>${esc(it)}</dc:creator>") }
        meta.subject?.let { sb.append("<dc:subject>${esc(it)}</dc:subject>") }
        meta.description?.let { sb.append("<dc:description>${esc(it)}</dc:description>") }
        for (kw in meta.keywords) sb.append("<meta:keyword>${esc(kw)}</meta:keyword>")
        meta.creationDate?.let { sb.append("<meta:creation-date>${esc(it)}</meta:creation-date>") }
        meta.modifiedDate?.let { sb.append("<dc:date>${esc(it)}</dc:date>") }
        meta.editingCycles?.let { sb.append("<meta:editing-cycles>$it</meta:editing-cycles>") }
        // Document statistics: emit whatever counts we have. (Round 2 R1)
        if (meta.pageCount != null || meta.wordCount != null || meta.charCount != null || meta.paragraphCount != null) {
            sb.append("<meta:document-statistic")
            meta.pageCount?.let { sb.append(""" meta:page-count="$it"""") }
            meta.wordCount?.let { sb.append(""" meta:word-count="$it"""") }
            meta.charCount?.let { sb.append(""" meta:character-count="$it"""") }
            meta.paragraphCount?.let { sb.append(""" meta:paragraph-count="$it"""") }
            sb.append("/>")
        }
        for ((name, value) in meta.userDefined) {
            val vt = meta.userDefinedTypes[name]
            sb.append("""<meta:user-defined meta:name="${esc(name)}"""")
            if (vt != null) sb.append(""" meta:value-type="${esc(vt)}"""")
            sb.append(">${esc(value)}</meta:user-defined>")
        }
        sb.append("</office:meta></office:document-meta>")
        return sb.toString()
    }

    private fun serializeTextDocument(doc: OdfDocument.TextDocument, ctx: SerCtx): String {
        val styles = mutableMapOf<String, SpanStyleDef>()
        val paraStyles = mutableMapOf<String, ParaStyleDef>()
        val graphicStyles = LinkedHashMap<String, GraphicStyleDef>()
        val tableColStyles = LinkedHashMap<String, Float>()
        val tableCellStyles = LinkedHashMap<String, CellStyleDef>()
        val sectionStyles = LinkedHashMap<String, Int>()
        val listStyles = LinkedHashMap<String, ListStyleDef>()
        fun sectionStyleName(cols: Int, m: MutableMap<String, Int>): String { for ((n, v) in m) if (v == cols) return n; val n = "Sect${m.size + 1}"; m[n] = cols; return n }
        val body = StringBuilder()

        // Bookmarks keyed by the content-block index they precede. (B3)
        val bookmarksByIndex = HashMap<Int, MutableList<String>>()
        for (bk in doc.bookmarks) bookmarksByIndex.getOrPut(bk.contentIndex) { mutableListOf() }.add(bk.name)
        // Footnotes keyed by citation, emitted inline as text:note where the marker appears. (B3)
        val footnotesByCitation = doc.footnotes.associateBy { it.citation }
        val emittedFootnotes = mutableSetOf<String>()

        // Nested-list emitter (Round 2 R8): tracks open <text:list> depth and the currently open item.
        var listDepth = 0
        var itemOpen = false
        fun closeAllLists() {
            if (itemOpen) { body.append("</text:list-item>"); itemOpen = false }
            while (listDepth > 0) { body.append("</text:list>"); listDepth--; if (listDepth > 0) body.append("</text:list-item>") }
        }
        doc.content.forEachIndexed { index, block ->
            val leadingBookmarks = bookmarksByIndex[index] ?: emptyList()
            when (block) {
                is OdfContentBlock.Paragraph -> {
                    val para = block.paragraph
                    if (para.style == ParagraphStyle.LIST_ITEM) {
                        val target = para.listLevel.coerceAtLeast(1)
                        when {
                            target > listDepth -> {
                                while (listDepth < target) {
                                    if (listDepth == 0) body.append("""<text:list text:style-name="${getOrCreateListStyle(para, listStyles)}">""")
                                    else body.append("<text:list>")
                                    listDepth++
                                    if (listDepth < target) body.append("<text:list-item>")
                                }
                            }
                            target < listDepth -> {
                                if (itemOpen) { body.append("</text:list-item>"); itemOpen = false }
                                while (listDepth > target) { body.append("</text:list>"); listDepth--; body.append("</text:list-item>") }
                            }
                            else -> if (itemOpen) { body.append("</text:list-item>"); itemOpen = false }
                        }
                        body.append("<text:list-item")
                        if (para.listType == ListType.CHECKBOX) body.append(""" loext:checkbox-status="${if (para.listChecked) "checked" else "unchecked"}"""")
                        body.append(">"); itemOpen = true
                        serializeParagraph(body, para, styles, paraStyles, "text:p", leadingBookmarks, footnotesByCitation, emittedFootnotes)
                    } else {
                        closeAllLists()
                        val tag = when (para.style) {
                            ParagraphStyle.HEADING1, ParagraphStyle.HEADING2,
                            ParagraphStyle.HEADING3, ParagraphStyle.HEADING4 -> "text:h"
                            else -> "text:p"
                        }
                        serializeParagraph(body, para, styles, paraStyles, tag, leadingBookmarks, footnotesByCitation, emittedFootnotes)
                    }
                }
                is OdfContentBlock.Table -> {
                    closeAllLists()
                    serializeTable(body, block.table, styles, paraStyles, tableColStyles, tableCellStyles)
                }
                is OdfContentBlock.Image -> {
                    closeAllLists()
                    serializeImageRef(body, block.image, graphicStyles, ctx)
                }
                is OdfContentBlock.Chart -> {
                    closeAllLists()
                    serializeChartFrame(body, block.chart, 0f, 0f, 480f, 320f, "paragraph", styles, paraStyles, ctx)
                }
                is OdfContentBlock.Formula -> {
                    closeAllLists()
                    // Formulas are embedded math objects; preserved via original package, not re-serialized inline.
                }
                is OdfContentBlock.TableOfContents -> {
                    closeAllLists()
                    serializeTableOfContents(body, block, styles, paraStyles)
                }
                is OdfContentBlock.PageBreak -> {
                    closeAllLists()
                    // Emit an empty paragraph carrying a page-break-before style. (B4)
                    val name = getOrCreateParaStyle(OdfParagraph(emptyList(), breakBeforePage = true), paraStyles)
                    body.append("""<text:p text:style-name="$name"/>""")
                }
                is OdfContentBlock.SectionStart -> {
                    closeAllLists()
                    val sn = if (block.columnCount > 1) sectionStyleName(block.columnCount, sectionStyles) else null
                    body.append("""<text:section text:name="${esc(block.name)}"""")
                    if (sn != null) body.append(""" text:style-name="$sn"""")
                    body.append(">")
                }
                is OdfContentBlock.SectionEnd -> {
                    closeAllLists()
                    body.append("</text:section>")
                }
            }
        }
        closeAllLists()

        val tracked = serializeTrackedChanges(doc)
        return buildDocument("office:text", styles, paraStyles, tableCellStyles, tableColStyles, graphicStyles, tracked + body.toString(), sectionStyles = sectionStyles, listStyles = listStyles)
    }

    private fun serializeSpreadsheet(doc: OdfDocument.Spreadsheet, ctx: SerCtx): String {
        val cellStyles = LinkedHashMap<String, CellStyleDef>()
        val colStyles = LinkedHashMap<String, Float>()
        val rowStyles = LinkedHashMap<String, Float>()
        val spanStyles = mutableMapOf<String, SpanStyleDef>()
        val paraStyles = mutableMapOf<String, ParaStyleDef>()
        val graphicStyles = LinkedHashMap<String, GraphicStyleDef>()
        fun rowStyleName(h: Float): String { for ((n, v) in rowStyles) if (v == h) return n; val n = "ro${rowStyles.size + 1}"; rowStyles[n] = h; return n }
        val body = StringBuilder()
        // Content validations must precede the tables. (Round 3)
        if (doc.validations.isNotEmpty()) {
            body.append("<table:content-validations>")
            for (vd in doc.validations) {
                body.append("""<table:content-validation table:name="${esc(vd.name)}" table:condition="${esc(vd.condition)}" table:allow-empty-cell="${vd.allowEmpty}"/>""")
            }
            body.append("</table:content-validations>")
        }
        for (sheet in doc.sheets) {
            body.append("""<table:table table:name="${esc(sheet.name)}"""")
            sheet.printRanges?.let { body.append(""" table:print-ranges="${esc(it)}"""") }
            body.append(">")
            val maxCols = sheet.rows.maxOfOrNull { it.cells.size } ?: 0
            for (c in 0 until maxCols) {
                val w = sheet.columnWidths.getOrNull(c)
                body.append("<table:table-column")
                if (w != null && w > 0f) body.append(""" table:style-name="${getOrCreateColStyle(w, colStyles)}"""")
                if (c in sheet.hiddenCols) body.append(""" table:visibility="collapse"""")
                body.append("/>")
            }
            sheet.rows.forEachIndexed { ri, row ->
                body.append("<table:table-row")
                sheet.rowHeights.getOrNull(ri)?.let { if (it > 0f) body.append(""" table:style-name="${rowStyleName(it)}"""") }
                if (ri in sheet.hiddenRows) body.append(""" table:visibility="collapse"""")
                body.append(">")
                for (cell in row.cells) {
                    if (cell.isCovered) {
                        body.append("<table:covered-table-cell/>")
                    } else {
                        body.append("<table:table-cell")
                        val styleName = getOrCreateCellStyle(cell, cellStyles)
                        if (styleName != null) body.append(""" table:style-name="$styleName"""")
                        if (cell.spannedColumns > 1) body.append(""" table:number-columns-spanned="${cell.spannedColumns}"""")
                        if (cell.rowSpan > 1) body.append(""" table:number-rows-spanned="${cell.rowSpan}"""")
                        if (cell.formula != null) body.append(""" table:formula="${esc(cell.formula)}"""")
                        if (cell.validationName != null) body.append(""" table:content-validation-name="${esc(cell.validationName)}"""")
                        // A freshly-typed formula caches its "=…" source as text, which is
                        // not a display value: omit the value cache so readers evaluate
                        // table:formula instead of showing the raw "=A1" string.
                        // (Imported cells carry a real display string and are unaffected.)
                        val isUncomputedFormula = cell.formula != null && cell.text.startsWith("=") &&
                            cell.numberValue == null
                        val numeric = if (isUncomputedFormula) null
                            else cell.numberValue ?: cell.text.toDoubleOrNull()
                        if (numeric != null && cell.valueType != "string") {
                            body.append(""" office:value-type="float" office:value="$numeric"""")
                        } else if (!isUncomputedFormula && cell.text.isNotEmpty()) {
                            body.append(""" office:value-type="string"""")
                        }
                        body.append(">")
                        cell.annotation?.let { ann ->
                            body.append("<office:annotation>")
                            ann.author?.let { body.append("<dc:creator>${esc(it)}</dc:creator>") }
                            ann.date?.let { body.append("<dc:date>${esc(it)}</dc:date>") }
                            for (p in ann.paragraphs) serializeParagraph(body, p, spanStyles, paraStyles, "text:p")
                            body.append("</office:annotation>")
                        }
                        if (!isUncomputedFormula && cell.text.isNotEmpty()) body.append("<text:p>${encodeText(cell.text)}</text:p>")
                        body.append("</table:table-cell>")
                    }
                }
                body.append("</table:table-row>")
            }
            // Floating objects anchored to the sheet (Phase 4).
            for (element in sheet.floating) {
                when (element) {
                    is OdfSlideElement.Frame -> serializeFrame(body, element.frame, spanStyles, paraStyles, graphicStyles, ctx)
                    is OdfSlideElement.Shape -> serializeShape(body, element.shape, spanStyles, paraStyles, graphicStyles)
                }
            }
            body.append("</table:table>")
        }
        // Workbook-level named ranges. (Round 2 R6)
        if (doc.namedRanges.isNotEmpty()) {
            body.append("<table:named-expressions>")
            for (nr in doc.namedRanges) {
                body.append("""<table:named-range table:name="${esc(nr.name)}"""")
                nr.baseCellAddress?.let { body.append(""" table:base-cell-address="${esc(it)}"""") }
                body.append(""" table:cell-range-address="${esc(nr.cellRangeAddress)}"/>""")
            }
            body.append("</table:named-expressions>")
        }
        return buildDocument("office:spreadsheet", spanStyles, paraStyles, cellStyles, colStyles, graphicStyles, body.toString(), rowStyles = rowStyles)
    }

    private fun serializePresentation(doc: OdfDocument.Presentation, ctx: SerCtx): String {
        val styles = mutableMapOf<String, SpanStyleDef>()
        val paraStyles = mutableMapOf<String, ParaStyleDef>()
        val graphicStyles = LinkedHashMap<String, GraphicStyleDef>()
        val drawPageStyles = LinkedHashMap<String, DrawPageStyleDef>()
        val body = StringBuilder()
        for (slide in doc.slides) {
            body.append("<draw:page")
            // Slide background fill + transition via a drawing-page style. (B2 / R9)
            getOrCreateDrawPageStyle(slide.backgroundColor, slide.transitionType, slide.transitionSpeed, drawPageStyles)
                ?.let { body.append(""" draw:style-name="$it"""") }
            body.append(""" draw:name="${esc(slide.name)}" draw:master-page-name="${esc(slide.masterName ?: "Standard")}">""")
            for (element in slide.elements) {
                when (element) {
                    is OdfSlideElement.Frame -> serializeFrame(body, element.frame, styles, paraStyles, graphicStyles, ctx)
                    is OdfSlideElement.Shape -> serializeShape(body, element.shape, styles, paraStyles, graphicStyles)
                }
            }
            // Speaker notes. (B2)
            if (slide.notes.isNotEmpty()) {
                body.append("<presentation:notes><draw:frame svg:x=\"1.5cm\" svg:y=\"12cm\" svg:width=\"18cm\" svg:height=\"10cm\"><draw:text-box>")
                for (note in slide.notes) serializeParagraph(body, note, styles, paraStyles, "text:p")
                body.append("</draw:text-box></draw:frame></presentation:notes>")
            }
            body.append("</draw:page>")
        }
        return buildDocument("office:presentation", styles, paraStyles, LinkedHashMap(), LinkedHashMap(), graphicStyles, body.toString(), drawPageStyles)
    }

    private data class DrawPageStyleDef(val fillColor: Long?, val transitionType: String?, val transitionSpeed: String?)

    private fun serializeParagraph(
        sb: StringBuilder, para: OdfParagraph,
        styles: MutableMap<String, SpanStyleDef>,
        paraStyles: MutableMap<String, ParaStyleDef>,
        tag: String,
        leadingBookmarks: List<String> = emptyList(),
        footnotes: Map<String, OdfFootnote> = emptyMap(),
        emittedFootnotes: MutableSet<String>? = null
    ) {
        sb.append("<$tag")
        val pStyleName = getOrCreateParaStyle(para, paraStyles)
        if (pStyleName != null) sb.append(""" text:style-name="$pStyleName"""")
        if (tag == "text:h") {
            val level = when (para.style) {
                ParagraphStyle.HEADING1 -> 1; ParagraphStyle.HEADING2 -> 2
                ParagraphStyle.HEADING3 -> 3; else -> 4
            }
            sb.append(""" text:outline-level="$level"""")
        }
        sb.append(">")
        for (name in leadingBookmarks) sb.append("""<text:bookmark text:name="${esc(name)}"/>""")
        for (span in para.spans) {
            // Footnote citation marker -> real text:note (B3). Recognizes both parser-produced
            // superscript citations and editor-inserted "[n]" markers.
            val fn = footnoteForSpan(span, footnotes)
            if (fn != null && emittedFootnotes?.contains(fn.citation) != true) {
                emittedFootnotes?.add(fn.citation)
                val noteClass = if (fn.isEndnote) "endnote" else "footnote"
                val idPrefix = if (fn.isEndnote) "edn" else "ftn"
                sb.append("""<text:note text:note-class="$noteClass" text:id="$idPrefix${esc(fn.citation)}">""")
                sb.append("""<text:note-citation>${esc(fn.citation)}</text:note-citation>""")
                sb.append("<text:note-body>")
                if (fn.body.isEmpty()) sb.append("<text:p/>")
                else for (p in fn.body) serializeParagraph(sb, p, styles, paraStyles, "text:p")
                sb.append("</text:note-body></text:note>")
                continue
            }
            if (span.annotation != null) {
                // Serialize comments as office:annotation so they round-trip. (B3)
                sb.append("<office:annotation>")
                span.annotation.author?.let { sb.append("<dc:creator>${esc(it)}</dc:creator>") }
                span.annotation.date?.let { sb.append("<dc:date>${esc(it)}</dc:date>") }
                for (p in span.annotation.paragraphs) serializeParagraph(sb, p, styles, paraStyles, "text:p")
                sb.append("</office:annotation>")
                continue
            }
            if (span.field != null) {
                // Real ODF text field element with the cached display value. (Priority 2)
                val tag = "text:${span.field}"
                val attrs = if (span.field == "date" || span.field == "time") " text:fixed=\"true\"" else ""
                if (span.text.isEmpty()) sb.append("<$tag$attrs/>")
                else sb.append("<$tag$attrs>${esc(span.text)}</$tag>")
                continue
            }
            if (span.refKind != null) {
                // Cross-reference element. (Priority 5)
                val rtag = "text:${span.refKind}"
                when (span.refKind) {
                    "reference-ref", "bookmark-ref" -> {
                        sb.append("<$rtag")
                        span.refFormat?.let { sb.append(""" text:reference-format="${esc(it)}"""") }
                        span.refName?.let { sb.append(""" text:ref-name="${esc(it)}"""") }
                        sb.append(">${esc(span.text)}</$rtag>")
                    }
                    else -> {
                        sb.append("<$rtag")
                        span.refName?.let { sb.append(""" text:name="${esc(it)}"""") }
                        sb.append("/>")
                    }
                }
                continue
            }
            if (span.changeKind == "deletion") {
                // Deleted content lives in the tracked-changes region; body carries only a point marker. (Priority 6)
                span.changeId?.let { sb.append("""<text:change text:change-id="${esc(it)}"/>""") }
                continue
            }
            val insWrap = span.changeKind == "insertion" && span.changeId != null
            if (insWrap) sb.append("""<text:change-start text:change-id="${esc(span.changeId)}"/>""")
            val needsStyle = span.bold || span.italic || span.underline || span.strikethrough ||
                span.color != null || span.fontSize != null || span.superscript || span.subscript || span.fontFamily != null ||
                span.letterSpacing != null || span.textTransform != null || span.language != null
            if (span.href != null) {
                sb.append("""<text:a xlink:href="${esc(span.href)}" xlink:type="simple">""")
                if (needsStyle) {
                    val styleName = getOrCreateSpanStyle(span, styles)
                    sb.append("""<text:span text:style-name="$styleName">${encodeText(span.text)}</text:span>""")
                } else {
                    sb.append(encodeText(span.text))
                }
                sb.append("</text:a>")
            } else if (needsStyle) {
                val styleName = getOrCreateSpanStyle(span, styles)
                sb.append("""<text:span text:style-name="$styleName">${encodeText(span.text)}</text:span>""")
            } else {
                sb.append(encodeText(span.text))
            }
            if (insWrap) sb.append("""<text:change-end text:change-id="${esc(span.changeId)}"/>""")
        }
        sb.append("</$tag>")
    }

    private fun serializeTrackedChanges(doc: OdfDocument.TextDocument): String {
        class Info(var kind: String, var deletedText: String)
        val used = LinkedHashMap<String, Info>()
        fun scan(spans: List<OdfSpan>) {
            for (s in spans) {
                val id = s.changeId ?: continue
                val kind = s.changeKind ?: continue
                val info = used.getOrPut(id) { Info(kind, "") }
                if (kind == "deletion" && s.text.isNotEmpty()) info.deletedText = s.text
            }
        }
        fun scanParas(paras: List<OdfParagraph>) { for (p in paras) scan(p.spans) }
        for (block in doc.content) when (block) {
            is OdfContentBlock.Paragraph -> scan(block.paragraph.spans)
            is OdfContentBlock.Table -> for (row in block.table.rows) for (cell in row.cells) scanParas(cell.paragraphs)
            is OdfContentBlock.TableOfContents -> scanParas(block.entries)
            else -> {}
        }
        if (used.isEmpty()) return ""
        val meta = doc.changes.associateBy { it.id }
        val sb = StringBuilder("<text:tracked-changes>")
        for ((id, info) in used) {
            val author = meta[id]?.author ?: "Unknown"
            val date = meta[id]?.date ?: ""
            val elem = if (info.kind == "deletion") "deletion" else "insertion"
            sb.append("""<text:changed-region xml:id="${esc(id)}" text:id="${esc(id)}">""")
            sb.append("<text:$elem><office:change-info>")
            sb.append("<dc:creator>${esc(author)}</dc:creator>")
            if (date.isNotEmpty()) sb.append("<dc:date>${esc(date)}</dc:date>")
            sb.append("</office:change-info>")
            if (info.kind == "deletion") for (line in info.deletedText.split("\n")) sb.append("<text:p>${esc(line)}</text:p>")
            sb.append("</text:$elem></text:changed-region>")
        }
        sb.append("</text:tracked-changes>")
        return sb.toString()
    }

    private fun serializeTableOfContents(
        sb: StringBuilder, toc: OdfContentBlock.TableOfContents,
        styles: MutableMap<String, SpanStyleDef>,
        paraStyles: MutableMap<String, ParaStyleDef>
    ) {
        sb.append("""<text:table-of-content text:name="${esc(toc.title)}1" text:protected="true">""")
        sb.append("""<text:table-of-content-source text:outline-level="4" text:use-outline-level="true">""")
        sb.append("""<text:index-title-template text:style-name="Contents_20_Heading">${esc(toc.title)}</text:index-title-template>""")
        for (lvl in 1..4) {
            sb.append("""<text:table-of-content-entry-template text:outline-level="$lvl" text:style-name="Contents_20_$lvl">""")
            sb.append("""<text:index-entry-chapter/><text:index-entry-text/>""")
            sb.append("""<text:index-entry-tab-stop style:type="right" style:leader-char="."/>""")
            sb.append("""<text:index-entry-page-number/>""")
            sb.append("</text:table-of-content-entry-template>")
        }
        sb.append("</text:table-of-content-source>")
        sb.append("<text:index-body>")
        sb.append("""<text:index-title text:name="${esc(toc.title)}1_Head">""")
        sb.append("""<text:p text:style-name="Contents_20_Heading">${esc(toc.title)}</text:p>""")
        sb.append("</text:index-title>")
        for (entry in toc.entries) serializeParagraph(sb, entry, styles, paraStyles, "text:p")
        sb.append("</text:index-body>")
        sb.append("</text:table-of-content>")
    }

    private fun footnoteForSpan(span: OdfSpan, footnotes: Map<String, OdfFootnote>): OdfFootnote? {
        if (footnotes.isEmpty() || span.annotation != null || span.text.isBlank()) return null
        val t = span.text.trim()
        val bracketed = t.startsWith("[") && t.endsWith("]") && t.length >= 3
        if (!span.superscript && !bracketed) return null
        val key = if (bracketed) t.substring(1, t.length - 1) else t
        return footnotes[key]
    }

    private fun serializeTable(
        sb: StringBuilder, table: OdfTable,
        styles: MutableMap<String, SpanStyleDef>,
        paraStyles: MutableMap<String, ParaStyleDef>,
        colStyles: MutableMap<String, Float>,
        cellStyles: MutableMap<String, CellStyleDef>
    ) {
        sb.append("""<table:table table:name="${esc(table.name)}">""")
        for (col in table.columns) {
            val w = col.width
            if (w != null && w > 0f) sb.append("""<table:table-column table:style-name="${getOrCreateColStyle(w, colStyles)}"/>""")
            else sb.append("<table:table-column/>")
        }
        val headerN = table.headerRowCount.coerceIn(0, table.rows.size)
        if (headerN > 0) sb.append("<table:table-header-rows>")
        table.rows.forEachIndexed { ri, row ->
            if (ri == headerN && headerN > 0) sb.append("</table:table-header-rows>")
            sb.append("<table:table-row>")
            for (cell in row.cells) {
                if (cell.isCovered) {
                    sb.append("<table:covered-table-cell/>")
                } else {
                    sb.append("<table:table-cell")
                    val cs = if (cell.backgroundColor != null || cell.borderColor != null || cell.verticalAlign != null) {
                        val def = CellStyleDef(backgroundColor = cell.backgroundColor, borderColor = cell.borderColor, verticalAlign = cell.verticalAlign)
                        cellStyles.entries.firstOrNull { it.value == def }?.key ?: "ce${cellStyles.size + 1}".also { cellStyles[it] = def }
                    } else null
                    if (cs != null) sb.append(""" table:style-name="$cs"""")
                    if (cell.colSpan > 1) sb.append(""" table:number-columns-spanned="${cell.colSpan}"""")
                    if (cell.rowSpan > 1) sb.append(""" table:number-rows-spanned="${cell.rowSpan}"""")
                    if (cell.formula != null) sb.append(""" table:formula="${esc(cell.formula)}"""")
                    sb.append(">")
                    for (para in cell.paragraphs) serializeParagraph(sb, para, styles, paraStyles, "text:p")
                    sb.append("</table:table-cell>")
                }
            }
            sb.append("</table:table-row>")
        }
        if (headerN > 0 && headerN >= table.rows.size) sb.append("</table:table-header-rows>")
        sb.append("</table:table>")
    }

    private fun serializeImageRef(sb: StringBuilder, image: OdfImage, graphicStyles: MutableMap<String, GraphicStyleDef>, ctx: SerCtx) {
        val href = resolveImageHref(image, ctx) ?: run {
            if (!ctx.flat) return
            // Flat export with no path: emit base64 binary-data.
            sb.append("""<draw:frame""")
            if (image.width > 0) sb.append(""" svg:width="${cm(image.width)}"""")
            if (image.height > 0) sb.append(""" svg:height="${cm(image.height)}"""")
            appendRotation(sb, image)
            sb.append("""><draw:image><office:binary-data>${android.util.Base64.encodeToString(image.imageData, android.util.Base64.NO_WRAP)}</office:binary-data></draw:image>""")
            sb.append(altTextXml(image))
            sb.append("</draw:frame>")
            return
        }
        sb.append("""<draw:frame""")
        val op = if (image.opacityPercent in 0f..99.9f) image.opacityPercent else null
        getOrCreateGraphicStyle(null, null, null, clipString(image), graphicStyles, op, image.colorMode)?.let { sb.append(""" draw:style-name="$it"""") }
        if (image.anchorType.isNotEmpty()) sb.append(""" text:anchor-type="${esc(image.anchorType)}"""")
        if (image.width > 0) sb.append(""" svg:width="${cm(image.width)}"""")
        if (image.height > 0) sb.append(""" svg:height="${cm(image.height)}"""")
        appendRotation(sb, image)
        sb.append("""><draw:image xlink:href="${esc(href)}" xlink:type="simple" xlink:actuate="onLoad"/>""")
        sb.append(altTextXml(image))
        sb.append("</draw:frame>")
    }

    /** Resolves the package href for an image, promoting inline images to the package. (A6) */
    private fun resolveImageHref(image: OdfImage, ctx: SerCtx): String? {
        if (image.path != "inline" && image.path.isNotBlank()) return image.path
        if (image.imageData.isEmpty()) return null
        if (ctx.flat) return null
        val path = ctx.nextImagePath(sniffExt(image.imageData))
        ctx.images[path] = image.imageData
        ctx.manifest[path] = mediaTypeForImage(path)
        return path
    }

    /** ODF fo:clip="rect(top right bottom left)" with absolute cm lengths relative to the natural image size. (A7) */
    private fun clipString(image: OdfImage): String? {
        if (image.cropLeftPct <= 0f && image.cropTopPct <= 0f && image.cropRightPct <= 0f && image.cropBottomPct <= 0f) return null
        val wPx = if (image.naturalWidthPx > 0f) image.naturalWidthPx else image.width
        val hPx = if (image.naturalHeightPx > 0f) image.naturalHeightPx else image.height
        if (wPx <= 0f || hPx <= 0f) return null
        val top = cm(image.cropTopPct * hPx)
        val right = cm(image.cropRightPct * wPx)
        val bottom = cm(image.cropBottomPct * hPx)
        val left = cm(image.cropLeftPct * wPx)
        return "rect($top $right $bottom $left)"
    }

    /** svg:title / svg:desc accessibility children for an image frame. (Phase 2) */
    private fun altTextXml(image: OdfImage): String {
        val sb = StringBuilder()
        image.altTitle?.let { sb.append("<svg:title>${esc(it)}</svg:title>") }
        image.altDesc?.let { sb.append("<svg:desc>${esc(it)}</svg:desc>") }
        return sb.toString()
    }

    private fun appendRotation(sb: StringBuilder, image: OdfImage) {
        if (image.rotationDegrees != 0f) {
            // ODF rotation is counter-clockwise radians; our model stores clockwise degrees. (B1)
            val rad = (-image.rotationDegrees.toDouble() * Math.PI / 180.0)
            sb.append(""" draw:transform="rotate(${String.format(Locale.US, "%.5f", rad)})"""")
        }
    }

    private fun serializeChartFrame(
        sb: StringBuilder, chart: OdfChart, x: Float, y: Float, w: Float, h: Float, anchor: String,
        styles: MutableMap<String, SpanStyleDef>, paraStyles: MutableMap<String, ParaStyleDef>, ctx: SerCtx
    ) {
        if (ctx.flat) {
            // Flat export: best-effort text summary.
            sb.append("<draw:frame")
            if (anchor.isNotEmpty()) sb.append(""" text:anchor-type="$anchor"""")
            sb.append(""" svg:width="${cm(w)}" svg:height="${cm(h)}"><draw:text-box>""")
            serializeParagraph(sb, OdfParagraph(listOf(OdfSpan(text = chartSummary(chart)))), styles, paraStyles, "text:p")
            sb.append("</draw:text-box></draw:frame>")
            return
        }
        val dir = ctx.nextObjectDir()
        ctx.objects["$dir/content.xml"] = generateChartXml(chart, w, h)
        ctx.manifest["$dir/"] = "application/vnd.oasis.opendocument.chart"
        ctx.manifest["$dir/content.xml"] = "text/xml"
        sb.append("<draw:frame")
        if (anchor.isNotEmpty()) sb.append(""" text:anchor-type="$anchor"""")
        if (x != 0f || y != 0f) sb.append(""" svg:x="${cm(x)}" svg:y="${cm(y)}"""")
        sb.append(""" svg:width="${cm(w)}" svg:height="${cm(h)}">""")
        sb.append("""<draw:object xlink:href="./$dir" xlink:type="simple" xlink:show="embed" xlink:actuate="onLoad"/>""")
        sb.append("</draw:frame>")
    }

    private fun chartSummary(chart: OdfChart): String {
        val series = chart.series.joinToString(", ") { it.name }
        return "[Chart: ${chart.type.name.lowercase()}] ${chart.title ?: ""} ${if (series.isNotEmpty()) "($series)" else ""}".trim()
    }

    private fun serializeFrame(
        sb: StringBuilder, frame: OdfFrame,
        styles: MutableMap<String, SpanStyleDef>,
        paraStyles: MutableMap<String, ParaStyleDef>,
        graphicStyles: MutableMap<String, GraphicStyleDef>,
        ctx: SerCtx
    ) {
        // Charts become embedded objects in a frame of their own. (A8)
        if (frame.chart != null) {
            serializeChartFrame(sb, frame.chart, frame.x, frame.y, frame.width, frame.height, "", styles, paraStyles, ctx)
            return
        }
        sb.append("<draw:frame")
        val clip = frame.image?.let { clipString(it) }
        val imgOp = frame.image?.let { if (it.opacityPercent in 0f..99.9f) it.opacityPercent else null }
        getOrCreateGraphicStyle(frame.fillColor, frame.strokeColor, frame.strokeWidth, clip, graphicStyles, opacity = imgOp, colorMode = frame.image?.colorMode, gradient = frame.fillGradient)?.let { sb.append(""" draw:style-name="$it"""") }
        sb.append(""" svg:x="${cm(frame.x)}" svg:y="${cm(frame.y)}"""")
        sb.append(""" svg:width="${cm(frame.width)}" svg:height="${cm(frame.height)}"""")
        frame.image?.let { appendRotation(sb, it) }
        sb.append(">")
        if (frame.image != null) {
            val href = resolveImageHref(frame.image, ctx)
            if (href != null) {
                sb.append("""<draw:image xlink:href="${esc(href)}" xlink:type="simple"/>""")
            } else if (ctx.flat && frame.image.imageData.isNotEmpty()) {
                sb.append("""<draw:image><office:binary-data>${android.util.Base64.encodeToString(frame.image.imageData, android.util.Base64.NO_WRAP)}</office:binary-data></draw:image>""")
            }
        }
        if (frame.paragraphs.isNotEmpty()) {
            sb.append("<draw:text-box>")
            for (para in frame.paragraphs) serializeParagraph(sb, para, styles, paraStyles, "text:p")
            sb.append("</draw:text-box>")
        }
        frame.image?.let { sb.append(altTextXml(it)) }
        sb.append("</draw:frame>")
    }

    private fun appendShapeRotation(sb: StringBuilder, rot: Float) {
        if (rot != 0f) {
            val rad = (-rot.toDouble() * Math.PI / 180.0)
            sb.append(""" draw:transform="rotate(${String.format(Locale.US, "%.5f", rad)})"""")
        }
    }

    private fun serializeShape(
        sb: StringBuilder, shape: OdfShape,
        styles: MutableMap<String, SpanStyleDef>,
        paraStyles: MutableMap<String, ParaStyleDef>,
        graphicStyles: MutableMap<String, GraphicStyleDef>
    ) {
        if (shape is OdfShape.Polyline) {
            // Polyline/polygon: emit a viewBox + draw:points mapping of the absolute px vertices. (Priority 8)
            val ptag = if (shape.closed) "draw:polygon" else "draw:polyline"
            sb.append("<$ptag")
            getOrCreateGraphicStyle(shape.fillColor, shape.strokeColor, shape.strokeWidth, null, graphicStyles, gradient = shape.fillGradient, strokeDashed = shape.strokeDashed)?.let { sb.append(""" draw:style-name="$it"""") }
            sb.append(""" svg:x="${cm(shape.x)}" svg:y="${cm(shape.y)}" svg:width="${cm(shape.width)}" svg:height="${cm(shape.height)}"""")
            appendShapeRotation(sb, shape.rotationDegrees)
            sb.append(""" svg:viewBox="0 0 10000 10000"""")
            val pts = shape.points.joinToString(" ") { (px, py) ->
                val vx = if (shape.width != 0f) Math.round((px - shape.x) / shape.width * 10000f) else 0
                val vy = if (shape.height != 0f) Math.round((py - shape.y) / shape.height * 10000f) else 0
                "$vx,$vy"
            }
            sb.append(""" draw:points="$pts">""")
            for (para in shape.text) serializeParagraph(sb, para, styles, paraStyles, "text:p")
            sb.append("</$ptag>")
            return
        }
        val tag = when (shape) {
            is OdfShape.Rect -> "draw:rect"
            is OdfShape.Ellipse -> "draw:ellipse"
            is OdfShape.Line -> "draw:line"
            is OdfShape.CustomShape -> "draw:custom-shape"
            is OdfShape.Polyline -> "draw:polyline"
        }
        sb.append("<$tag")
        getOrCreateGraphicStyle(shape.fillColor, shape.strokeColor, shape.strokeWidth, null, graphicStyles, gradient = shape.fillGradient, strokeDashed = shape.strokeDashed, markerStart = shape.markerStart, markerEnd = shape.markerEnd)?.let { sb.append(""" draw:style-name="$it"""") }
        if (shape is OdfShape.Line) {
            // Lines use endpoint coordinates, not a bounding box. (A5)
            val x2 = if (shape.x2 != 0f || shape.y2 != 0f) shape.x2 else shape.x + shape.width
            val y2 = if (shape.x2 != 0f || shape.y2 != 0f) shape.y2 else shape.y + shape.height
            sb.append(""" svg:x1="${cm(shape.x)}" svg:y1="${cm(shape.y)}"""")
            sb.append(""" svg:x2="${cm(x2)}" svg:y2="${cm(y2)}"""")
        } else {
            sb.append(""" svg:x="${cm(shape.x)}" svg:y="${cm(shape.y)}"""")
            sb.append(""" svg:width="${cm(shape.width)}" svg:height="${cm(shape.height)}"""")
            appendShapeRotation(sb, shape.rotationDegrees)
            if (shape is OdfShape.Rect && shape.cornerRadius > 0f) sb.append(""" draw:corner-radius="${cm(shape.cornerRadius)}"""")
        }
        sb.append(">")
        for (para in shape.text) serializeParagraph(sb, para, styles, paraStyles, "text:p")
        sb.append("</$tag>")
    }

    // --- Embedded chart object generation (A8) ---

    private fun generateChartXml(chart: OdfChart, wPx: Float, hPx: Float): String {
        val stacked = chart.stacked || chart.type == ChartType.STACKED_BAR
        val chartClass = when (chart.type) {
            ChartType.LINE -> "chart:line"
            ChartType.PIE -> "chart:circle"
            ChartType.DONUT -> "chart:ring"
            ChartType.AREA -> "chart:area"
            ChartType.SCATTER -> "chart:scatter"
            ChartType.RADAR -> "chart:radar"
            ChartType.BUBBLE -> "chart:bubble"
            else -> "chart:bar"
        }
        val rowCount = chart.categories.size
        val lastRow = rowCount + 1 // header is row 1
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.append("""<office:document-content""")
        sb.append(""" xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"""")
        sb.append(""" xmlns:chart="urn:oasis:names:tc:opendocument:xmlns:chart:1.0"""")
        sb.append(""" xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0"""")
        sb.append(""" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"""")
        sb.append(""" xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0"""")
        sb.append(""" xmlns:xlink="http://www.w3.org/1999/xlink"""")
        sb.append(""" xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0"""")
        sb.append(""" xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"""")
        sb.append(""" office:version="1.3">""")
        // Chart automatic styles: plot-area stacking + per-series color / data labels. (Phase 5)
        sb.append("<office:automatic-styles>")
        sb.append("""<style:style style:name="plotstyle" style:family="chart"><style:chart-properties""")
        if (stacked) sb.append(""" chart:stacked="true"""")
        sb.append("/></style:style>")
        chart.series.forEachIndexed { i, s ->
            sb.append("""<style:style style:name="series$i" style:family="chart">""")
            sb.append("<style:chart-properties")
            if (s.dataLabels) sb.append(""" chart:data-label-number="value"""")
            sb.append("/>")
            if (s.color != null) sb.append("""<style:graphic-properties draw:fill="solid" draw:fill-color="${formatColor(s.color)}"/>""")
            sb.append("</style:style>")
        }
        sb.append("</office:automatic-styles>")
        sb.append("<office:body><office:chart>")
        sb.append("""<chart:chart chart:class="$chartClass" svg:width="${cm(wPx)}" svg:height="${cm(hPx)}">""")
        chart.title?.let { sb.append("""<chart:title><text:p>${esc(it)}</text:p></chart:title>""") }
        chart.subtitle?.let { sb.append("""<chart:subtitle><text:p>${esc(it)}</text:p></chart:subtitle>""") }
        if (chart.legend) sb.append("""<chart:legend chart:legend-position="end"/>""")
        sb.append("<chart:plot-area chart:style-name=\"plotstyle\">")
        sb.append("""<chart:axis chart:dimension="x" chart:name="primary-x">""")
        chart.xAxisTitle?.let { sb.append("""<chart:title><text:p>${esc(it)}</text:p></chart:title>""") }
        sb.append("</chart:axis>")
        sb.append("""<chart:axis chart:dimension="y" chart:name="primary-y">""")
        chart.yAxisTitle?.let { sb.append("""<chart:title><text:p>${esc(it)}</text:p></chart:title>""") }
        sb.append("</chart:axis>")
        chart.series.forEachIndexed { i, s ->
            val col = columnLetter(i + 1) // series start at column B
            sb.append("""<chart:series chart:style-name="series$i" chart:values-cell-range-address="local-table.${'$'}$col${'$'}2:.${'$'}$col${'$'}$lastRow" chart:label-cell-address="local-table.${'$'}$col${'$'}1" chart:class="$chartClass">""")
            if (i == 0) sb.append("""<chart:categories table:cell-range-address="local-table.${'$'}A${'$'}2:.${'$'}A${'$'}$lastRow"/>""")
            sb.append("</chart:series>")
        }
        sb.append("</chart:plot-area>")
        // local-table mirroring categories + series values.
        sb.append("""<table:table table:name="local-table">""")
        sb.append("<table:table-header-rows><table:table-row><table:table-cell/>")
        for (s in chart.series) sb.append("""<table:table-cell office:value-type="string"><text:p>${esc(s.name)}</text:p></table:table-cell>""")
        sb.append("</table:table-row></table:table-header-rows>")
        sb.append("<table:table-rows>")
        for (r in 0 until rowCount) {
            sb.append("<table:table-row>")
            sb.append("""<table:table-cell office:value-type="string"><text:p>${esc(chart.categories[r])}</text:p></table:table-cell>""")
            for (s in chart.series) {
                val v = s.values.getOrNull(r) ?: 0f
                sb.append("""<table:table-cell office:value-type="float" office:value="$v"><text:p>$v</text:p></table:table-cell>""")
            }
            sb.append("</table:table-row>")
        }
        sb.append("</table:table-rows></table:table>")
        sb.append("</chart:chart></office:chart></office:body></office:document-content>")
        return sb.toString()
    }

    private fun columnLetter(index: Int): String {
        val sb = StringBuilder(); var n = index
        do { sb.insert(0, ('A' + n % 26)); n = n / 26 - 1 } while (n >= 0)
        return sb.toString()
    }

    // --- Style management ---

    private data class SpanStyleDef(
        val bold: Boolean = false, val italic: Boolean = false,
        val underline: Boolean = false, val strikethrough: Boolean = false,
        val color: Long? = null, val fontSize: Float? = null,
        val superscript: Boolean = false, val subscript: Boolean = false,
        val fontFamily: String? = null,
        val underlineStyle: String? = null, val underlineColor: Long? = null,
        val letterSpacing: Float? = null, val textTransform: String? = null,
        val language: String? = null, val country: String? = null
    )

    private data class ParaStyleDef(
        val alignment: TextAlign? = null,
        val marginLeft: Float = 0f,
        val marginTop: Float = 0f,
        val marginBottom: Float = 0f,
        val textIndent: Float = 0f,
        val lineHeightPercent: Float? = null,
        val borderColor: Long? = null,
        val backgroundColor: Long? = null,
        val breakBefore: Boolean = false,
        val tabStops: List<Float> = emptyList(),
        val borders: OdfBorders? = null,
        val marginRight: Float = 0f,
        val keepWithNext: Boolean = false,
        val keepTogether: Boolean = false,
        val widows: Int? = null,
        val orphans: Int? = null,
        val tabStopDetails: List<OdfTabStop> = emptyList(),
        val dropCapLines: Int = 0,
        val dropCapLength: Int = 1,
        val padding: Float = 0f,
        val direction: androidx.compose.ui.unit.LayoutDirection? = null
    )

    private data class CellStyleDef(
        val backgroundColor: Long? = null, val textColor: Long? = null,
        val bold: Boolean = false, val italic: Boolean = false,
        val alignment: TextAlign? = null, val borderColor: Long? = null,
        val wrap: Boolean = false, val numberFormat: OdfNumberFormat? = null,
        val borders: OdfBorders? = null,
        // Conditional-format maps (condition, bg, text) re-emitted as style:map. (Round 3)
        val condMaps: List<Triple<String, Long?, Long?>> = emptyList(),
        val verticalAlign: String? = null
    )

    private data class GraphicStyleDef(
        val fillColor: Long? = null, val strokeColor: Long? = null, val strokeWidth: Float? = null,
        val clip: String? = null, val opacity: Float? = null, val colorMode: String? = null,
        val gradient: OdfGradient? = null,
        val strokeDashed: Boolean = false, val markerStart: Boolean = false, val markerEnd: Boolean = false
    )

    private fun getOrCreateGraphicStyle(fillColor: Long?, strokeColor: Long?, strokeWidth: Float?, clip: String?, styles: MutableMap<String, GraphicStyleDef>, opacity: Float? = null, colorMode: String? = null, gradient: OdfGradient? = null, strokeDashed: Boolean = false, markerStart: Boolean = false, markerEnd: Boolean = false): String? {
        if (fillColor == null && strokeColor == null && strokeWidth == null && clip == null && opacity == null && colorMode == null && gradient == null && !strokeDashed && !markerStart && !markerEnd) return null
        val def = GraphicStyleDef(fillColor, strokeColor, strokeWidth, clip, opacity, colorMode, gradient, strokeDashed, markerStart, markerEnd)
        for ((name, existing) in styles) if (existing == def) return name
        val name = "gr${styles.size + 1}"
        styles[name] = def
        return name
    }

    private fun getOrCreateDrawPageStyle(fillColor: Long?, transitionType: String?, transitionSpeed: String?, styles: MutableMap<String, DrawPageStyleDef>): String? {
        if (fillColor == null && transitionType == null && transitionSpeed == null) return null
        val def = DrawPageStyleDef(fillColor, transitionType, transitionSpeed)
        for ((name, existing) in styles) if (existing == def) return name
        val name = "dp${styles.size + 1}"
        styles[name] = def
        return name
    }

    private fun getOrCreateCellStyle(cell: OdfCell, styles: MutableMap<String, CellStyleDef>): String? {
        if (cell.backgroundColor == null && cell.textColor == null && !cell.bold && !cell.italic &&
            cell.alignment == null && cell.borderColor == null && cell.borders == null && !cell.wrap &&
            cell.numberFormat == null && cell.condFormats.isEmpty()) return null
        val condMaps = cell.condFormats.map { Triple(it.condition, it.backgroundColor, it.textColor) }
        val def = CellStyleDef(cell.backgroundColor, cell.textColor, cell.bold, cell.italic, cell.alignment, cell.borderColor, cell.wrap, cell.numberFormat, cell.borders, condMaps)
        for ((name, existing) in styles) if (existing == def) return name
        val name = "ce${styles.size + 1}"
        styles[name] = def
        return name
    }

    private fun getOrCreateColStyle(width: Float, styles: MutableMap<String, Float>): String {
        for ((name, existing) in styles) if (existing == width) return name
        val name = "co${styles.size + 1}"
        styles[name] = width
        return name
    }

    private fun getOrCreateSpanStyle(span: OdfSpan, styles: MutableMap<String, SpanStyleDef>): String {
        val def = SpanStyleDef(span.bold, span.italic, span.underline, span.strikethrough,
            span.color, span.fontSize, span.superscript, span.subscript, span.fontFamily,
            span.underlineStyle, span.underlineColor, span.letterSpacing, span.textTransform, span.language, span.country)
        for ((name, existing) in styles) if (existing == def) return name
        val name = "T${styles.size + 1}"
        styles[name] = def
        return name
    }

    private data class ListStyleDef(
        val type: ListType, val format: String, val bullet: String, val prefix: String, val suffix: String
    )

    private fun getOrCreateListStyle(para: OdfParagraph, styles: MutableMap<String, ListStyleDef>): String {
        val def = ListStyleDef(para.listType, para.listNumberFormat, para.listBulletChar, para.listNumberPrefix, para.listNumberSuffix)
        for ((name, existing) in styles) if (existing == def) return name
        val name = "L${styles.size + 1}"
        styles[name] = def
        return name
    }

    private fun getOrCreateParaStyle(para: OdfParagraph, styles: MutableMap<String, ParaStyleDef>): String? {
        val hasProps = para.alignment != null || para.marginLeft != 0f || para.textIndent != 0f ||
            para.lineHeightPercent != null || para.borderColor != null || para.borders != null || para.backgroundColor != null ||
            para.marginTop != 0f || para.marginBottom != 0f || para.tabStops.isNotEmpty() || para.tabStopDetails.isNotEmpty() ||
            para.dropCapLines > 0 ||
            para.padding != 0f || para.direction != null ||
            para.marginRight != 0f || para.keepWithNext || para.keepTogether || para.widows != null || para.orphans != null
        if (!hasProps && !para.breakBeforePage) return null
        val def = ParaStyleDef(para.alignment, para.marginLeft, para.marginTop, para.marginBottom, para.textIndent,
            para.lineHeightPercent, para.borderColor, para.backgroundColor, para.breakBeforePage, para.tabStops, para.borders,
            para.marginRight, para.keepWithNext, para.keepTogether, para.widows, para.orphans,
            para.tabStopDetails, para.dropCapLines, para.dropCapLength, para.padding, para.direction)
        for ((name, existing) in styles) if (existing == def) return name
        val name = "P${styles.size + 1}"
        styles[name] = def
        return name
    }

    // --- XML construction ---

    private fun buildDocument(
        bodyType: String,
        spanStyles: Map<String, SpanStyleDef>,
        paraStyles: Map<String, ParaStyleDef>,
        cellStyles: Map<String, CellStyleDef>,
        colStyles: Map<String, Float>,
        graphicStyles: Map<String, GraphicStyleDef>,
        bodyContent: String,
        drawPageStyles: Map<String, DrawPageStyleDef> = emptyMap(),
        rowStyles: Map<String, Float> = emptyMap(),
        sectionStyles: Map<String, Int> = emptyMap(),
        listStyles: Map<String, ListStyleDef> = emptyMap()
    ): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.append("""<office:document-content""")
        sb.append(""" xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"""")
        sb.append(""" xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0"""")
        sb.append(""" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"""")
        sb.append(""" xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0"""")
        sb.append(""" xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"""")
        sb.append(""" xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"""")
        sb.append(""" xmlns:xlink="http://www.w3.org/1999/xlink"""")
        sb.append(""" xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0"""")
        sb.append(""" xmlns:dc="http://purl.org/dc/elements/1.1/"""")
        sb.append(""" xmlns:meta="urn:oasis:names:tc:opendocument:xmlns:meta:1.0"""")
        sb.append(""" xmlns:presentation="urn:oasis:names:tc:opendocument:xmlns:presentation:1.0"""")
        sb.append(""" xmlns:number="urn:oasis:names:tc:opendocument:xmlns:datastyle:1.0"""")
        sb.append(""" xmlns:loext="urn:org:documentfoundation:names:experimental:office:xmlns:loext:1.0"""")
        sb.append(""" office:version="1.3">""")

        // Declare fonts referenced by span styles (content.xml font-face-decls). (Round 3 / Tier 4)
        val fontNames = spanStyles.values.mapNotNull { it.fontFamily }.distinct()
        if (fontNames.isNotEmpty()) {
            sb.append("<office:font-face-decls>")
            for (fn in fontNames) sb.append("""<style:font-face style:name="${esc(fn)}" svg:font-family="${esc(fn)}"/>""")
            sb.append("</office:font-face-decls>")
        }

        sb.append("<office:automatic-styles>")
        for ((name, def) in spanStyles) {
            sb.append("""<style:style style:name="$name" style:family="text"><style:text-properties""")
            if (def.bold) sb.append(""" fo:font-weight="bold"""")
            if (def.italic) sb.append(""" fo:font-style="italic"""")
            if (def.underline) {
                val ulStyle = def.underlineStyle ?: "solid"
                sb.append(""" style:text-underline-style="$ulStyle" style:text-underline-width="auto"""")
                if (def.underlineColor != null) sb.append(""" style:text-underline-color="${formatColor(def.underlineColor)}"""")
                else sb.append(""" style:text-underline-color="font-color"""")
            }
            if (def.strikethrough) sb.append(""" style:text-line-through-style="solid"""")
            if (def.color != null) sb.append(""" fo:color="${formatColor(def.color)}"""")
            if (def.fontSize != null) sb.append(""" fo:font-size="${def.fontSize}pt"""")
            if (def.fontFamily != null) sb.append(""" style:font-name="${esc(def.fontFamily)}"""")
            if (def.superscript) sb.append(""" style:text-position="super 58%"""")
            if (def.subscript) sb.append(""" style:text-position="sub 58%"""")
            if (def.letterSpacing != null) sb.append(""" fo:letter-spacing="${def.letterSpacing}pt"""")
            if (def.textTransform != null) sb.append(""" fo:text-transform="${def.textTransform}"""")
            if (def.language != null) sb.append(""" fo:language="${esc(def.language)}"""")
            if (def.country != null) sb.append(""" fo:country="${esc(def.country)}"""")
            sb.append("/></style:style>")
        }
        for ((name, def) in paraStyles) {
            sb.append("""<style:style style:name="$name" style:family="paragraph"><style:paragraph-properties""")
            when (def.alignment) {
                TextAlign.Start, TextAlign.Left -> sb.append(""" fo:text-align="start"""")
                TextAlign.Center -> sb.append(""" fo:text-align="center"""")
                TextAlign.End, TextAlign.Right -> sb.append(""" fo:text-align="end"""")
                TextAlign.Justify -> sb.append(""" fo:text-align="justify"""")
                else -> {}
            }
            if (def.marginLeft != 0f) sb.append(""" fo:margin-left="${cm(def.marginLeft)}"""")
            if (def.marginRight != 0f) sb.append(""" fo:margin-right="${cm(def.marginRight)}"""")
            if (def.marginTop != 0f) sb.append(""" fo:margin-top="${cm(def.marginTop)}"""")
            if (def.marginBottom != 0f) sb.append(""" fo:margin-bottom="${cm(def.marginBottom)}"""")
            if (def.textIndent != 0f) sb.append(""" fo:text-indent="${cm(def.textIndent)}"""")
            if (def.padding != 0f) sb.append(""" fo:padding="${cm(def.padding)}"""")
            if (def.lineHeightPercent != null) sb.append(""" fo:line-height="${(def.lineHeightPercent * 100).toInt()}%"""")
            if (def.keepWithNext) sb.append(""" fo:keep-with-next="always"""")
            if (def.keepTogether) sb.append(""" fo:keep-together="always"""")
            if (def.widows != null) sb.append(""" fo:widows="${def.widows}"""")
            if (def.orphans != null) sb.append(""" fo:orphans="${def.orphans}"""")
            if (def.borders != null) sb.append(borderAttrs(def.borders))
            else if (def.borderColor != null) sb.append(""" fo:border="0.5pt solid ${formatColor(def.borderColor)}"""")
            if (def.backgroundColor != null) sb.append(""" fo:background-color="${formatColor(def.backgroundColor)}"""")
            if (def.breakBefore) sb.append(""" fo:break-before="page"""")
            when (def.direction) {
                androidx.compose.ui.unit.LayoutDirection.Rtl -> sb.append(""" style:writing-mode="rl-tb"""")
                androidx.compose.ui.unit.LayoutDirection.Ltr -> sb.append(""" style:writing-mode="lr-tb"""")
                else -> {}
            }
            val hasTabs = def.tabStopDetails.isNotEmpty() || def.tabStops.isNotEmpty()
            if (hasTabs || def.dropCapLines > 0) {
                sb.append(">")
                if (hasTabs) {
                    sb.append("<style:tab-stops>")
                    if (def.tabStopDetails.isNotEmpty()) {
                        for (t in def.tabStopDetails) {
                            sb.append("""<style:tab-stop style:position="${cm(t.position)}"""")
                            t.type?.let { sb.append(""" style:type="${esc(it)}"""") }
                            t.leaderChar?.let { sb.append(""" style:leader-char="${esc(it)}"""") }
                            sb.append("/>")
                        }
                    } else {
                        for (t in def.tabStops) sb.append("""<style:tab-stop style:position="${cm(t)}"/>""")
                    }
                    sb.append("</style:tab-stops>")
                }
                if (def.dropCapLines > 0) sb.append("""<style:drop-cap style:lines="${def.dropCapLines}" style:length="${def.dropCapLength}"/>""")
                sb.append("</style:paragraph-properties></style:style>")
                continue
            }
            sb.append("/></style:style>")
        }
        for ((name, width) in colStyles) {
            sb.append("""<style:style style:name="$name" style:family="table-column"><style:table-column-properties style:column-width="${cm(width)}"/></style:style>""")
        }
        for ((name, height) in rowStyles) {
            sb.append("""<style:style style:name="$name" style:family="table-row"><style:table-row-properties style:row-height="${cm(height)}" style:use-optimal-row-height="false"/></style:style>""")
        }
        for ((name, cols) in sectionStyles) {
            sb.append("""<style:style style:name="$name" style:family="section"><style:section-properties><style:columns fo:column-count="$cols" fo:column-gap="0.5cm"/></style:section-properties></style:style>""")
        }
        // List styles (text:list-style) so numbered formats / custom bullets survive round-trip.
        for ((name, def) in listStyles) {
            sb.append("""<text:list-style style:name="$name">""")
            for (lvl in 1..10) {
                val spacing = """<style:list-level-properties text:list-level-position-and-space-mode="label-alignment"><style:list-level-label-alignment text:label-followed-by="listtab" fo:margin-left="${cm(18f * lvl)}" fo:text-indent="-0.635cm"/></style:list-level-properties>"""
                if (def.type == ListType.BULLET || def.type == ListType.CHECKBOX) {
                    sb.append("""<text:list-level-style-bullet text:level="$lvl" text:bullet-char="${esc(def.bullet.ifEmpty { "\u2022" })}">$spacing</text:list-level-style-bullet>""")
                } else {
                    sb.append("""<text:list-level-style-number text:level="$lvl" style:num-format="${esc(def.format)}"""")
                    if (def.prefix.isNotEmpty()) sb.append(""" style:num-prefix="${esc(def.prefix)}"""")
                    if (def.suffix.isNotEmpty()) sb.append(""" style:num-suffix="${esc(def.suffix)}"""")
                    sb.append(">$spacing</text:list-level-style-number>")
                }
            }
            sb.append("</text:list-style>")
        }
        // Number/date/currency data styles for cells, emitted before the cell styles that reference them. (B6)
        val dataStyleNames = HashMap<String, String>()
        run {
            var n = 0
            for ((cellName, def) in cellStyles) {
                val fmt = def.numberFormat ?: continue
                n++
                val dsName = "N$n"
                dataStyleNames[cellName] = dsName
                sb.append(numberStyleXml(dsName, fmt))
            }
        }
        // Conditional-format target cell styles, emitted before the styles that reference them. (Round 3)
        val condTargets = LinkedHashMap<Pair<Long?, Long?>, String>()
        for ((_, def) in cellStyles) for ((_, bg, tc) in def.condMaps) {
            if (condTargets[bg to tc] == null) {
                val nm = "cf${condTargets.size + 1}"
                condTargets[bg to tc] = nm
                sb.append("""<style:style style:name="$nm" style:family="table-cell"><style:table-cell-properties""")
                if (bg != null) sb.append(""" fo:background-color="${formatColor(bg)}"""")
                sb.append("/>")
                if (tc != null) sb.append("""<style:text-properties fo:color="${formatColor(tc)}"/>""")
                sb.append("</style:style>")
            }
        }
        for ((name, def) in cellStyles) {
            sb.append("""<style:style style:name="$name" style:family="table-cell"""")
            dataStyleNames[name]?.let { sb.append(""" style:data-style-name="$it"""") }
            sb.append(">")
            sb.append("<style:table-cell-properties")
            if (def.backgroundColor != null) sb.append(""" fo:background-color="${formatColor(def.backgroundColor)}"""")
            if (def.borders != null) sb.append(borderAttrs(def.borders))
            else if (def.borderColor != null) sb.append(""" fo:border="0.5pt solid ${formatColor(def.borderColor)}"""")
            if (def.wrap) sb.append(""" fo:wrap-option="wrap"""")
            if (def.verticalAlign != null) sb.append(""" style:vertical-align="${def.verticalAlign}"""")
            sb.append("/>")
            when (def.alignment) {
                TextAlign.Start, TextAlign.Left -> sb.append("""<style:paragraph-properties fo:text-align="start"/>""")
                TextAlign.Center -> sb.append("""<style:paragraph-properties fo:text-align="center"/>""")
                TextAlign.End, TextAlign.Right -> sb.append("""<style:paragraph-properties fo:text-align="end"/>""")
                TextAlign.Justify -> sb.append("""<style:paragraph-properties fo:text-align="justify"/>""")
                else -> {}
            }
            sb.append("<style:text-properties")
            if (def.bold) sb.append(""" fo:font-weight="bold"""")
            if (def.italic) sb.append(""" fo:font-style="italic"""")
            if (def.textColor != null) sb.append(""" fo:color="${formatColor(def.textColor)}"""")
            sb.append("/>")
            for ((cond, bg, tc) in def.condMaps) {
                val target = condTargets[bg to tc] ?: continue
                sb.append("""<style:map style:condition="${esc(cond)}" style:apply-style-name="$target"/>""")
            }
            sb.append("</style:style>")
        }
        // draw:gradient definitions referenced by graphic styles. (Round 3)
        val gradNames = LinkedHashMap<OdfGradient, String>()
        for ((_, def) in graphicStyles) def.gradient?.let { g ->
            if (gradNames[g] == null) {
                val gn = "grad${gradNames.size + 1}"
                gradNames[g] = gn
                val ang = Math.round(g.angle * 10)  // ODF angle in 1/10 degree
                sb.append("""<draw:gradient draw:name="$gn" draw:style="${esc(g.style)}" draw:start-color="${formatColor(g.startColor)}" draw:end-color="${formatColor(g.endColor)}" draw:angle="$ang"/>""")
            }
        }
        // Shared dash + arrow marker definitions, emitted once if any style uses them. (Round 3)
        if (graphicStyles.values.any { it.strokeDashed }) {
            sb.append("""<draw:stroke-dash draw:name="aDash" draw:style="rect" draw:dots1="1" draw:dots1-length="0.2cm" draw:distance="0.2cm"/>""")
        }
        if (graphicStyles.values.any { it.markerStart || it.markerEnd }) {
            sb.append("""<draw:marker draw:name="aArrow" svg:viewBox="0 0 20 30" svg:d="m10 0-10 30h20z"/>""")
        }
        for ((name, def) in graphicStyles) {
            sb.append("""<style:style style:name="$name" style:family="graphic"><style:graphic-properties""")
            if (def.gradient != null) sb.append(""" draw:fill="gradient" draw:fill-gradient-name="${gradNames[def.gradient]}"""")
            else if (def.fillColor != null) sb.append(""" draw:fill="solid" draw:fill-color="${formatColor(def.fillColor)}"""")
            else sb.append(""" draw:fill="none"""")
            if (def.strokeColor != null) {
                sb.append(""" draw:stroke="${if (def.strokeDashed) "dash" else "solid"}" svg:stroke-color="${formatColor(def.strokeColor)}"""")
                if (def.strokeDashed) sb.append(""" draw:stroke-dash="aDash"""")
            }
            if (def.strokeWidth != null) sb.append(""" svg:stroke-width="${cm(def.strokeWidth)}"""")
            if (def.markerStart) sb.append(""" draw:marker-start="aArrow" draw:marker-start-width="0.3cm"""")
            if (def.markerEnd) sb.append(""" draw:marker-end="aArrow" draw:marker-end-width="0.3cm"""")
            if (def.clip != null) sb.append(""" fo:clip="${def.clip}"""")
            if (def.opacity != null) sb.append(""" draw:image-opacity="${def.opacity}%"""")
            if (def.colorMode != null) sb.append(""" draw:color-mode="${def.colorMode}"""")
            sb.append("/></style:style>")
        }
        for ((name, def) in drawPageStyles) {
            sb.append("""<style:style style:name="$name" style:family="drawing-page"><style:drawing-page-properties""")
            if (def.fillColor != null) sb.append(""" draw:fill="solid" draw:fill-color="${formatColor(def.fillColor)}"""")
            if (def.transitionType != null) sb.append(""" presentation:transition-style="${esc(def.transitionType)}"""")
            if (def.transitionSpeed != null) sb.append(""" presentation:transition-speed="${esc(def.transitionSpeed)}"""")
            sb.append("/></style:style>")
        }
        sb.append("</office:automatic-styles>")

        sb.append("<office:body><$bodyType>")
        sb.append(bodyContent)
        sb.append("</$bodyType></office:body>")
        sb.append("</office:document-content>")
        return sb.toString()
    }

    /** Re-emits preserved number:date-style / number:time-style component tokens in order. (Phase 4) */
    private fun dateTimeTokensXml(tokens: List<OdfNumberToken>): String {
        val sb = StringBuilder()
        for (t in tokens) {
            if (t.kind == "text") {
                sb.append("<number:text>${esc(t.text ?: "")}</number:text>")
            } else {
                sb.append("<number:${t.kind}")
                t.style?.let { sb.append(""" number:style="${esc(it)}"""") }
                if (t.textual) sb.append(""" number:textual="true"""")
                sb.append("/>")
            }
        }
        return sb.toString()
    }

    private fun numberStyleXml(name: String, fmt: OdfNumberFormat): String {
        val sb = StringBuilder()
        val dec = fmt.decimals ?: 2
        when {
            fmt.isDate -> {
                sb.append("""<number:date-style style:name="$name">""")
                if (fmt.dateTimeTokens.isNotEmpty()) sb.append(dateTimeTokensXml(fmt.dateTimeTokens))
                else {
                    sb.append("""<number:year number:style="long"/><number:text>-</number:text>""")
                    sb.append("""<number:month number:style="long"/><number:text>-</number:text>""")
                    sb.append("""<number:day number:style="long"/>""")
                }
                sb.append("</number:date-style>")
            }
            fmt.isTime -> {
                sb.append("""<number:time-style style:name="$name">""")
                if (fmt.dateTimeTokens.isNotEmpty()) sb.append(dateTimeTokensXml(fmt.dateTimeTokens))
                else {
                    sb.append("""<number:hours number:style="long"/><number:text>:</number:text>""")
                    sb.append("""<number:minutes number:style="long"/><number:text>:</number:text>""")
                    sb.append("""<number:seconds number:style="long"/>""")
                }
                sb.append("</number:time-style>")
            }
            fmt.isScientific -> {
                sb.append("""<number:number-style style:name="$name">""")
                sb.append("""<number:scientific-number number:decimal-places="$dec" number:min-integer-digits="1" number:min-exponent-digits="2"/>""")
                sb.append("</number:number-style>")
            }
            fmt.isFraction -> {
                val denom = fmt.fractionDenominatorDigits.coerceIn(1, 5)
                sb.append("""<number:number-style style:name="$name">""")
                sb.append("""<number:fraction number:min-integer-digits="0" number:min-numerator-digits="1" number:min-denominator-digits="$denom"/>""")
                sb.append("</number:number-style>")
            }
            fmt.currencySymbol != null -> {
                sb.append("""<number:currency-style style:name="$name">""")
                sb.append("""<number:currency-symbol>${esc(fmt.currencySymbol)}</number:currency-symbol>""")
                sb.append("""<number:number number:decimal-places="$dec" number:min-integer-digits="1"${if (fmt.grouping) " number:grouping=\"true\"" else ""}/>""")
                sb.append("</number:currency-style>")
            }
            fmt.percent -> {
                sb.append("""<number:percentage-style style:name="$name">""")
                sb.append("""<number:number number:decimal-places="$dec" number:min-integer-digits="1"/>""")
                sb.append("""<number:text>%</number:text>""")
                sb.append("</number:percentage-style>")
            }
            else -> {
                sb.append("""<number:number-style style:name="$name">""")
                sb.append("""<number:number number:decimal-places="$dec" number:min-integer-digits="1"${if (fmt.grouping) " number:grouping=\"true\"" else ""}/>""")
                sb.append("</number:number-style>")
            }
        }
        return sb.toString()
    }

    private fun formatColor(color: Long): String {
        val rgb = (color and 0xFFFFFFL).toInt()
        return String.format("#%06X", rgb)
    }

    /** Emits fo:border / fo:border-* attributes from raw per-edge border strings. */
    private fun borderAttrs(b: OdfBorders): String {
        val t = b.top; val r = b.right; val bo = b.bottom; val l = b.left
        if (t != null && t == r && t == bo && t == l) return """ fo:border="${esc(t)}""""
        val sb = StringBuilder()
        if (t != null) sb.append(""" fo:border-top="${esc(t)}"""")
        if (r != null) sb.append(""" fo:border-right="${esc(r)}"""")
        if (bo != null) sb.append(""" fo:border-bottom="${esc(bo)}"""")
        if (l != null) sb.append(""" fo:border-left="${esc(l)}"""")
        return sb.toString()
    }

    /** px@96 -> cm length string. */
    private fun cm(px: Float): String = String.format(Locale.US, "%.4fcm", px / 37.795f)

    private fun sniffExt(bytes: ByteArray): String = when {
        bytes.size >= 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() -> "png"
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
        bytes.size >= 3 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() -> "gif"
        else -> "png"
    }

    private fun mediaTypeForImage(path: String): String = when (path.substringAfterLast('.').lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "svg" -> "image/svg+xml"
        "webp" -> "image/webp"
        else -> "image/png"
    }

    private fun esc(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                // Drop characters not permitted in XML 1.0 (keep tab/newline/carriage-return). (Tier 0 bugfix)
                '\t', '\n', '\r' -> sb.append(c)
                else -> if (c.code >= 0x20) sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * Encodes inline text content preserving ODF whitespace semantics: runs of spaces become
     * text:s, tabs become text:tab, and newlines become text:line-break. Without this, ODF readers
     * collapse literal whitespace and lose multiple spaces / tabs / in-paragraph breaks. (Tier 0 bugfix)
     */
    private fun encodeText(text: String): String {
        if (text.isEmpty()) return ""
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when (c) {
                '\t' -> { sb.append("<text:tab/>"); i++ }
                '\n', '\u2028' -> { sb.append("<text:line-break/>"); i++ }
                '\r' -> { i++ }
                ' ' -> {
                    var j = i + 1
                    while (j < text.length && text[j] == ' ') j++
                    val run = j - i
                    sb.append(' ')
                    if (run > 1) sb.append("""<text:s text:c="${run - 1}"/>""")
                    i = j
                }
                '&' -> { sb.append("&amp;"); i++ }
                '<' -> { sb.append("&lt;"); i++ }
                '>' -> { sb.append("&gt;"); i++ }
                else -> { if (c.code >= 0x20 || c == '\u0009') sb.append(c); i++ }
            }
        }
        return sb.toString()
    }
}
