package com.vayunmathur.office.odf

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Shared XML pull-parsing helpers used by the OOXML importers (docx/xlsx/pptx). Kept
 * namespace-aware but matched on local names, since OOXML mixes many namespaces (w/a/r/wp/…)
 * and we only ever care about the local element/attribute name.
 */
internal object OoxmlXml {

    fun newParser(xml: String): XmlPullParser {
        val f = XmlPullParserFactory.newInstance()
        f.isNamespaceAware = true
        val p = f.newPullParser()
        p.setInput(xml.reader())
        return p
    }

    /** Value of the attribute whose local name is [localName], or null. */
    fun attr(parser: XmlPullParser, localName: String): String? {
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeName(i) == localName) return parser.getAttributeValue(i)
        }
        return null
    }

    /**
     * Value of an attribute, disambiguated by namespace prefix's local semantics: tries local
     * name first, useful for attributes like r:id vs w:id where both have local name "id". Pass
     * [ns] as the namespace URI to require a specific namespace.
     */
    fun attrNs(parser: XmlPullParser, ns: String, localName: String): String? {
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeName(i) == localName && parser.getAttributeNamespace(i) == ns) {
                return parser.getAttributeValue(i)
            }
        }
        return null
    }

    /** Reads concatenated text content up to the end tag [endTag] at the current element's depth. */
    fun readElementText(parser: XmlPullParser, endTag: String): String {
        val sb = StringBuilder()
        val depth = parser.depth
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == endTag)) {
            if (e == XmlPullParser.TEXT) sb.append(parser.text)
            if (e == XmlPullParser.END_DOCUMENT) break
            e = parser.next()
        }
        return sb.toString()
    }

    /**
     * Iterates START_TAG events strictly inside the element named [endTag] at the current
     * depth, invoking [onStart] for each. Consumes through the matching END_TAG. The parser
     * must currently be positioned on the opening [endTag] START_TAG.
     */
    inline fun forEachChild(parser: XmlPullParser, endTag: String, onStart: (String) -> Unit) {
        val depth = parser.depth
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == endTag)) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) onStart(parser.name)
            e = parser.next()
        }
    }

    /** Skips the current element (must be on its START_TAG) and everything inside it. */
    fun skipElement(parser: XmlPullParser) {
        val depth = parser.depth
        val name = parser.name
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == name)) {
            if (e == XmlPullParser.END_DOCUMENT) break
            e = parser.next()
        }
    }

    /** OOXML boolean attribute: absent -> true (the toggle is on), else "1"/"true"/"on". */
    fun boolAttr(v: String?): Boolean = v == null || v == "1" || v == "true" || v == "on"

    /** Column index (0-based) from an A1-style cell reference (e.g. "AB12" -> 27). */
    fun colIndex(cellRef: String): Int {
        var n = 0
        for (c in cellRef) {
            if (c.isLetter()) n = n * 26 + (c.uppercaseChar() - 'A' + 1) else break
        }
        return (n - 1).coerceAtLeast(0)
    }

    /** Row index (0-based) from an A1-style cell reference (e.g. "AB12" -> 11), or -1. */
    fun rowIndex(cellRef: String): Int {
        val digits = cellRef.dropWhile { it.isLetter() }
        return (digits.toIntOrNull() ?: 0) - 1
    }
}
