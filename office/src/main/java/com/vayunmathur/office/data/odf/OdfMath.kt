package com.vayunmathur.office.data.odf

import com.vayunmathur.office.data.odf.MathNode.Fenced
import com.vayunmathur.office.data.odf.MathNode.Frac
import com.vayunmathur.office.data.odf.MathNode.Root
import com.vayunmathur.office.data.odf.MathNode.Row
import com.vayunmathur.office.data.odf.MathNode.Sqrt
import com.vayunmathur.office.data.odf.MathNode.Sub
import com.vayunmathur.office.data.odf.MathNode.SubSup
import com.vayunmathur.office.data.odf.MathNode.Sup
import com.vayunmathur.office.data.odf.MathNode.Token
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/** Parsed MathML presentation tree for layout rendering (D34). */
sealed class MathNode {
    /** Immutable value describing row; carries children. */
    data class Row(val children: List<MathNode>) : MathNode()
    /** Immutable value describing token; carries text, isOperator. */
    data class Token(val text: String, val isOperator: Boolean = false) : MathNode()
    /** Immutable value describing frac; carries numerator, denominator. */
    data class Frac(val numerator: MathNode, val denominator: MathNode) : MathNode()
    /** Immutable value describing sup; carries base, exponent. */
    data class Sup(val base: MathNode, val exponent: MathNode) : MathNode()
    /** Immutable value describing sub; carries base, subscript. */
    data class Sub(val base: MathNode, val subscript: MathNode) : MathNode()
    /** Immutable value describing sub sup; carries base, subscript, superscript. */
    data class SubSup(val base: MathNode, val subscript: MathNode, val superscript: MathNode) : MathNode()
    /** Immutable value describing sqrt; carries radicand. */
    data class Sqrt(val radicand: MathNode) : MathNode()
    /** Immutable value describing root; carries radicand, index. */
    data class Root(val radicand: MathNode, val index: MathNode) : MathNode()
    /** Immutable value describing fenced; carries open, close, body. */
    data class Fenced(val open: String, val close: String, val body: MathNode) : MathNode()
    /** Immutable value describing under; carries base, under. */
    data class Under(val base: MathNode, val under: MathNode) : MathNode()
    /** Immutable value describing over; carries base, over. */
    data class Over(val base: MathNode, val over: MathNode) : MathNode()
    /** Immutable value describing under over; carries base, under, over. */
    data class UnderOver(val base: MathNode, val under: MathNode, val over: MathNode) : MathNode()
    /** Immutable value describing table; carries rows. */
    data class Table(val rows: List<TableRow>) : MathNode()
    /** Immutable value describing table row; carries cells. */
    data class TableRow(val cells: List<MathNode>) : MathNode()
    /** mmultiscripts (best-effort): pre/post sub/sup scripts flattened around the base. */
    data class Multiscripts(
        val base: MathNode,
        val postSub: MathNode?,
        val postSup: MathNode?,
        val preSub: MathNode?,
        val preSup: MathNode?) : MathNode()
}

/** Singleton holding odf math state and operations. */
object OdfMath {

    /** Parses an embedded math object's content.xml (MathML). Returns null if unparseable. */
    fun parse(xml: String): MathNode? {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(xml.reader())
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "math") {
                    return Row(parseChildren(parser, "math"))
                }
                event = parser.next()
            }
            null
        } catch (_: Exception) { null }
    }

    private fun attr(parser: XmlPullParser, name: String): String? {
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeName(i) == name) return parser.getAttributeValue(i)
        }
        return null
    }

    /** Parses all child element nodes until the matching end tag of [endTag] at the current depth. */
    private fun parseChildren(parser: XmlPullParser, endTag: String): List<MathNode> {
        val nodes = mutableListOf<MathNode>()
        val depth = parser.depth
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth && parser.name == endTag)) {
            if (event == XmlPullParser.START_TAG) parseElement(parser)?.let { nodes.add(it) }
            if (event == XmlPullParser.END_DOCUMENT) break
            event = parser.next()
        }
        return nodes
    }

    /** Parses a single element (assumes parser positioned at its START_TAG). Consumes through its END_TAG. */
    private fun parseElement(parser: XmlPullParser): MathNode? {
        parseLeaf(parser)?.let { return it }
        parseScript(parser)?.let { return it }
        return parseLayout(parser)
    }

    /** Leaf/token/row-group elements; null when [parser] is not on one. */
    private fun parseLeaf(parser: XmlPullParser): MathNode? {
        return when (parser.name) {
            "mi", "mn", "mo", "mtext", "ms" -> {
                val isOp = parser.name == "mo"
                Token(readText(parser, parser.name), isOp)
            }
            "mspace" -> { skip(parser); Token(" ") }
            "mrow", "mstyle", "mpadded", "merror", "math" -> {
                val tag = parser.name
                Row(parseChildren(parser, tag))
            }
            else -> null
        }
    }

    /** Script/fraction/radical elements; null when [parser] is not on one. */
    private fun parseScript(parser: XmlPullParser): MathNode? {
        return when (parser.name) {
            "mfrac" -> binaryNode(parser, "mfrac") { a, b -> Frac(a, b) }
            "msup" -> binaryNode(parser, "msup") { a, b -> Sup(a, b) }
            "msub" -> binaryNode(parser, "msub") { a, b -> Sub(a, b) }
            "msubsup" -> {
                val kids = parseChildren(parser, "msubsup")
                SubSup(
                    kids.getOrElse(0) { Row(emptyList()) },
                    kids.getOrElse(1) { Row(emptyList()) },
                    kids.getOrElse(2) { Row(emptyList()) })
            }
            "msqrt" -> Sqrt(Row(parseChildren(parser, "msqrt")))
            "mroot" -> {
                val kids = parseChildren(parser, "mroot")
                Root(kids.getOrElse(0) { Row(emptyList()) }, kids.getOrElse(1) { Token("") })
            }
            "mfenced" -> {
                val open = attr(parser, "open") ?: "("
                val close = attr(parser, "close") ?: ")"
                Fenced(open, close, Row(parseChildren(parser, "mfenced")))
            }
            else -> null
        }
    }

    /** Layout/table/semantics elements (plus unknown-tag fallback). */
    private fun parseLayout(parser: XmlPullParser): MathNode? {
        return when (parser.name) {
            "munder" -> binaryNode(parser, "munder") { a, b -> MathNode.Under(a, b) }
            "mover" -> binaryNode(parser, "mover") { a, b -> MathNode.Over(a, b) }
            "munderover" -> parseUnderOver(parser)
            "mtable" -> parseTable(parser)
            "mtr", "mlabeledtr" -> {
                val cells = parseChildren(parser, parser.name)
                MathNode.TableRow(cells)
            }
            "mtd" -> Row(parseChildren(parser, "mtd"))
            "mmultiscripts" -> parseMultiscripts(parser)
            "semantics" -> Row(parseChildren(parser, "semantics"))
            "annotation", "annotation-xml" -> { skip(parser); null }
            else -> { val tag = parser.name; Row(parseChildren(parser, tag)) }
        }
    }

    /** munderover element. */
    private fun parseUnderOver(parser: XmlPullParser): MathNode.UnderOver {
        val kids = parseChildren(parser, "munderover")
        return MathNode.UnderOver(
            kids.getOrElse(0) { Row(emptyList()) },
            kids.getOrElse(1) { Row(emptyList()) },
            kids.getOrElse(2) { Row(emptyList()) })
    }

    /** mtable element (keeps only row children). */
    private fun parseTable(parser: XmlPullParser): MathNode.Table {
        val rows = parseChildren(parser, "mtable").filterIsInstance<MathNode.TableRow>()
        return MathNode.Table(rows)
    }

    /** mmultiscripts element (best-effort post-scripts only). */
    private fun parseMultiscripts(parser: XmlPullParser): MathNode.Multiscripts {
        val kids = parseChildren(parser, "mmultiscripts")
        // base, then (postsub, postsup) pairs; <mprescripts/> is dropped so pre-scripts fold in after it.
        val base = kids.getOrElse(0) { Row(emptyList()) }
        val postSub = kids.getOrNull(1)
        val postSup = kids.getOrNull(2)
        return MathNode.Multiscripts(base, postSub, postSup, null, null)
    }

    /** Binary math node from the first two children. */
    private fun binaryNode(
        parser: XmlPullParser,
        tag: String,
        build: (MathNode, MathNode) -> MathNode,
    ): MathNode {
        val kids = parseChildren(parser, tag)
        return build(
            kids.getOrElse(0) { Row(emptyList()) },
            kids.getOrElse(1) { Row(emptyList()) })
    }

    private fun readText(parser: XmlPullParser, endTag: String): String {
        val sb = StringBuilder()
        val depth = parser.depth
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth && parser.name == endTag)) {
            if (event == XmlPullParser.TEXT) sb.append(parser.text)
            if (event == XmlPullParser.END_DOCUMENT) break
            event = parser.next()
        }
        return sb.toString().trim()
    }

    private fun skip(parser: XmlPullParser) {
        val depth = parser.depth
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (event == XmlPullParser.END_DOCUMENT) break
            event = parser.next()
        }
    }

    /** Flattens a node to plain text (fallback when layout isn't desired). */
    fun toText(node: MathNode): String {
        if (node is MathNode.Row) return node.children.joinToString("") { toText(it) }
        if (node is MathNode.Token) return node.text
        if (node is MathNode.Table) return node.rows.joinToString("; ") { toText(it) }
        if (node is MathNode.TableRow) return node.cells.joinToString(", ") { toText(it) }
        if (node is MathNode.Multiscripts) return multiscriptsText(node)
        return scriptText(node) ?: layoutText(node) ?: containerText(node) ?: ""
    }

    /** Script nodes (frac/sup/sub/subsup). */
    private fun scriptText(node: MathNode): String? = when (node) {
        is MathNode.Frac -> "(${toText(node.numerator)})/(${toText(node.denominator)})"
        is MathNode.Sup -> "${toText(node.base)}^${toText(node.exponent)}"
        is MathNode.Sub -> "${toText(node.base)}_${toText(node.subscript)}"
        is MathNode.SubSup -> textOfSubSup(node)
        else -> null
    }

    /** Layout nodes (sqrt/root/fenced). */
    private fun layoutText(node: MathNode): String? = when (node) {
        is MathNode.Sqrt -> "\u221A(${toText(node.radicand)})"
        is MathNode.Root -> "root[${toText(node.index)}](${toText(node.radicand)})"
        is MathNode.Fenced -> "${node.open}${toText(node.body)}${node.close}"
        else -> null
    }

    /** Container nodes (under/over/underover). */
    private fun containerText(node: MathNode): String? = when (node) {
        is MathNode.Under -> "${toText(node.base)}_${toText(node.under)}"
        is MathNode.Over -> "${toText(node.base)}^${toText(node.over)}"
        is MathNode.UnderOver -> textOfUnderOver(node)
        else -> null
    }

    /** SubSup annotation text. */
    private fun textOfSubSup(node: MathNode.SubSup): String =
        "${toText(node.base)}_${toText(node.subscript)}^${toText(node.superscript)}"

    /** UnderOver annotation text. */
    private fun textOfUnderOver(node: MathNode.UnderOver): String =
        "${toText(node.base)}_${toText(node.under)}^${toText(node.over)}"

    /** Multiscripts pre/post annotation text. */
    private fun multiscriptsText(node: MathNode.Multiscripts): String {
        val pre = buildString {
            node.preSub?.let { append("_").append(toText(it)) }
            node.preSup?.let { append("^").append(toText(it)) }
        }
        val post = buildString {
            node.postSub?.let { append("_").append(toText(it)) }
            node.postSup?.let { append("^").append(toText(it)) }
        }
        return "$pre${toText(node.base)}$post"
    }
}
