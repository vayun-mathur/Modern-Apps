package com.vayunmathur.news.domain

/** Minimal HTML element node built by [HtmlDom]; text children are [HtmlText]. */
sealed interface HtmlNode

data class HtmlText(val text: String) : HtmlNode

data class HtmlElement(
    val tag: String,
    val attrs: Map<String, String> = emptyMap(),
    val children: List<HtmlNode> = emptyList(),
) : HtmlNode {
    /** All descendant text, entity-decoded and whitespace-collapsed. */
    fun textContent(): String {
        val sb = StringBuilder()
        fun walk(node: HtmlNode) {
            when (node) {
                is HtmlText -> sb.append(node.text).append(' ')
                is HtmlElement -> node.children.forEach(::walk)
            }
        }
        walk(this)
        return sb.toString().replace(Regex("""\s+"""), " ").trim()
    }

    fun findAll(tag: String): List<HtmlElement> {
        val out = mutableListOf<HtmlElement>()
        fun walk(node: HtmlNode) {
            if (node is HtmlElement) {
                if (node.tag == tag) out += node
                node.children.forEach(::walk)
            }
        }
        children.forEach(::walk)
        return out
    }

    fun first(tag: String): HtmlElement? = findAll(tag).firstOrNull()

    fun attr(name: String): String? = attrs[name.lowercase()]
}

/**
 * Tolerant stack parser for real-world article HTML: handles void elements,
 * raw-text elements (script/style dropped), comments, doctypes and unclosed
 * tags by auto-closing at the parent boundary. Never throws.
 */
object HtmlDom {
    private val voidTags = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input",
        "link", "meta", "param", "source", "track", "wbr",
    )
    private val dropTags = setOf("script", "style", "noscript", "template", "svg")

    fun parse(html: String): HtmlElement {
        val root = Builder("root")
        val stack = ArrayDeque<Builder>()
        stack.addLast(root)
        var i = 0
        while (i < html.length) {
            val lt = html.indexOf('<', i)
            if (lt < 0) {
                stack.last().children += HtmlText(decode(html.substring(i)))
                break
            }
            if (lt > i) stack.last().children += HtmlText(decode(html.substring(i, lt)))
            i = if (html.startsWith("<!--", lt)) skipComment(html, lt) else handleTag(html, lt, stack)
            if (i < 0) break
        }
        while (stack.size > 1) {
            val done = stack.removeLast()
            stack.last().children += done.build()
        }
        return root.build()
    }

    private fun skipComment(html: String, lt: Int): Int {
        val end = html.indexOf("-->", lt + 4)
        return if (end < 0) html.length else end + 3
    }

    /** Handles one tag at [lt]; returns the next scan index, or -1 to stop. */
    private fun handleTag(html: String, lt: Int, stack: ArrayDeque<Builder>): Int {
        val gt = findTagEnd(html, lt)
        if (gt < 0) return -1
        val rawTag = html.substring(lt + 1, gt).trim()
        var next = gt + 1
        if (rawTag.isEmpty() || rawTag.startsWith("!") || rawTag.startsWith("?")) return next
        if (rawTag.startsWith("/")) {
            closeTag(rawTag, stack)
            return next
        }
        val selfClosing = rawTag.endsWith("/")
        val tagBody = if (selfClosing) rawTag.dropLast(1).trim() else rawTag
        val name = tagBody.substringBefore(' ').substringBefore('/').lowercase()
        if (name.isEmpty()) return next
        if (name in dropTags) {
            if (!selfClosing) {
                val close = html.indexOf("</$name", next, ignoreCase = true)
                next = if (close < 0) html.length else close
            }
            return next
        }
        val builder = Builder(name, parseAttrs(tagBody.substring(name.length)))
        if (selfClosing || name in voidTags) {
            stack.last().children += builder.build()
        } else {
            stack.addLast(builder)
        }
        return next
    }

    private fun closeTag(rawTag: String, stack: ArrayDeque<Builder>) {
        val name = rawTag.substring(1).trim().substringBefore(' ').lowercase()
        val idx = stack.indexOfLast { it.tag == name }
        if (idx > 0) {
            while (stack.size > idx) {
                val done = stack.removeLast()
                stack.last().children += done.build()
            }
        }
    }

    private fun findTagEnd(html: String, lt: Int): Int {
        var quote: Char? = null
        var j = lt + 1
        while (j < html.length) {
            val c = html[j]
            if (quote != null) {
                if (c == quote) quote = null
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == '>') {
                return j
            }
            j++
        }
        return -1
    }

    private fun parseAttrs(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val regex = Regex("""([a-zA-Z_:][-\w:.]*)\s*(?:=\s*("[^"]*"|'[^']*'|[^\s"'/>]+))?""")
        for (m in regex.findAll(text)) {
            val value = m.groupValues[2].trim('"', '\'')
            out[m.groupValues[1].lowercase()] = decode(value)
        }
        return out
    }

    internal fun decode(text: String): String = SitemapParser.decodeEntities(
        text.replace("&nbsp;", " ").replace("&#160;", " "),
    )

    private class Builder(val tag: String, val attrs: Map<String, String> = emptyMap()) {
        val children = mutableListOf<HtmlNode>()
        fun build() = HtmlElement(tag, attrs, children.toList())
    }
}
