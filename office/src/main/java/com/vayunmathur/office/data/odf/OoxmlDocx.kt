package com.vayunmathur.office.data.odf

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import com.vayunmathur.library.ui.odf.ListType
import com.vayunmathur.library.ui.odf.OdfAnnotation
import com.vayunmathur.library.ui.odf.OdfBookmark
import com.vayunmathur.library.ui.odf.OdfBorders
import com.vayunmathur.library.ui.odf.OdfChange
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfFootnote
import com.vayunmathur.library.ui.odf.OdfPageSetup
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.ParagraphStyle
import org.xmlpull.v1.XmlPullParser

/**
 * DOCX importer (Groups 1-5, phases D1-D16). Resolves styles.xml inheritance, numbering.xml lists,
 * rich run/paragraph formatting, tables with merges & decoration, sections/page setup, headers &
 * footers, footnotes/comments/bookmarks, hyperlinks & fields, images, text boxes, equations,
 * embedded charts, tracked changes, TOC and content controls. Best-effort onto the ODF model.
 */
internal object OoxmlDocx {

    private const val LINE_RULE_DIVISOR = 240f
    private const val TWIPS_PER_PT = 20f
    internal const val OOXML_THOUSANDTHS = 100000f
    private const val WINGDINGS_MIDDOT = 0xB7
    private const val WINGDINGS_BULLET = 0xA7
    private const val WINGDINGS_PHONE = 0x28
    private const val PRINTABLE_MIN = 0x20
    private const val BODY_DEPTH = 3
    private const val PRINTABLE_MAX = 0x7E

    // ---- Style model: see OoxmlDocxStyles (split for file length; behavior identical) ----

    internal typealias RPr = OoxmlDocxStyles.RPr
    internal typealias PPr = OoxmlDocxStyles.PPr
    internal typealias StyleDef = OoxmlDocxStyles.StyleDef
    internal typealias Styles = OoxmlDocxStyles.Styles
    internal typealias NumLevel = OoxmlDocxStyles.NumLevel
    internal typealias Numbering = OoxmlDocxStyles.Numbering

    // ---- Entry point ----

    fun import(pkg: OoxmlPackage, fileName: String): OdfDocument.TextDocument {
        val docPart = "word/document.xml"
        val xml = pkg.entries[docPart] ?: return OdfDocument.TextDocument(fileName, emptyList())
        val theme = OoxmlTheme.parse(pkg.entries["word/theme/theme1.xml"])
        val styles = pkg.entries["word/styles.xml"]?.let { OoxmlDocxStyles.parseStyles(it, theme) }
            ?: Styles(emptyMap(), RPr(), PPr(), null)
        val numbering = pkg.entries["word/numbering.xml"]?.let { OoxmlDocxStyles.parseNumbering(it) }
            ?: Numbering(emptyMap(), emptyMap())
        val rels = pkg.relsFor(docPart)

        val footnotesById = parseNotes(pkg.entries["word/footnotes.xml"], "footnote", styles, theme, false)
        val endnotesById = parseNotes(pkg.entries["word/endnotes.xml"], "endnote", styles, theme, true)
        val comments = parseComments(pkg.entries["word/comments.xml"], styles, theme)

        val ctx = DocxCtx(pkg, docPart, theme, styles, numbering, rels, footnotesById, endnotesById, comments)
        val content = mutableListOf<OdfContentBlock>()
        parseBody(OoxmlXml.newParser(xml), ctx, content)

        // Headers / footers (first referenced default of each).
        val headerFooter = parseHeadersFooters(pkg, rels, styles, theme)
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

    internal class DocxCtx(
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
        var imageSeq: Int = 0,
        // Running list-item counters keyed by numId -> (ilvl -> count), for 1/2/3 numbering.
        val listCounters: HashMap<Int, HashMap<Int, Int>> = HashMap()
    )

    // ---- Body ----

    private fun parseBody(parser: XmlPullParser, ctx: DocxCtx, out: MutableList<OdfContentBlock>) {
        var event = parser.eventType
        var inBody = false
        var columnCount = 1
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                columnCount = applyBodyTag(parser, ctx, out, inBody, columnCount).let { (body, cols) ->
                    inBody = body
                    cols
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "body") inBody = false
            event = parser.next()
        }
        // Wrap in a multi-column section if the (last) sectPr declared columns.
        if (columnCount > 1) {
            out.add(0, OdfContentBlock.SectionStart("Section", columnCount))
            out.add(OdfContentBlock.SectionEnd)
        }
    }

    /** Apply one body-level tag; returns (inBody, columnCount). */
    private fun applyBodyTag(
        parser: XmlPullParser,
        ctx: DocxCtx,
        out: MutableList<OdfContentBlock>,
        inBody: Boolean,
        columnCount: Int,
    ): Pair<Boolean, Int> {
        var body = inBody
        var cols = columnCount
        when (parser.name) {
            "body" -> body = true
            "p" -> if (body) parseParagraph(parser, ctx, out)
            "tbl" -> if (body) out.add(OdfContentBlock.Table(OoxmlDocxTables.parseTable(parser, ctx)))
            "sectPr" -> if (body && parser.depth <= BODY_DEPTH) {
                val sect = parseSectPr(parser)
                ctx.pageSetup = sect.first
                cols = sect.second
            }
            "oMathPara" -> if (body) convertOMathPara(parser)?.let { out.add(OdfContentBlock.Formula(it)) }
        }
        return body to cols
    }

    /** Converts an m:oMathPara wrapper by descending to its inner m:oMath. */
    private fun convertOMathPara(parser: XmlPullParser): String? {
        val depth = parser.depth
        var result: String? = null
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "oMathPara")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG && parser.name == "oMath" && result == null) result =
                OmmlToMathml.convertElement(parser)
            e = parser.next()
        }
        return result
    }

    private class ParaAcc(
        val spans: MutableList<OdfSpan> = mutableListOf(),
        var ppr: PPr = PPr(),
        var mathml: String? = null,
        val fieldState: FieldState = FieldState(),
        var blockStart: Int = 0,
    )

    internal fun parseParagraph(parser: XmlPullParser, ctx: DocxCtx, out: MutableList<OdfContentBlock>) {
        val depth = parser.depth
        val acc = ParaAcc(blockStart = ctx.pendingBlocks.size)
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "p")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) applyParaTag(parser, ctx, acc, out)
            e = parser.next()
        }
        flushParagraph(ctx, out, acc)
    }

    private fun applyParaTag(parser: XmlPullParser, ctx: DocxCtx, acc: ParaAcc, out: MutableList<OdfContentBlock>) {
        if (applyParaContentTag(parser, ctx, acc)) return
        applyParaMetaTag(parser, ctx, acc, out.size)
    }

    private fun applyParaContentTag(parser: XmlPullParser, ctx: DocxCtx, acc: ParaAcc): Boolean {
        when (parser.name) {
            "pPr" -> acc.ppr = OoxmlDocxStyles.parsePPr(parser, ctx.theme)
            "r" -> parseRun(parser, ctx, acc.ppr, acc.spans, acc.fieldState, null, null)
            "hyperlink" -> parseHyperlink(parser, ctx, acc.ppr, acc.spans)
            "sdt" -> parseSdtInline(parser, ctx, acc.spans)
            else -> return false
        }
        return true
    }

    private fun applyParaMetaTag(
        parser: XmlPullParser, ctx: DocxCtx, acc: ParaAcc, blockIndex: Int
    ) {
        when (parser.name) {
            "ins" -> parseChangeWrapper(parser, ctx, acc.ppr, acc.spans, "insertion")
            "del" -> parseChangeWrapper(parser, ctx, acc.ppr, acc.spans, "deletion")
            "bookmarkStart" -> OoxmlXml.attr(parser, "name")?.let { ctx.bookmarks.add(OdfBookmark(it, blockIndex)) }
            "oMath" -> if (acc.mathml == null) acc.mathml = OmmlToMathml.convertElement(parser)
        }
    }

    private fun flushParagraph(ctx: DocxCtx, out: MutableList<OdfContentBlock>, acc: ParaAcc) {
        val newBlocks = if (ctx.pendingBlocks.size > acc.blockStart) {
            val list = ctx.pendingBlocks.subList(acc.blockStart, ctx.pendingBlocks.size)
            val copy = list.toList(); list.clear(); copy
        } else emptyList()

        if (acc.mathml != null && acc.spans.all { it.text.isBlank() } && newBlocks.isEmpty()) {
            out.add(OdfContentBlock.Formula(acc.mathml!!)); return
        }
        if (acc.spans.isNotEmpty() || newBlocks.isEmpty()) out.add(OdfContentBlock.Paragraph(buildParagraph(
            acc.ppr,
            ctx,
            acc.spans)))
        out.addAll(newBlocks)
    }

    private fun buildParagraph(ppr: PPr, ctx: DocxCtx, spans: List<OdfSpan>): OdfParagraph {
        val styles = ctx.styles
        val eff = styles.resolvedPPr(ppr.styleId ?: styles.defaultParaStyle).overlay(ppr)
        val outline = ppr.outlineLvl ?: styles.outlineLvl(ppr.styleId)
        val style = paragraphStyle(ppr.styleId, outline)
        val layout = paragraphLayout(eff)
        val numbering = paragraphNumbering(eff, ctx)

        return OdfParagraph(
            spans = spans.ifEmpty { listOf(OdfSpan("")) },
            style = if (numbering.isList && style == ParagraphStyle.BODY) ParagraphStyle.LIST_ITEM else style,
            alignment = layout.align,
            marginLeft = layout.marginLeft,
            marginRight = layout.marginRight,
            marginTop = layout.marginTop,
            marginBottom = layout.marginBottom,
            textIndent = layout.textIndent,
            backgroundColor = eff.shdFill,
            listLevel = numbering.listLevel,
            listType = numbering.listType,
            listItemIndex = numbering.listItemIndex,
            direction = layout.direction,
            lineHeightPercent = layout.lineHeight,
            borders = eff.borders?.takeIf { !it.isEmpty() },
            borderColor = eff.borders?.let { OdfBorders.renderColor(it.top ?: it.left) },
            listNumberFormat = numbering.numFmt,
            listBulletChar = numbering.bulletChar,
            listNumberPrefix = numbering.prefix,
            listNumberSuffix = numbering.suffix,
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

    /** Paragraph layout (alignment/margins/indent/direction/line height). */
    private class ParaLayout(
        val align: TextAlign?,
        val direction: LayoutDirection?,
        val marginLeft: Float,
        val marginRight: Float,
        val textIndent: Float,
        val marginTop: Float,
        val marginBottom: Float,
        val lineHeight: Float?,
    )

    /** Paragraph layout from effective properties. */
    private fun paragraphLayout(eff: PPr): ParaLayout {
        val margins = layoutMargins(eff)
        val indents = layoutIndents(eff)
        // Absent w:lineRule defaults to "auto" per the spec (line is then in 240ths = multiples).
        val lineHeight =
            if ((eff.lineRule == "auto" || eff.lineRule == null) && eff.line != null) {
                eff.line!! / LINE_RULE_DIVISOR
            } else {
                null
            }
        return ParaLayout(
            jcToAlign(eff.jc), layoutDirection(eff), margins.first, margins.second,
            indents, margins.third, margins.fourth, lineHeight)
    }

    private fun layoutDirection(eff: PPr): LayoutDirection? {
        return when (eff.bidi) { true -> LayoutDirection.Rtl; false -> LayoutDirection.Ltr; null -> null }
    }

    private fun layoutMargins(eff: PPr): Quadruple {
        val left = eff.indLeft?.let { OoxmlUnits.twipsToPx(it) } ?: 0f
        val right = eff.indRight?.let { OoxmlUnits.twipsToPx(it) } ?: 0f
        val top = eff.spacingBefore?.let { OoxmlUnits.twipsToPx(it) } ?: 0f
        val bottom = eff.spacingAfter?.let { OoxmlUnits.twipsToPx(it) } ?: 0f
        return Quadruple(left, right, top, bottom)
    }

    private data class Quadruple(val first: Float, val second: Float, val third: Float, val fourth: Float)

    private fun layoutIndents(eff: PPr): Float {
        return when {
            eff.indHanging != null -> -OoxmlUnits.twipsToPx(eff.indHanging!!)
            eff.indFirstLine != null -> OoxmlUnits.twipsToPx(eff.indFirstLine!!)
            else -> 0f
        }
    }

    /** Paragraph numbering state. */
    private class ParaNumbering(
        var listLevel: Int = 0,
        var listType: ListType = ListType.BULLET,
        var numFmt: String = "1",
        var bulletChar: String = "\u2022",
        var prefix: String = "",
        var suffix: String = ".",
        var isList: Boolean = false,
        var listItemIndex: Int = 0,
    )

    /** Paragraph numbering from effective properties + numbering tables. */
    private fun paragraphNumbering(eff: PPr, ctx: DocxCtx): ParaNumbering {
        val numbering = ParaNumbering()
        if (eff.numId != null && eff.numId != 0) {
            val ilvl = eff.ilvl ?: 0
            ctx.numbering.level(eff.numId!!, ilvl)?.let { lvl ->
                numbering.isList = true
                numbering.listLevel = ilvl
                if (lvl.numFmt == "bullet") {
                    numbering.listType = ListType.BULLET
                    numbering.bulletChar = OoxmlDocxStyles.mapBullet(lvl.lvlText)
                } else {
                    numbering.listType = ListType.NUMBERED
                    numbering.numFmt = OoxmlDocxStyles.mapNumFmt(lvl.numFmt)
                    val markers = Regex("%\\d+").findAll(lvl.lvlText).toList()
                    if (markers.isNotEmpty()) {
                        numbering.prefix = lvl.lvlText.substring(0, markers.first().range.first)
                        numbering.suffix = lvl.lvlText.substring(markers.last().range.last + 1)
                    }
                }
                // Assign a running 1/2/3 index: increment this level, reset any deeper levels.
                val counters = ctx.listCounters.getOrPut(eff.numId!!) { HashMap() }
                val next = (counters[ilvl] ?: (lvl.start - 1)) + 1
                counters[ilvl] = next
                counters.keys.filter { it > ilvl }.toList().forEach { counters.remove(it) }
                numbering.listItemIndex = next
            }
        }
        return numbering
    }

    // ---- Runs ----

    internal class FieldState {
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
        val run = RunAcc(rpr = RPr())
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "r")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) applyRunTag(parser, ctx, run, field, spans)
            e = parser.next()
        }

        // Effective run props: docDefaults+paragraph style < paragraph mark < character style < direct rPr.
        val base = ctx.styles.resolvedRPr(ppr.styleId ?: ctx.styles.defaultParaStyle)
        val paraMark = ppr.rPr ?: RPr()
        val charStyle = ctx.styles.charStyleRPr(run.rpr.styleId)
        val eff = base.overlay(paraMark).overlay(charStyle).overlay(run.rpr)

        if (eff.vanish == true) return
        val citation = run.noteCitation
        if (citation != null) {
            spans.add(OdfSpan(text = citation, superscript = true))
            @Suppress("UNUSED_VALUE") run { run.isEndnote = run.isEndnote }
            return
        }
        if (run.sb.isEmpty()) return

        val href = forcedHref
        spans.add(toSpan(run.sb.toString(), eff, href, changeKind, changeId))
    }

    /** Run accumulation state. */
    private class RunAcc(
        var rpr: RPr = RPr(),
        val sb: StringBuilder = StringBuilder(),
        var noteCitation: String? = null,
        var isEndnote: Boolean = false,
    )

    /** Apply one run child tag. */
    private fun applyRunTag(
        parser: XmlPullParser,
        ctx: DocxCtx,
        run: RunAcc,
        field: FieldState,
        spans: MutableList<OdfSpan>,
    ) {
        when (parser.name) {
            "rPr" -> run.rpr = OoxmlDocxStyles.parseRPr(parser, ctx.theme)
            "t" -> run.sb.append(OoxmlXml.readElementText(parser, "t"))
            "tab" -> run.sb.append("\t")
            "br", "cr" -> run.sb.append("\n")
            "noBreakHyphen" -> run.sb.append("\u2011")
            "softHyphen" -> run.sb.append("\u00AD")
            "sym" -> run.sb.append(symChar(OoxmlXml.attr(parser, "char")))
            "fldChar" -> handleFldChar(parser, field, spans)
            "instrText" -> if (field.capturing) field.instr.append(OoxmlXml.readElementText(parser, "instrText"))
            "footnoteReference" -> { run.noteCitation = registerNote(ctx, ctx.footnotes, OoxmlXml.attr(parser, "id")); }
            "endnoteReference" -> { run.noteCitation = registerNote(
                ctx,
                ctx.endnotes,
                OoxmlXml.attr(parser, "id")); run.isEndnote = true }
            "drawing", "pict" -> OoxmlDocxDrawings.parseDrawing(parser, ctx)
        }
    }

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
            letterSpacing = e.spacingTwips?.let { it / TWIPS_PER_PT },
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

    private fun parseChangeWrapper(
        parser: XmlPullParser,
        ctx: DocxCtx,
        ppr: PPr,
        spans: MutableList<OdfSpan>,
        kind: String) {
        val depth = parser.depth
        val endTag = if (kind == "insertion") "ins" else "del"
        val author = OoxmlXml.attr(parser, "author")
        val date = OoxmlXml.attr(parser, "date")
        val id = ctx.newChangeId(kind, author, date)
        val field = FieldState()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == endTag)) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG && parser.name == "r") parseRun(
                parser,
                ctx,
                ppr,
                spans,
                field,
                null,
                kind,
                id)
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
            if (e == XmlPullParser.START_TAG && parser.name == "r") parseRun(
                parser,
                ctx,
                PPr(),
                spans,
                field,
                null,
                null)
            e = parser.next()
        }
    }

    private fun registerNote(ctx: DocxCtx, notes: Map<String, OdfFootnote>, id: String?): String? {
        val note = id?.let { notes[it] } ?: return id
        if (ctx.usedNotes.none { it === note }) ctx.usedNotes.add(note)
        return note.citation
    }

    // ---- Property parsers/styles/numbering: see OoxmlDocxStyles (split for file length) ----

    // ---- Tables: see OoxmlDocxTables (split for file length; behavior identical) ----

    // ---- Sections / page setup ----

    private fun parseSectPr(parser: XmlPullParser): Pair<OdfPageSetup?, Int> {
        val depth = parser.depth
        val sect = SectAcc()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "sectPr")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) applySectTag(parser, sect)
            e = parser.next()
        }
        if (!sect.hasPgSz) return null to sect.cols
        if (sect.landscape && sect.w < sect.h) { val t = sect.w; sect.w = sect.h; sect.h = t }
        return OdfPageSetup(sect.w, sect.h, sect.ml, sect.mr, sect.mt, sect.mb) to sect.cols
    }

    /** Section/page-setup accumulation state. */
    private class SectAcc(
        var w: Float = 793.7f,
        var h: Float = 1122.5f,
        var ml: Float = 75.6f,
        var mr: Float = 75.6f,
        var mt: Float = 75.6f,
        var mb: Float = 75.6f,
        var landscape: Boolean = false,
        var cols: Int = 1,
        var hasPgSz: Boolean = false,
    )

    /** Apply one sectPr child tag. */
    private fun applySectTag(parser: XmlPullParser, sect: SectAcc) {
        when (parser.name) {
            "pgSz" -> {
                sect.hasPgSz = true
                OoxmlXml.attr(parser, "w")?.toIntOrNull()?.let { sect.w = OoxmlUnits.twipsToPx(it) }
                OoxmlXml.attr(parser, "h")?.toIntOrNull()?.let { sect.h = OoxmlUnits.twipsToPx(it) }
                sect.landscape = OoxmlXml.attr(parser, "orient") == "landscape"
            }
            "pgMar" -> {
                OoxmlXml.attr(parser, "left")?.toIntOrNull()?.let { sect.ml = OoxmlUnits.twipsToPx(it) }
                OoxmlXml.attr(parser, "right")?.toIntOrNull()?.let { sect.mr = OoxmlUnits.twipsToPx(it) }
                OoxmlXml.attr(parser, "top")?.toIntOrNull()?.let { sect.mt = OoxmlUnits.twipsToPx(it) }
                OoxmlXml.attr(parser, "bottom")?.toIntOrNull()?.let { sect.mb = OoxmlUnits.twipsToPx(it) }
            }
            "cols" -> sect.cols = OoxmlXml.attr(parser, "num")?.toIntOrNull() ?: 1
        }
    }

    // ---- Headers / footers ----

    private fun parseHeadersFooters(
        pkg: OoxmlPackage, rels: Map<String, OoxmlPackage.Rel>,
        styles: Styles, theme: OoxmlTheme
    ): Pair<List<OdfParagraph>, List<OdfParagraph>> {
        fun firstOfType(typeSuffix: String): List<OdfParagraph> {
            val rel = rels.values.firstOrNull { it.type?.endsWith(typeSuffix) == true } ?: return emptyList()
            val xml = pkg.entries[rel.target] ?: return emptyList()
            val ctx = DocxCtx(
                pkg,
                rel.target,
                theme,
                styles,
                Numbering(emptyMap(), emptyMap()),
                pkg.relsFor(rel.target),
                emptyMap(),
                emptyMap(),
                emptyMap())
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

    private fun parseNotes(
        xml: String?,
        tag: String,
        styles: Styles,
        theme: OoxmlTheme,
        isEndnote: Boolean): Map<String, OdfFootnote> {
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

    private fun readNoteBody(
        parser: XmlPullParser,
        endTag: String,
        styles: Styles,
        theme: OoxmlTheme): List<OdfParagraph> {
        val depth = parser.depth
        val ctx = DocxCtx(
            OoxmlPackage(emptyMap(), emptyMap()),
            "",
            theme,
            styles,
            Numbering(emptyMap(), emptyMap()),
            emptyMap(),
            emptyMap(),
            emptyMap(),
            emptyMap())
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

    // ---- Drawings / images: see OoxmlDocxDrawings (split for file length; behavior identical) ----

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
            return when (it) {
                0 -> ParagraphStyle.HEADING1
                1 -> ParagraphStyle.HEADING2
                2 -> ParagraphStyle.HEADING3
                else -> ParagraphStyle.HEADING4
            }
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

    private fun symChar(code: String?): String {
        val v = code?.removePrefix("F0")?.toIntOrNull(16) ?: code?.toIntOrNull(16) ?: return ""
        // Common Wingdings/Symbol mappings; fall back to the raw code point.
        return when (v) {
            WINGDINGS_MIDDOT, WINGDINGS_BULLET -> "\u2022"
            WINGDINGS_PHONE -> "\u260E"
            else -> if (v in PRINTABLE_MIN..PRINTABLE_MAX) {
                v.toChar().toString()
            } else {
                String(Character.toChars(v))
            }
        }
    }
}
