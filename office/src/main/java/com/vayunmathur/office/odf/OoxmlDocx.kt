package com.vayunmathur.office.odf

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import com.vayunmathur.library.ui.odf.*
import org.xmlpull.v1.XmlPullParser

/**
 * DOCX importer (Groups 1-5, phases D1-D16). Resolves styles.xml inheritance, numbering.xml lists,
 * rich run/paragraph formatting, tables with merges & decoration, sections/page setup, headers &
 * footers, footnotes/comments/bookmarks, hyperlinks & fields, images, text boxes, equations,
 * embedded charts, tracked changes, TOC and content controls. Best-effort onto the ODF model.
 */
internal object OoxmlDocx {

    // ---- Run / paragraph property holders (for style inheritance) ----

    private class RPr(
        var bold: Boolean? = null,
        var italic: Boolean? = null,
        var underline: String? = null,       // ODF underline style, or "none"
        var underlineColor: Long? = null,
        var strike: Boolean? = null,
        var color: Long? = null,
        var sizeHalfPt: Int? = null,
        var font: String? = null,
        var vertAlign: String? = null,        // "superscript"/"subscript"
        var caps: Boolean? = null,
        var smallCaps: Boolean? = null,
        var spacingTwips: Int? = null,
        var highlight: Long? = null,
        var shdFill: Long? = null,
        var vanish: Boolean? = null,
        var lang: String? = null
    ) {
        /** Returns a new RPr with [o]'s non-null values overriding this one's. */
        fun overlay(o: RPr) = RPr(
            o.bold ?: bold, o.italic ?: italic, o.underline ?: underline, o.underlineColor ?: underlineColor,
            o.strike ?: strike, o.color ?: color, o.sizeHalfPt ?: sizeHalfPt, o.font ?: font,
            o.vertAlign ?: vertAlign, o.caps ?: caps, o.smallCaps ?: smallCaps, o.spacingTwips ?: spacingTwips,
            o.highlight ?: highlight, o.shdFill ?: shdFill, o.vanish ?: vanish, o.lang ?: lang
        )
    }

    private class PPr(
        var styleId: String? = null,
        var jc: String? = null,
        var indLeft: Int? = null,
        var indRight: Int? = null,
        var indFirstLine: Int? = null,
        var indHanging: Int? = null,
        var spacingBefore: Int? = null,
        var spacingAfter: Int? = null,
        var lineRule: String? = null,
        var line: Int? = null,
        var bidi: Boolean? = null,
        var shdFill: Long? = null,
        var borders: OdfBorders? = null,
        var keepNext: Boolean? = null,
        var keepLines: Boolean? = null,
        var widowControl: Boolean? = null,
        var pageBreakBefore: Boolean? = null,
        var tabs: List<OdfTabStop>? = null,
        var numId: Int? = null,
        var ilvl: Int? = null,
        var outlineLvl: Int? = null,
        var dropCapLines: Int? = null,
        var rPr: RPr? = null
    ) {
        fun overlay(o: PPr) = PPr(
            o.styleId ?: styleId, o.jc ?: jc, o.indLeft ?: indLeft, o.indRight ?: indRight,
            o.indFirstLine ?: indFirstLine, o.indHanging ?: indHanging, o.spacingBefore ?: spacingBefore,
            o.spacingAfter ?: spacingAfter, o.lineRule ?: lineRule, o.line ?: line, o.bidi ?: bidi,
            o.shdFill ?: shdFill, o.borders ?: borders, o.keepNext ?: keepNext, o.keepLines ?: keepLines,
            o.widowControl ?: widowControl, o.pageBreakBefore ?: pageBreakBefore, o.tabs ?: tabs,
            o.numId ?: numId, o.ilvl ?: ilvl, o.outlineLvl ?: outlineLvl, o.dropCapLines ?: dropCapLines,
            (rPr ?: RPr()).let { base -> o.rPr?.let { base.overlay(it) } ?: base }
        )
    }

    private class StyleDef(
        val id: String, val type: String?, val basedOn: String?,
        val rpr: RPr, val ppr: PPr, val outlineLvl: Int?, val name: String?
    )

    private class Styles(
        val byId: Map<String, StyleDef>,
        val docDefaultRPr: RPr,
        val docDefaultPPr: PPr,
        val defaultParaStyle: String?
    ) {
        private val rprCache = HashMap<String, RPr>()
        private val pprCache = HashMap<String, PPr>()

        fun resolvedRPr(id: String?): RPr {
            if (id == null) return docDefaultRPr
            rprCache[id]?.let { return it }
            val chain = chain(id)
            var acc = docDefaultRPr
            for (s in chain) acc = acc.overlay(s.rpr)
            rprCache[id] = acc
            return acc
        }

        fun resolvedPPr(id: String?): PPr {
            if (id == null) return docDefaultPPr
            pprCache[id]?.let { return it }
            val chain = chain(id)
            var acc = docDefaultPPr
            for (s in chain) acc = acc.overlay(s.ppr)
            pprCache[id] = acc
            return acc
        }

        fun outlineLvl(id: String?): Int? {
            var cur = id?.let { byId[it] }
            val seen = HashSet<String>()
            while (cur != null && seen.add(cur.id)) {
                cur.outlineLvl?.let { return it }
                cur = cur.basedOn?.let { byId[it] }
            }
            return null
        }

        /** basedOn chain from root ancestor down to [id]. */
        private fun chain(id: String): List<StyleDef> {
            val out = ArrayDeque<StyleDef>()
            var cur = byId[id]
            val seen = HashSet<String>()
            while (cur != null && seen.add(cur.id)) {
                out.addFirst(cur)
                cur = cur.basedOn?.let { byId[it] }
            }
            return out.toList()
        }
    }

    // ---- Numbering ----

    private class NumLevel(val numFmt: String, val lvlText: String, val start: Int)
    private class Numbering(
        private val numToAbstract: Map<Int, Int>,
        private val abstractLevels: Map<Int, Map<Int, NumLevel>>
    ) {
        fun level(numId: Int, ilvl: Int): NumLevel? =
            numToAbstract[numId]?.let { abstractLevels[it]?.get(ilvl) }
    }

    // ---- Entry point ----

    fun import(pkg: OoxmlPackage, fileName: String): OdfDocument.TextDocument {
        val docPart = "word/document.xml"
        val xml = pkg.entries[docPart] ?: return OdfDocument.TextDocument(fileName, emptyList())
        val theme = OoxmlTheme.parse(pkg.entries["word/theme/theme1.xml"])
        val styles = pkg.entries["word/styles.xml"]?.let { parseStyles(it, theme) }
            ?: Styles(emptyMap(), RPr(), PPr(), null)
        val numbering = pkg.entries["word/numbering.xml"]?.let { parseNumbering(it) }
            ?: Numbering(emptyMap(), emptyMap())
        val rels = pkg.relsFor(docPart)

        val footnotesById = parseNotes(pkg.entries["word/footnotes.xml"], "footnote", styles, theme, false)
        val endnotesById = parseNotes(pkg.entries["word/endnotes.xml"], "endnote", styles, theme, true)
        val comments = parseComments(pkg.entries["word/comments.xml"], styles, theme)

        val ctx = DocxCtx(pkg, docPart, theme, styles, numbering, rels, footnotesById, endnotesById, comments)
        val content = mutableListOf<OdfContentBlock>()
        parseBody(OoxmlXml.newParser(xml), ctx, content)

        // Headers / footers (first referenced default of each).
        val headerFooter = parseHeadersFooters(pkg, docPart, rels, styles, theme)
        val images = LinkedHashMap<String, ByteArray>()
        for (b in content) if (b is OdfContentBlock.Image) images[b.image.path] = b.image.imageData
        images.putAll(ctx.extraImages)

        return OdfDocument.TextDocument(
            title = fileName,
            content = content,
            metadata = OoxmlMetadata.parse(pkg),
            images = images,
            footnotes = ctx.usedNotes.toList(),
            headerParagraphs = headerFooter.first,
            footerParagraphs = headerFooter.second,
            bookmarks = ctx.bookmarks.toList(),
            changes = ctx.changes.values.toList(),
            pageSetup = ctx.pageSetup
        )
    }

    private class DocxCtx(
        val pkg: OoxmlPackage,
        val part: String,
        val theme: OoxmlTheme,
        val styles: Styles,
        val numbering: Numbering,
        val rels: Map<String, OoxmlPackage.Rel>,
        val footnotes: Map<String, OdfFootnote>,
        val endnotes: Map<String, OdfFootnote>,
        val comments: Map<String, OdfAnnotation>,
        val bookmarks: MutableList<OdfBookmark> = mutableListOf(),
        val changes: LinkedHashMap<String, OdfChange> = LinkedHashMap(),
        val usedNotes: MutableList<OdfFootnote> = mutableListOf(),
        val extraImages: LinkedHashMap<String, ByteArray> = LinkedHashMap(),
        val pendingBlocks: MutableList<OdfContentBlock> = mutableListOf(),
        var pageSetup: OdfPageSetup? = null,
        var imageSeq: Int = 0
    )

    // ---- Body ----

    private fun parseBody(parser: XmlPullParser, ctx: DocxCtx, out: MutableList<OdfContentBlock>) {
        var event = parser.eventType
        var inBody = false
        var columnCount = 1
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) when (parser.name) {
                "body" -> inBody = true
                "p" -> if (inBody) parseParagraph(parser, ctx, out)
                "tbl" -> if (inBody) out.add(OdfContentBlock.Table(parseTable(parser, ctx)))
                "sectPr" -> if (inBody && parser.depth <= 3) {
                    val sect = parseSectPr(parser)
                    ctx.pageSetup = sect.first
                    columnCount = sect.second
                }
                "oMathPara" -> if (inBody) convertOMathPara(parser)?.let { out.add(OdfContentBlock.Formula(it)) }
            } else if (event == XmlPullParser.END_TAG && parser.name == "body") inBody = false
            event = parser.next()
        }
        // Wrap in a multi-column section if the (last) sectPr declared columns.
        if (columnCount > 1) {
            out.add(0, OdfContentBlock.SectionStart("Section", columnCount))
            out.add(OdfContentBlock.SectionEnd)
        }
    }

    /** Converts an m:oMathPara wrapper by descending to its inner m:oMath. */
    private fun convertOMathPara(parser: XmlPullParser): String? {
        val depth = parser.depth
        var result: String? = null
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "oMathPara")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG && parser.name == "oMath" && result == null) result = OmmlToMathml.convertElement(parser)
            e = parser.next()
        }
        return result
    }

    private fun parseParagraph(parser: XmlPullParser, ctx: DocxCtx, out: MutableList<OdfContentBlock>) {
        val depth = parser.depth
        val spans = mutableListOf<OdfSpan>()
        var ppr = PPr()
        var mathml: String? = null
        val fieldState = FieldState()
        val blockStart = ctx.pendingBlocks.size
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "p")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "pPr" -> ppr = parsePPr(parser, ctx.theme)
                "r" -> parseRun(parser, ctx, ppr, spans, fieldState, null, null)
                "hyperlink" -> parseHyperlink(parser, ctx, ppr, spans)
                "ins" -> parseChangeWrapper(parser, ctx, ppr, spans, "insertion")
                "del" -> parseChangeWrapper(parser, ctx, ppr, spans, "deletion")
                "bookmarkStart" -> OoxmlXml.attr(parser, "name")?.let { ctx.bookmarks.add(OdfBookmark(it, out.size)) }
                "oMath" -> if (mathml == null) mathml = OmmlToMathml.convertElement(parser)
                "sdt" -> parseSdtInline(parser, ctx, spans)
            }
            e = parser.next()
        }
        val newBlocks = if (ctx.pendingBlocks.size > blockStart) {
            val list = ctx.pendingBlocks.subList(blockStart, ctx.pendingBlocks.size)
            val copy = list.toList(); list.clear(); copy
        } else emptyList()

        if (mathml != null && spans.all { it.text.isBlank() } && newBlocks.isEmpty()) {
            out.add(OdfContentBlock.Formula(mathml)); return
        }
        if (spans.isNotEmpty() || newBlocks.isEmpty()) out.add(OdfContentBlock.Paragraph(buildParagraph(ppr, ctx, spans)))
        out.addAll(newBlocks)
    }

    private fun buildParagraph(ppr: PPr, ctx: DocxCtx, spans: List<OdfSpan>): OdfParagraph {
        val styles = ctx.styles
        val eff = styles.resolvedPPr(ppr.styleId ?: styles.defaultParaStyle).overlay(ppr)
        val outline = ppr.outlineLvl ?: styles.outlineLvl(ppr.styleId)
        val style = paragraphStyle(ppr.styleId, outline)

        val align = jcToAlign(eff.jc)
        val direction = when (eff.bidi) { true -> LayoutDirection.Rtl; false -> LayoutDirection.Ltr; null -> null }
        val marginLeft = eff.indLeft?.let { OoxmlUnits.twipsToPx(it) } ?: 0f
        val marginRight = eff.indRight?.let { OoxmlUnits.twipsToPx(it) } ?: 0f
        val textIndent = when {
            eff.indHanging != null -> -OoxmlUnits.twipsToPx(eff.indHanging!!)
            eff.indFirstLine != null -> OoxmlUnits.twipsToPx(eff.indFirstLine!!)
            else -> 0f
        }
        val marginTop = eff.spacingBefore?.let { OoxmlUnits.twipsToPx(it) } ?: 0f
        val marginBottom = eff.spacingAfter?.let { OoxmlUnits.twipsToPx(it) } ?: 0f
        val lineHeight = if (eff.lineRule == "auto" && eff.line != null) eff.line!! / 240f else null

        // Numbering
        var listLevel = 0; var listType = ListType.BULLET
        var numFmt = "1"; var bulletChar = "\u2022"; var prefix = ""; var suffix = "."
        var isList = false
        if (eff.numId != null && eff.numId != 0) {
            val ilvl = eff.ilvl ?: 0
            ctx.numbering.level(eff.numId!!, ilvl)?.let { lvl ->
                isList = true
                listLevel = ilvl
                if (lvl.numFmt == "bullet") {
                    listType = ListType.BULLET
                    bulletChar = mapBullet(lvl.lvlText)
                } else {
                    listType = ListType.NUMBERED
                    numFmt = mapNumFmt(lvl.numFmt)
                    val markers = Regex("%\\d+").findAll(lvl.lvlText).toList()
                    if (markers.isNotEmpty()) {
                        prefix = lvl.lvlText.substring(0, markers.first().range.first)
                        suffix = lvl.lvlText.substring(markers.last().range.last + 1)
                    }
                }
            }
        }

        return OdfParagraph(
            spans = spans.ifEmpty { listOf(OdfSpan("")) },
            style = if (isList && style == ParagraphStyle.BODY) ParagraphStyle.LIST_ITEM else style,
            alignment = align,
            marginLeft = marginLeft,
            marginRight = marginRight,
            marginTop = marginTop,
            marginBottom = marginBottom,
            textIndent = textIndent,
            backgroundColor = eff.shdFill,
            listLevel = listLevel,
            listType = listType,
            direction = direction,
            lineHeightPercent = lineHeight,
            borders = eff.borders?.takeIf { !it.isEmpty() },
            borderColor = eff.borders?.let { OdfBorders.renderColor(it.top ?: it.left) },
            listNumberFormat = numFmt,
            listBulletChar = bulletChar,
            listNumberPrefix = prefix,
            listNumberSuffix = suffix,
            tabStopDetails = eff.tabs ?: emptyList(),
            tabStops = eff.tabs?.map { it.position } ?: emptyList(),
            dropCapLines = eff.dropCapLines ?: 0,
            breakBeforePage = eff.pageBreakBefore == true,
            keepWithNext = eff.keepNext == true,
            keepTogether = eff.keepLines == true,
            widows = if (eff.widowControl == true) 2 else null,
            orphans = if (eff.widowControl == true) 2 else null
        )
    }

    // ---- Runs ----

    private class FieldState {
        var capturing = false          // between fldChar begin..separate: capturing instr
        var inResult = false           // between separate..end: capturing display text
        var instr = StringBuilder()
        var resultStart = -1
    }

    private fun parseRun(
        parser: XmlPullParser, ctx: DocxCtx, ppr: PPr,
        spans: MutableList<OdfSpan>, field: FieldState,
        forcedHref: String?, changeKind: String?, changeId: String? = null
    ) {
        val depth = parser.depth
        var rpr = RPr()
        val sb = StringBuilder()
        var noteCitation: String? = null
        var isEndnote = false
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "r")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "rPr" -> rpr = parseRPr(parser, ctx.theme)
                "t" -> sb.append(OoxmlXml.readElementText(parser, "t"))
                "tab" -> sb.append("\t")
                "br", "cr" -> sb.append("\n")
                "noBreakHyphen" -> sb.append("\u2011")
                "softHyphen" -> sb.append("\u00AD")
                "sym" -> sb.append(symChar(OoxmlXml.attr(parser, "char")))
                "fldChar" -> handleFldChar(parser, field, spans)
                "instrText" -> if (field.capturing) field.instr.append(OoxmlXml.readElementText(parser, "instrText"))
                "footnoteReference" -> { noteCitation = registerNote(ctx, ctx.footnotes, OoxmlXml.attr(parser, "id")); }
                "endnoteReference" -> { noteCitation = registerNote(ctx, ctx.endnotes, OoxmlXml.attr(parser, "id")); isEndnote = true }
                "drawing", "pict" -> parseDrawing(parser, ctx)
            }
            e = parser.next()
        }

        // Effective run props from paragraph mark + style default.
        val base = ctx.styles.resolvedRPr(ppr.styleId ?: ctx.styles.defaultParaStyle)
        val paraMark = ppr.rPr ?: RPr()
        val eff = base.overlay(paraMark).overlay(rpr)

        if (eff.vanish == true) return
        if (noteCitation != null) {
            spans.add(OdfSpan(text = noteCitation, superscript = true))
            @Suppress("UNUSED_VALUE") run { isEndnote = isEndnote }
            return
        }
        if (sb.isEmpty()) return

        val href = forcedHref ?: field.currentHyperlink()
        spans.add(toSpan(sb.toString(), eff, href, changeKind, changeId))
    }

    private fun FieldState.currentHyperlink(): String? = null

    private fun toSpan(text: String, e: RPr, href: String?, changeKind: String?, changeId: String?): OdfSpan {
        val transform = when { e.caps == true -> "uppercase"; e.smallCaps == true -> "uppercase"; else -> null }
        return OdfSpan(
            text = text,
            bold = e.bold == true,
            italic = e.italic == true,
            fontSize = e.sizeHalfPt?.let { it / 2f },
            fontFamily = e.font,
            underline = e.underline != null && e.underline != "none",
            underlineStyle = e.underline?.takeIf { it != "none" },
            underlineColor = e.underlineColor,
            strikethrough = e.strike == true,
            color = e.color,
            backgroundColor = e.highlight ?: e.shdFill,
            superscript = e.vertAlign == "superscript",
            subscript = e.vertAlign == "subscript",
            letterSpacing = e.spacingTwips?.let { it / 20f },
            textTransform = transform,
            language = e.lang?.substringBefore('-'),
            country = e.lang?.substringAfter('-', "")?.ifEmpty { null },
            href = href,
            changeKind = changeKind,
            changeId = changeId
        )
    }

    private fun handleFldChar(parser: XmlPullParser, field: FieldState, spans: MutableList<OdfSpan>) {
        when (OoxmlXml.attr(parser, "fldCharType")) {
            "begin" -> { field.capturing = true; field.inResult = false; field.instr = StringBuilder() }
            "separate" -> { field.capturing = false; field.inResult = true; field.resultStart = spans.size }
            "end" -> {
                if (field.inResult && field.resultStart in 0..spans.size) {
                    val kind = fieldKind(field.instr.toString())
                    val href = hyperlinkTarget(field.instr.toString())
                    if (kind != null || href != null) {
                        for (i in field.resultStart until spans.size) {
                            spans[i] = spans[i].copy(field = kind ?: spans[i].field, href = href ?: spans[i].href)
                        }
                    }
                }
                field.capturing = false; field.inResult = false; field.instr = StringBuilder(); field.resultStart = -1
            }
        }
    }

    private fun fieldKind(instr: String): String? {
        val t = instr.trim().uppercase()
        return when {
            t.startsWith("PAGE ") || t == "PAGE" -> "page-number"
            t.startsWith("NUMPAGES") -> "page-count"
            t.startsWith("DATE") -> "date"
            t.startsWith("TIME") -> "time"
            t.startsWith("AUTHOR") -> "author-name"
            t.startsWith("FILENAME") -> "file-name"
            t.startsWith("TITLE") -> "title"
            t.startsWith("REF") || t.startsWith("PAGEREF") -> "bookmark-ref"
            t.startsWith("SEQ") -> "sequence"
            else -> null
        }
    }

    private fun hyperlinkTarget(instr: String): String? {
        val m = Regex("HYPERLINK\\s+\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(instr) ?: return null
        return m.groupValues[1]
    }

    private fun parseHyperlink(parser: XmlPullParser, ctx: DocxCtx, ppr: PPr, spans: MutableList<OdfSpan>) {
        val depth = parser.depth
        val rId = OoxmlXml.attrNs(parser, "http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
            ?: OoxmlXml.attr(parser, "id")
        val anchor = OoxmlXml.attr(parser, "anchor")
        val href = ctx.rels[rId]?.target ?: anchor?.let { "#$it" }
        val start = spans.size
        val field = FieldState()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "hyperlink")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG && parser.name == "r") parseRun(parser, ctx, ppr, spans, field, href, null)
            e = parser.next()
        }
        if (href == null && anchor != null) {
            for (i in start until spans.size) spans[i] = spans[i].copy(refName = anchor, refKind = "bookmark-ref")
        }
    }

    private fun parseChangeWrapper(parser: XmlPullParser, ctx: DocxCtx, ppr: PPr, spans: MutableList<OdfSpan>, kind: String) {
        val depth = parser.depth
        val endTag = if (kind == "insertion") "ins" else "del"
        val author = OoxmlXml.attr(parser, "author")
        val date = OoxmlXml.attr(parser, "date")
        val id = ctx.newChangeId(kind, author, date)
        val field = FieldState()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == endTag)) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG && parser.name == "r") parseRun(parser, ctx, ppr, spans, field, null, kind, id)
            e = parser.next()
        }
    }

    private fun DocxCtx.newChangeId(kind: String, author: String? = null, date: String? = null): String {
        val id = "ct${changes.size + 1}"
        changes[id] = OdfChange(id = id, type = kind, author = author, date = date)
        return id
    }

    private fun parseSdtInline(parser: XmlPullParser, ctx: DocxCtx, spans: MutableList<OdfSpan>) {
        // Unwrap sdtContent: parse its runs as if inline.
        val depth = parser.depth
        val field = FieldState()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "sdt")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG && parser.name == "r") parseRun(parser, ctx, PPr(), spans, field, null, null)
            e = parser.next()
        }
    }

    private fun registerNote(ctx: DocxCtx, notes: Map<String, OdfFootnote>, id: String?): String? {
        val note = id?.let { notes[it] } ?: return id
        if (ctx.usedNotes.none { it === note }) ctx.usedNotes.add(note)
        return note.citation
    }

    // ---- Property parsers ----

    private fun parseRPr(parser: XmlPullParser, theme: OoxmlTheme): RPr {
        val depth = parser.depth
        val r = RPr()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && (parser.name == "rPr" || parser.name == "defRPr"))) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "b" -> r.bold = OoxmlXml.boolAttr(OoxmlXml.attr(parser, "val"))
                "i" -> r.italic = OoxmlXml.boolAttr(OoxmlXml.attr(parser, "val"))
                "strike" -> r.strike = OoxmlXml.boolAttr(OoxmlXml.attr(parser, "val"))
                "dstrike" -> if (OoxmlXml.boolAttr(OoxmlXml.attr(parser, "val"))) r.strike = true
                "u" -> {
                    val v = OoxmlXml.attr(parser, "val")
                    r.underline = mapUnderline(v)
                    r.underlineColor = OoxmlUnits.hexColor(OoxmlXml.attr(parser, "color"))
                }
                "color" -> r.color = resolveWColor(parser, theme)
                "sz" -> r.sizeHalfPt = OoxmlXml.attr(parser, "val")?.toIntOrNull()
                "rFonts" -> r.font = OoxmlXml.attr(parser, "ascii") ?: OoxmlXml.attr(parser, "hAnsi") ?: OoxmlXml.attr(parser, "cs")
                "vertAlign" -> r.vertAlign = when (OoxmlXml.attr(parser, "val")) { "superscript" -> "superscript"; "subscript" -> "subscript"; else -> null }
                "caps" -> r.caps = OoxmlXml.boolAttr(OoxmlXml.attr(parser, "val"))
                "smallCaps" -> r.smallCaps = OoxmlXml.boolAttr(OoxmlXml.attr(parser, "val"))
                "spacing" -> r.spacingTwips = OoxmlXml.attr(parser, "val")?.toIntOrNull()
                "highlight" -> r.highlight = OoxmlUnits.highlightColor(OoxmlXml.attr(parser, "val"))
                "shd" -> r.shdFill = OoxmlUnits.hexColor(OoxmlXml.attr(parser, "fill"))
                "vanish" -> r.vanish = OoxmlXml.boolAttr(OoxmlXml.attr(parser, "val"))
                "lang" -> r.lang = OoxmlXml.attr(parser, "val")
            }
            e = parser.next()
        }
        return r
    }

    private fun resolveWColor(parser: XmlPullParser, theme: OoxmlTheme): Long? {
        OoxmlUnits.hexColor(OoxmlXml.attr(parser, "val"))?.let { return it }
        val themeColor = OoxmlXml.attr(parser, "themeColor") ?: return null
        val base = theme.schemeColor(mapWordTheme(themeColor)) ?: return null
        val tint = OoxmlXml.attr(parser, "themeTint")?.toIntOrNull(16)
        val shade = OoxmlXml.attr(parser, "themeShade")?.toIntOrNull(16)
        return when {
            tint != null -> OoxmlUnits.applyTransforms(base, tint = (tint / 255f * 100000).toInt())
            shade != null -> OoxmlUnits.applyTransforms(base, shade = (shade / 255f * 100000).toInt())
            else -> base
        }
    }

    private fun parsePPr(parser: XmlPullParser, theme: OoxmlTheme): PPr {
        val depth = parser.depth
        val p = PPr()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "pPr")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "pStyle" -> p.styleId = OoxmlXml.attr(parser, "val")
                "jc" -> p.jc = OoxmlXml.attr(parser, "val")
                "bidi" -> p.bidi = OoxmlXml.boolAttr(OoxmlXml.attr(parser, "val"))
                "keepNext" -> p.keepNext = OoxmlXml.boolAttr(OoxmlXml.attr(parser, "val"))
                "keepLines" -> p.keepLines = OoxmlXml.boolAttr(OoxmlXml.attr(parser, "val"))
                "widowControl" -> p.widowControl = OoxmlXml.boolAttr(OoxmlXml.attr(parser, "val"))
                "pageBreakBefore" -> p.pageBreakBefore = OoxmlXml.boolAttr(OoxmlXml.attr(parser, "val"))
                "outlineLvl" -> p.outlineLvl = OoxmlXml.attr(parser, "val")?.toIntOrNull()
                "ind" -> {
                    p.indLeft = (OoxmlXml.attr(parser, "left") ?: OoxmlXml.attr(parser, "start"))?.toIntOrNull()
                    p.indRight = (OoxmlXml.attr(parser, "right") ?: OoxmlXml.attr(parser, "end"))?.toIntOrNull()
                    p.indFirstLine = OoxmlXml.attr(parser, "firstLine")?.toIntOrNull()
                    p.indHanging = OoxmlXml.attr(parser, "hanging")?.toIntOrNull()
                }
                "spacing" -> {
                    p.spacingBefore = OoxmlXml.attr(parser, "before")?.toIntOrNull()
                    p.spacingAfter = OoxmlXml.attr(parser, "after")?.toIntOrNull()
                    p.line = OoxmlXml.attr(parser, "line")?.toIntOrNull()
                    p.lineRule = OoxmlXml.attr(parser, "lineRule")
                }
                "shd" -> p.shdFill = OoxmlUnits.hexColor(OoxmlXml.attr(parser, "fill"))
                "pBdr" -> p.borders = parseBorders(parser, "pBdr")
                "numPr" -> parseNumPr(parser, p)
                "tabs" -> p.tabs = parseTabs(parser)
                "framePr" -> OoxmlXml.attr(parser, "dropCap")?.let { if (it != "none") p.dropCapLines = OoxmlXml.attr(parser, "lines")?.toIntOrNull() ?: 3 }
                "rPr" -> p.rPr = parseRPr(parser, theme)
            }
            e = parser.next()
        }
        return p
    }

    private fun parseNumPr(parser: XmlPullParser, p: PPr) {
        val depth = parser.depth
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "numPr")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "ilvl" -> p.ilvl = OoxmlXml.attr(parser, "val")?.toIntOrNull()
                "numId" -> p.numId = OoxmlXml.attr(parser, "val")?.toIntOrNull()
            }
            e = parser.next()
        }
    }

    private fun parseTabs(parser: XmlPullParser): List<OdfTabStop> {
        val depth = parser.depth
        val tabs = mutableListOf<OdfTabStop>()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "tabs")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG && parser.name == "tab") {
                val pos = OoxmlXml.attr(parser, "pos")?.toIntOrNull()
                val valType = OoxmlXml.attr(parser, "val")
                if (pos != null && valType != "clear") {
                    tabs.add(OdfTabStop(
                        position = OoxmlUnits.twipsToPx(pos),
                        type = when (valType) { "center" -> "center"; "right", "end" -> "right"; "decimal" -> "char"; else -> "left" },
                        leaderChar = when (OoxmlXml.attr(parser, "leader")) { "dot" -> "."; "hyphen" -> "-"; "underscore" -> "_"; else -> null }
                    ))
                }
            }
            e = parser.next()
        }
        return tabs
    }

    private fun parseBorders(parser: XmlPullParser, endTag: String): OdfBorders {
        val depth = parser.depth
        var top: String? = null; var right: String? = null; var bottom: String? = null; var left: String? = null
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == endTag)) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) {
                val edge = when (parser.name) { "top" -> "top"; "bottom" -> "bottom"; "left", "start" -> "left"; "right", "end" -> "right"; else -> null }
                if (edge != null) {
                    val v = borderValue(parser)
                    when (edge) { "top" -> top = v; "bottom" -> bottom = v; "left" -> left = v; "right" -> right = v }
                }
            }
            e = parser.next()
        }
        return OdfBorders(top, right, bottom, left)
    }

    private fun borderValue(parser: XmlPullParser): String? {
        val style = OoxmlXml.attr(parser, "val") ?: return null
        if (style == "nil" || style == "none") return null
        val szEighthPt = OoxmlXml.attr(parser, "sz")?.toIntOrNull() ?: 4
        val pt = szEighthPt / 8f
        val color = OoxmlXml.attr(parser, "color")?.takeIf { !it.equals("auto", true) }?.let { "#$it" } ?: "#000000"
        val odfStyle = when (style) { "single" -> "solid"; "double" -> "double"; "dotted" -> "dotted"; "dashed" -> "dashed"; else -> "solid" }
        return "%.2fpt %s %s".format(pt, odfStyle, color)
    }

    // ---- Styles / numbering parsing ----

    private fun parseStyles(xml: String, theme: OoxmlTheme): Styles {
        val parser = OoxmlXml.newParser(xml)
        val byId = LinkedHashMap<String, StyleDef>()
        var docRPr = RPr(); var docPPr = PPr(); var defaultPara: String? = null
        var e = parser.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "docDefaults" -> { val d = parseDocDefaults(parser, theme); docRPr = d.first; docPPr = d.second }
                "style" -> {
                    val isDefault = OoxmlXml.attr(parser, "default") == "1"
                    val styleType = OoxmlXml.attr(parser, "type")
                    val def = parseStyle(parser, theme)
                    byId[def.id] = def
                    if (isDefault && styleType == "paragraph") defaultPara = def.id
                }
            }
            e = parser.next()
        }
        return Styles(byId, docRPr, docPPr, defaultPara)
    }

    private fun parseDocDefaults(parser: XmlPullParser, theme: OoxmlTheme): Pair<RPr, PPr> {
        val depth = parser.depth
        var rpr = RPr(); var ppr = PPr()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "docDefaults")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "rPr" -> rpr = parseRPr(parser, theme)
                "pPr" -> ppr = parsePPr(parser, theme)
            }
            e = parser.next()
        }
        return rpr to ppr
    }

    private fun parseStyle(parser: XmlPullParser, theme: OoxmlTheme): StyleDef {
        val depth = parser.depth
        val type = OoxmlXml.attr(parser, "type")
        val id = OoxmlXml.attr(parser, "styleId") ?: ""
        var basedOn: String? = null; var name: String? = null; var outline: Int? = null
        var rpr = RPr(); var ppr = PPr()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "style")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "basedOn" -> basedOn = OoxmlXml.attr(parser, "val")
                "name" -> name = OoxmlXml.attr(parser, "val")
                "rPr" -> rpr = parseRPr(parser, theme)
                "pPr" -> { ppr = parsePPr(parser, theme); outline = ppr.outlineLvl }
            }
            e = parser.next()
        }
        return StyleDef(id, type, basedOn, rpr, ppr, outline, name)
    }

    private fun parseNumbering(xml: String): Numbering {
        val parser = OoxmlXml.newParser(xml)
        val numToAbstract = HashMap<Int, Int>()
        val abstractLevels = HashMap<Int, MutableMap<Int, NumLevel>>()
        var e = parser.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "abstractNum" -> {
                    val aid = OoxmlXml.attr(parser, "abstractNumId")?.toIntOrNull()
                    if (aid != null) abstractLevels[aid] = parseAbstractNum(parser)
                }
                "num" -> {
                    val numId = OoxmlXml.attr(parser, "numId")?.toIntOrNull()
                    val aid = readAbstractNumId(parser)
                    if (numId != null && aid != null) numToAbstract[numId] = aid
                }
            }
            e = parser.next()
        }
        return Numbering(numToAbstract, abstractLevels)
    }

    private fun readAbstractNumId(parser: XmlPullParser): Int? {
        val depth = parser.depth
        var aid: Int? = null
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "num")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG && parser.name == "abstractNumId") aid = OoxmlXml.attr(parser, "val")?.toIntOrNull()
            e = parser.next()
        }
        return aid
    }

    private fun parseAbstractNum(parser: XmlPullParser): MutableMap<Int, NumLevel> {
        val depth = parser.depth
        val levels = HashMap<Int, NumLevel>()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "abstractNum")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG && parser.name == "lvl") {
                val ilvl = OoxmlXml.attr(parser, "ilvl")?.toIntOrNull() ?: 0
                levels[ilvl] = parseLvl(parser)
            }
            e = parser.next()
        }
        return levels
    }

    private fun parseLvl(parser: XmlPullParser): NumLevel {
        val depth = parser.depth
        var numFmt = "decimal"; var lvlText = "%1."; var start = 1
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "lvl")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "numFmt" -> numFmt = OoxmlXml.attr(parser, "val") ?: numFmt
                "lvlText" -> lvlText = OoxmlXml.attr(parser, "val") ?: lvlText
                "start" -> start = OoxmlXml.attr(parser, "val")?.toIntOrNull() ?: start
            }
            e = parser.next()
        }
        return NumLevel(numFmt, lvlText, start)
    }

    // ---- Tables ----

    private fun parseTable(parser: XmlPullParser, ctx: DocxCtx): OdfTable {
        val depth = parser.depth
        val grid = mutableListOf<MutableList<OdfTableCell>>()
        val columns = mutableListOf<OdfTableColumn>()
        var headerRows = 0
        var tblBorders: OdfBorders? = null
        // colStart (grid column) -> (rowIndex, colIndex) of the vMerge anchor cell.
        val vAnchors = HashMap<Int, Pair<Int, Int>>()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "tbl")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "gridCol" -> OoxmlXml.attr(parser, "w")?.toIntOrNull()?.let { columns.add(OdfTableColumn(OoxmlUnits.twipsToPx(it))) }
                "tblBorders" -> tblBorders = parseBorders(parser, "tblBorders")
                "tr" -> if (parseRow(parser, ctx, grid, vAnchors)) headerRows = grid.size
            }
            e = parser.next()
        }
        val rows = grid.map { cells ->
            val decorated = if (tblBorders != null && !tblBorders.isEmpty()) cells.map { c ->
                if (c.borders == null && !c.isCovered) c.copy(borders = tblBorders, borderColor = OdfBorders.renderColor(tblBorders.top)) else c
            } else cells
            OdfTableRow(decorated)
        }
        return OdfTable(columns = columns, rows = rows, headerRowCount = headerRows)
    }

    /** Parses one table row into [grid], resolving vertical merges. Returns true if it's a header row. */
    private fun parseRow(
        parser: XmlPullParser, ctx: DocxCtx,
        grid: MutableList<MutableList<OdfTableCell>>, vAnchors: HashMap<Int, Pair<Int, Int>>
    ): Boolean {
        val depth = parser.depth
        val rowIdx = grid.size
        val cells = mutableListOf<OdfTableCell>()
        var isHeader = false
        var colCursor = 0
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "tr")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "tblHeader" -> if (OoxmlXml.boolAttr(OoxmlXml.attr(parser, "val"))) isHeader = true
                "tc" -> {
                    val cell = parseCell(parser, ctx)
                    val span = cell.colSpan
                    if (cell.vMergeContinue) {
                        vAnchors[colCursor]?.let { (ar, ac) ->
                            val anchor = grid.getOrNull(ar)?.getOrNull(ac)
                            if (anchor != null) grid[ar][ac] = anchor.copy(rowSpan = anchor.rowSpan + 1)
                        }
                        repeat(span) { cells.add(OdfTableCell(isCovered = true)) }
                    } else {
                        val myCol = cells.size
                        cells.add(cell.toModel())
                        repeat(span - 1) { cells.add(OdfTableCell(isCovered = true)) }
                        if (cell.vMergeRestart) vAnchors[colCursor] = rowIdx to myCol else vAnchors.remove(colCursor)
                    }
                    colCursor += span
                }
            }
            e = parser.next()
        }
        grid.add(cells)
        return isHeader
    }

    private class CellAccum(
        val paragraphs: List<OdfParagraph>,
        val colSpan: Int,
        val backgroundColor: Long?,
        val borders: OdfBorders?,
        val vAlign: String?,
        val vMergeRestart: Boolean,
        val vMergeContinue: Boolean
    ) {
        fun toModel() = OdfTableCell(
            paragraphs = paragraphs.ifEmpty { listOf(OdfParagraph(listOf(OdfSpan("")))) },
            colSpan = colSpan,
            backgroundColor = backgroundColor,
            borders = borders?.takeIf { !it.isEmpty() },
            borderColor = borders?.let { OdfBorders.renderColor(it.top ?: it.left) },
            verticalAlign = vAlign
        )
    }

    private fun parseCell(parser: XmlPullParser, ctx: DocxCtx): CellAccum {
        val depth = parser.depth
        val paras = mutableListOf<OdfParagraph>()
        var colSpan = 1; var bg: Long? = null; var borders: OdfBorders? = null; var vAlign: String? = null
        var vMergeRestart = false; var vMergeContinue = false
        val dummy = mutableListOf<OdfContentBlock>()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "tc")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "gridSpan" -> colSpan = OoxmlXml.attr(parser, "val")?.toIntOrNull() ?: 1
                "shd" -> bg = OoxmlUnits.hexColor(OoxmlXml.attr(parser, "fill")) ?: bg
                "tcBorders" -> borders = parseBorders(parser, "tcBorders")
                "vAlign" -> vAlign = when (OoxmlXml.attr(parser, "val")) { "center" -> "middle"; "bottom" -> "bottom"; else -> "top" }
                "vMerge" -> { val v = OoxmlXml.attr(parser, "val"); if (v == null || v == "continue") vMergeContinue = true else vMergeRestart = true }
                "p" -> { val before = dummy.size; parseParagraph(parser, ctx, dummy); for (i in before until dummy.size) (dummy[i] as? OdfContentBlock.Paragraph)?.let { paras.add(it.paragraph) } }
                "tbl" -> { // nested table -> flatten to text paragraphs (best-effort)
                    val nested = parseTable(parser, ctx)
                    for (r in nested.rows) for (c in r.cells) if (!c.isCovered) paras.addAll(c.paragraphs)
                }
            }
            e = parser.next()
        }
        return CellAccum(paras, colSpan, bg, borders, vAlign, vMergeRestart, vMergeContinue)
    }

    // ---- Sections / page setup ----

    private fun parseSectPr(parser: XmlPullParser): Pair<OdfPageSetup?, Int> {
        val depth = parser.depth
        var w = 793.7f; var h = 1122.5f; var ml = 75.6f; var mr = 75.6f; var mt = 75.6f; var mb = 75.6f
        var landscape = false; var cols = 1; var hasPgSz = false
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "sectPr")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "pgSz" -> {
                    hasPgSz = true
                    OoxmlXml.attr(parser, "w")?.toIntOrNull()?.let { w = OoxmlUnits.twipsToPx(it) }
                    OoxmlXml.attr(parser, "h")?.toIntOrNull()?.let { h = OoxmlUnits.twipsToPx(it) }
                    landscape = OoxmlXml.attr(parser, "orient") == "landscape"
                }
                "pgMar" -> {
                    OoxmlXml.attr(parser, "left")?.toIntOrNull()?.let { ml = OoxmlUnits.twipsToPx(it) }
                    OoxmlXml.attr(parser, "right")?.toIntOrNull()?.let { mr = OoxmlUnits.twipsToPx(it) }
                    OoxmlXml.attr(parser, "top")?.toIntOrNull()?.let { mt = OoxmlUnits.twipsToPx(it) }
                    OoxmlXml.attr(parser, "bottom")?.toIntOrNull()?.let { mb = OoxmlUnits.twipsToPx(it) }
                }
                "cols" -> cols = OoxmlXml.attr(parser, "num")?.toIntOrNull() ?: 1
            }
            e = parser.next()
        }
        if (!hasPgSz) return null to cols
        if (landscape && w < h) { val t = w; w = h; h = t }
        return OdfPageSetup(w, h, ml, mr, mt, mb) to cols
    }

    // ---- Headers / footers ----

    private fun parseHeadersFooters(
        pkg: OoxmlPackage, docPart: String, rels: Map<String, OoxmlPackage.Rel>,
        styles: Styles, theme: OoxmlTheme
    ): Pair<List<OdfParagraph>, List<OdfParagraph>> {
        fun firstOfType(typeSuffix: String): List<OdfParagraph> {
            val rel = rels.values.firstOrNull { it.type?.endsWith(typeSuffix) == true } ?: return emptyList()
            val xml = pkg.entries[rel.target] ?: return emptyList()
            val ctx = DocxCtx(pkg, rel.target, theme, styles, Numbering(emptyMap(), emptyMap()), pkg.relsFor(rel.target), emptyMap(), emptyMap(), emptyMap())
            val blocks = mutableListOf<OdfContentBlock>()
            val parser = OoxmlXml.newParser(xml)
            var e = parser.eventType
            while (e != XmlPullParser.END_DOCUMENT) {
                if (e == XmlPullParser.START_TAG && parser.name == "p")
                    parseParagraph(parser, ctx, blocks)
                e = parser.next()
            }
            return blocks.filterIsInstance<OdfContentBlock.Paragraph>().map { it.paragraph }
        }
        return firstOfType("header") to firstOfType("footer")
    }

    // ---- Notes / comments ----

    private fun parseNotes(xml: String?, tag: String, styles: Styles, theme: OoxmlTheme, isEndnote: Boolean): Map<String, OdfFootnote> {
        if (xml == null) return emptyMap()
        val parser = OoxmlXml.newParser(xml)
        val out = LinkedHashMap<String, OdfFootnote>()
        val elemName = "${tag}"
        var counter = 0
        var e = parser.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG && parser.name == elemName) {
                val id = OoxmlXml.attr(parser, "id")
                val type = OoxmlXml.attr(parser, "type")
                if (id != null && type != "separator" && type != "continuationSeparator") {
                    counter++
                    val paras = readNoteBody(parser, elemName, styles, theme)
                    out[id] = OdfFootnote(citation = counter.toString(), body = paras, isEndnote = isEndnote)
                }
            }
            e = parser.next()
        }
        return out
    }

    private fun readNoteBody(parser: XmlPullParser, endTag: String, styles: Styles, theme: OoxmlTheme): List<OdfParagraph> {
        val depth = parser.depth
        val ctx = DocxCtx(OoxmlPackage(emptyMap(), emptyMap()), "", theme, styles, Numbering(emptyMap(), emptyMap()), emptyMap(), emptyMap(), emptyMap(), emptyMap())
        val blocks = mutableListOf<OdfContentBlock>()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == endTag)) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG && parser.name == "p") parseParagraph(parser, ctx, blocks)
            e = parser.next()
        }
        return blocks.filterIsInstance<OdfContentBlock.Paragraph>().map { it.paragraph }
    }

    private fun parseComments(xml: String?, styles: Styles, theme: OoxmlTheme): Map<String, OdfAnnotation> {
        if (xml == null) return emptyMap()
        val parser = OoxmlXml.newParser(xml)
        val out = LinkedHashMap<String, OdfAnnotation>()
        var e = parser.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG && parser.name == "comment") {
                val id = OoxmlXml.attr(parser, "id")
                val author = OoxmlXml.attr(parser, "author")
                val date = OoxmlXml.attr(parser, "date")
                if (id != null) {
                    val paras = readNoteBody(parser, "comment", styles, theme)
                    out[id] = OdfAnnotation(author = author, date = date, paragraphs = paras)
                }
            }
            e = parser.next()
        }
        return out
    }

    // ---- Drawings / images ----

    private fun parseDrawing(parser: XmlPullParser, ctx: DocxCtx) {
        val relsNs = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        val depth = parser.depth
        val endTag = parser.name
        var cx = 0L; var cy = 0L; var embed: String? = null
        var title: String? = null; var desc: String? = null; var rot = 0
        var chartRid: String? = null
        var dmRid: String? = null
        var cropL = 0f; var cropT = 0f; var cropR = 0f; var cropB = 0f
        val textboxParas = mutableListOf<OdfParagraph>()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == endTag)) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "extent" -> { cx = OoxmlXml.attr(parser, "cx")?.toLongOrNull() ?: 0L; cy = OoxmlXml.attr(parser, "cy")?.toLongOrNull() ?: 0L }
                "docPr" -> { title = OoxmlXml.attr(parser, "title") ?: OoxmlXml.attr(parser, "name"); desc = OoxmlXml.attr(parser, "descr") }
                "xfrm" -> OoxmlXml.attr(parser, "rot")?.toIntOrNull()?.let { rot = it }
                "blip" -> if (embed == null) embed = OoxmlXml.attrNs(parser, relsNs, "embed") ?: OoxmlXml.attr(parser, "embed")
                "srcRect" -> {
                    cropL = (OoxmlXml.attr(parser, "l")?.toIntOrNull() ?: 0) / 100000f
                    cropT = (OoxmlXml.attr(parser, "t")?.toIntOrNull() ?: 0) / 100000f
                    cropR = (OoxmlXml.attr(parser, "r")?.toIntOrNull() ?: 0) / 100000f
                    cropB = (OoxmlXml.attr(parser, "b")?.toIntOrNull() ?: 0) / 100000f
                }
                "chart" -> chartRid = OoxmlXml.attrNs(parser, relsNs, "id") ?: OoxmlXml.attr(parser, "id")
                "relIds" -> dmRid = OoxmlXml.attrNs(parser, relsNs, "dm")
                "txbxContent" -> parseTextboxContent(parser, ctx, textboxParas)
            }
            e = parser.next()
        }
        // Chart takes priority, then image, then text box.
        if (chartRid != null) {
            val target = ctx.rels[chartRid]?.target
            val chartXml = target?.let { ctx.pkg.entries[it] }
            if (chartXml != null) OoxmlChart.parse(chartXml, ctx.theme)?.let { ctx.pendingBlocks.add(OdfContentBlock.Chart(it)); return }
        }
        if (embed != null) {
            val target = ctx.rels[embed]?.target
            val bytes = target?.let { ctx.pkg.mediaBytes(it) }
            if (bytes != null) {
                val path = "media/${target.substringAfterLast('/')}"
                ctx.extraImages[path] = bytes
                ctx.pendingBlocks.add(OdfContentBlock.Image(OdfImage(
                    path = path, imageData = bytes,
                    width = OoxmlUnits.emuToPx(cx), height = OoxmlUnits.emuToPx(cy),
                    rotationDegrees = OoxmlUnits.angle60000ToDeg(rot),
                    cropLeftPct = cropL, cropTopPct = cropT, cropRightPct = cropR, cropBottomPct = cropB,
                    altTitle = title, altDesc = desc
                )))
                return
            }
        }
        for (p in textboxParas) ctx.pendingBlocks.add(OdfContentBlock.Paragraph(p))
        // SmartArt: extract diagram text (best-effort) if no image/chart/textbox.
        if (chartRid == null && embed == null && textboxParas.isEmpty() && dmRid != null) {
            val dataPart = ctx.rels[dmRid]?.target
            for (line in OoxmlDiagram.extractText(ctx.pkg, dataPart)) {
                ctx.pendingBlocks.add(OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(line)))))
            }
        }
    }

    private fun parseTextboxContent(parser: XmlPullParser, ctx: DocxCtx, out: MutableList<OdfParagraph>) {
        val depth = parser.depth
        val blocks = mutableListOf<OdfContentBlock>()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "txbxContent")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG && parser.name == "p") parseParagraph(parser, ctx, blocks)
            e = parser.next()
        }
        out.addAll(blocks.filterIsInstance<OdfContentBlock.Paragraph>().map { it.paragraph })
    }

    // ---- Mapping helpers ----

    private fun jcToAlign(jc: String?): TextAlign? = when (jc) {
        "center" -> TextAlign.Center
        "right", "end" -> TextAlign.End
        "both", "distribute" -> TextAlign.Justify
        "left", "start" -> TextAlign.Start
        else -> null
    }

    private fun paragraphStyle(styleId: String?, outline: Int?): ParagraphStyle {
        outline?.let {
            return when (it) { 0 -> ParagraphStyle.HEADING1; 1 -> ParagraphStyle.HEADING2; 2 -> ParagraphStyle.HEADING3; else -> ParagraphStyle.HEADING4 }
        }
        if (styleId == null) return ParagraphStyle.BODY
        return when {
            styleId.equals("Title", true) || styleId.equals("Heading1", true) -> ParagraphStyle.HEADING1
            styleId.equals("Subtitle", true) || styleId.equals("Heading2", true) -> ParagraphStyle.HEADING2
            styleId.equals("Heading3", true) -> ParagraphStyle.HEADING3
            styleId.startsWith("Heading", true) -> ParagraphStyle.HEADING4
            else -> ParagraphStyle.BODY
        }
    }

    private fun mapUnderline(v: String?): String? = when (v) {
        null -> "solid"
        "none" -> "none"
        "single", "words" -> "solid"
        "double" -> "double"
        "dotted", "dottedHeavy" -> "dotted"
        "dash", "dashLong", "dashedHeavy" -> "dash"
        "wave", "wavyHeavy", "wavyDouble" -> "wave"
        else -> "solid"
    }

    private fun mapNumFmt(fmt: String): String = when (fmt) {
        "decimal", "decimalZero" -> "1"
        "lowerLetter" -> "a"
        "upperLetter" -> "A"
        "lowerRoman" -> "i"
        "upperRoman" -> "I"
        else -> "1"
    }

    private fun mapBullet(lvlText: String): String {
        val ch = lvlText.firstOrNull() ?: return "\u2022"
        return when (ch.code) {
            0xF0B7, 0x2022 -> "\u2022"
            0xF0A7, 0x25AA -> "\u25AA"
            0xF06F, 0x006F -> "\u25E6"
            0xF0D8 -> "\u27A2"
            else -> if (ch.isLetterOrDigit() || ch.code < 0x20) "\u2022" else ch.toString()
        }
    }

    private fun mapWordTheme(name: String): String = when (name.lowercase()) {
        "text1", "dark1" -> "dk1"
        "background1", "light1" -> "lt1"
        "text2", "dark2" -> "dk2"
        "background2", "light2" -> "lt2"
        "hyperlink" -> "hlink"
        "followedhyperlink" -> "folhlink"
        else -> name
    }

    private fun symChar(code: String?): String {
        val v = code?.removePrefix("F0")?.toIntOrNull(16) ?: code?.toIntOrNull(16) ?: return ""
        // Common Wingdings/Symbol mappings; fall back to the raw code point.
        return when (v) {
            0xB7, 0xA7 -> "\u2022"
            0x28 -> "\u260E"
            else -> if (v in 0x20..0x7E) v.toChar().toString() else String(Character.toChars(v))
        }
    }
}
