package com.vayunmathur.office.odf

import org.xmlpull.v1.XmlPullParser

/**
 * Converts Office Math Markup (OMML, `m:oMath`) to a MathML string (Phases C4/D15). Covers runs,
 * fractions, sub/superscripts, radicals, n-ary operators (sum/prod/integral) with limits,
 * delimiters, functions, and matrices. Anything unrecognized degrades to its inner text.
 */
internal object OmmlToMathml {

    private const val NS = "http://www.w3.org/1998/Math/MathML"

    /** Finds the first `m:oMath` in [xml] and converts it, or returns null if none. */
    fun convert(xml: String): String? {
        val parser = OoxmlXml.newParser(xml)
        var e = parser.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG && parser.name == "oMath") return convertElement(parser)
            e = parser.next()
        }
        return null
    }

    /** Converts the `m:oMath` element the parser is currently positioned on. */
    fun convertElement(parser: XmlPullParser): String {
        val inner = renderChildren(parser, "oMath")
        return "<math xmlns=\"$NS\" display=\"block\"><mrow>$inner</mrow></math>"
    }

    /** Renders all OMML child nodes of the element named [endTag] (parser on its START_TAG). */
    private fun renderChildren(parser: XmlPullParser, endTag: String): String {
        val depth = parser.depth
        val sb = StringBuilder()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == endTag)) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) sb.append(renderNode(parser))
            e = parser.next()
        }
        return sb.toString()
    }

    private fun renderNode(parser: XmlPullParser): String = when (parser.name) {
        "r" -> renderRun(parser)
        "f" -> renderFrac(parser)
        "sSup" -> renderScript(parser, "sSup", "msup")
        "sSub" -> renderScript(parser, "sSub", "msub")
        "sSubSup" -> renderSubSup(parser)
        "rad" -> renderRad(parser)
        "nary" -> renderNary(parser)
        "d" -> renderDelim(parser)
        "func" -> renderFunc(parser)
        "m" -> renderMatrix(parser)
        "e", "num", "den", "sub", "sup", "deg", "fName", "oMath" -> row(renderChildren(parser, parser.name))
        else -> { OoxmlXml.skipElement(parser); "" }
    }

    private fun renderRun(parser: XmlPullParser): String {
        val depth = parser.depth
        val sb = StringBuilder()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "r")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG && parser.name == "t") sb.append(OoxmlXml.readElementText(parser, "t"))
            e = parser.next()
        }
        return classifyText(sb.toString())
    }

    /** Splits run text into <mn>/<mo>/<mi> tokens. */
    private fun classifyText(text: String): String {
        if (text.isEmpty()) return ""
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || (c == '.' && i + 1 < text.length && text[i + 1].isDigit()) -> {
                    val start = i
                    while (i < text.length && (text[i].isDigit() || text[i] == '.')) i++
                    out.append("<mn>").append(esc(text.substring(start, i))).append("</mn>")
                }
                c in "+-*/=<>±×÷⋅∙,()[]{}|" -> { out.append("<mo>").append(esc(c.toString())).append("</mo>"); i++ }
                c.isLetter() -> {
                    val start = i
                    while (i < text.length && text[i].isLetter()) i++
                    out.append("<mi>").append(esc(text.substring(start, i))).append("</mi>")
                }
                else -> { out.append("<mo>").append(esc(c.toString())).append("</mo>"); i++ }
            }
        }
        return out.toString()
    }

    private fun renderFrac(parser: XmlPullParser): String {
        var num = ""; var den = ""
        eachPart(parser, "f") { name ->
            when (name) { "num" -> num = renderChildren(parser, "num"); "den" -> den = renderChildren(parser, "den"); else -> OoxmlXml.skipElement(parser) }
        }
        return "<mfrac>${row(num)}${row(den)}</mfrac>"
    }

    private fun renderScript(parser: XmlPullParser, endTag: String, mml: String): String {
        var base = ""; var script = ""
        eachPart(parser, endTag) { name ->
            when (name) {
                "e" -> base = renderChildren(parser, "e")
                "sup", "sub" -> script = renderChildren(parser, name)
                else -> OoxmlXml.skipElement(parser)
            }
        }
        return "<$mml>${row(base)}${row(script)}</$mml>"
    }

    private fun renderSubSup(parser: XmlPullParser): String {
        var base = ""; var sub = ""; var sup = ""
        eachPart(parser, "sSubSup") { name ->
            when (name) {
                "e" -> base = renderChildren(parser, "e")
                "sub" -> sub = renderChildren(parser, "sub")
                "sup" -> sup = renderChildren(parser, "sup")
                else -> OoxmlXml.skipElement(parser)
            }
        }
        return "<msubsup>${row(base)}${row(sub)}${row(sup)}</msubsup>"
    }

    private fun renderRad(parser: XmlPullParser): String {
        var deg = ""; var body = ""; var degPresent = false
        eachPart(parser, "rad") { name ->
            when (name) {
                "deg" -> { deg = renderChildren(parser, "deg"); degPresent = true }
                "e" -> body = renderChildren(parser, "e")
                else -> OoxmlXml.skipElement(parser)
            }
        }
        return if (degPresent && deg.isNotBlank()) "<mroot>${row(body)}${row(deg)}</mroot>" else "<msqrt>${row(body)}</msqrt>"
    }

    private fun renderNary(parser: XmlPullParser): String {
        var chr = "\u222B" // default integral
        var sub = ""; var sup = ""; var body = ""
        eachPart(parser, "nary") { name ->
            when (name) {
                "naryPr" -> chr = readNaryChr(parser) ?: chr
                "sub" -> sub = renderChildren(parser, "sub")
                "sup" -> sup = renderChildren(parser, "sup")
                "e" -> body = renderChildren(parser, "e")
                else -> OoxmlXml.skipElement(parser)
            }
        }
        val op = "<mo>${esc(chr)}</mo>"
        val operator = when {
            sub.isNotBlank() && sup.isNotBlank() -> "<munderover>$op${row(sub)}${row(sup)}</munderover>"
            sub.isNotBlank() -> "<munder>$op${row(sub)}</munder>"
            sup.isNotBlank() -> "<mover>$op${row(sup)}</mover>"
            else -> op
        }
        return "<mrow>$operator${row(body)}</mrow>"
    }

    private fun readNaryChr(parser: XmlPullParser): String? {
        var chr: String? = null
        val depth = parser.depth
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "naryPr")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG && parser.name == "chr") chr = OoxmlXml.attr(parser, "val")
            e = parser.next()
        }
        return chr
    }

    private fun renderDelim(parser: XmlPullParser): String {
        var beg = "("; var end = ")"
        val parts = mutableListOf<String>()
        eachPart(parser, "d") { name ->
            when (name) {
                "dPr" -> { val d = readDelimChars(parser); beg = d.first ?: beg; end = d.second ?: end }
                "e" -> parts.add(renderChildren(parser, "e"))
                else -> OoxmlXml.skipElement(parser)
            }
        }
        val inner = parts.joinToString("<mo>,</mo>") { row(it) }
        return "<mrow><mo>${esc(beg)}</mo>$inner<mo>${esc(end)}</mo></mrow>"
    }

    private fun readDelimChars(parser: XmlPullParser): Pair<String?, String?> {
        var beg: String? = null; var end: String? = null
        val depth = parser.depth
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "dPr")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "begChr" -> beg = OoxmlXml.attr(parser, "val")
                "endChr" -> end = OoxmlXml.attr(parser, "val")
            }
            e = parser.next()
        }
        return beg to end
    }

    private fun renderFunc(parser: XmlPullParser): String {
        var fname = ""; var body = ""
        eachPart(parser, "func") { name ->
            when (name) {
                "fName" -> fname = renderChildren(parser, "fName")
                "e" -> body = renderChildren(parser, "e")
                else -> OoxmlXml.skipElement(parser)
            }
        }
        return "<mrow>${row(fname)}<mo>\u2061</mo>${row(body)}</mrow>"
    }

    private fun renderMatrix(parser: XmlPullParser): String {
        val rows = mutableListOf<List<String>>()
        eachPart(parser, "m") { name ->
            when (name) {
                "mr" -> {
                    val cells = mutableListOf<String>()
                    eachPart(parser, "mr") { cn -> if (cn == "e") cells.add(renderChildren(parser, "e")) else OoxmlXml.skipElement(parser) }
                    rows.add(cells)
                }
                else -> OoxmlXml.skipElement(parser)
            }
        }
        val body = rows.joinToString("") { r -> "<mtr>${r.joinToString("") { "<mtd>${row(it)}</mtd>" }}</mtr>" }
        return "<mtable>$body</mtable>"
    }

    /** Iterates the direct child START_TAGs of [endTag]; the callback must fully consume any it reads. */
    private inline fun eachPart(parser: XmlPullParser, endTag: String, onPart: (String) -> Unit) {
        val depth = parser.depth
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == endTag)) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) onPart(parser.name)
            e = parser.next()
        }
    }

    private fun row(inner: String): String =
        if (inner.isBlank()) "<mrow></mrow>" else "<mrow>$inner</mrow>"

    private fun esc(s: String): String = buildString {
        for (c in s) when (c) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;")
            '"' -> append("&quot;"); else -> append(c)
        }
    }
}
