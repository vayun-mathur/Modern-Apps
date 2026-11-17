package com.vayunmathur.code.domain
/**
 * A tiny, dependency-free Markdown → HTML converter (there is no Markdown library in the catalog).
 *
 * Supports headings, bold/italic, inline and fenced code, links, unordered/ordered lists and
 * blockquotes — enough for a readable preview. Pure and unit-tested; the WebView-based
 * [com.vayunmathur.code.ui.PreviewPage] wraps the returned fragment in a styled document.
 */
fun markdownToHtml(markdown: String): String {
    val converter = MarkdownConverter()
    return converter.convert(markdown.replace("\r\n", "\n").split("\n"))
}

private class MarkdownConverter {
    private val out = StringBuilder()
    private val codeBuf = StringBuilder()
    private val paraBuf = StringBuilder()
    private var inCode = false
    private var listType: String? = null

    fun convert(lines: List<String>): String {
        for (line in lines) consumeLine(line)
        if (inCode) emitCodeBlock()
        flushParagraph()
        closeList()
        return out.toString().trim()
    }

    private fun consumeLine(line: String) {
        if (line.trimStart().startsWith(FENCE)) {
            consumeFence()
            return
        }
        if (inCode) {
            codeBuf.append(line).append("\n")
            return
        }
        val trimmed = line.trim()
        if (trimmed.isEmpty()) {
            flushParagraph()
            closeList()
            return
        }
        if (consumeHeading(trimmed)) return
        if (consumeQuote(trimmed)) return
        if (consumeListItem(trimmed)) return
        appendParagraphText(trimmed)
    }

    private fun consumeFence() {
        if (inCode) {
            emitCodeBlock()
        } else {
            flushParagraph()
            closeList()
            inCode = true
        }
    }

    private fun emitCodeBlock() {
        out.append("<pre><code>").append(escapeHtml(codeBuf.toString())).append("</code></pre>\n")
        codeBuf.setLength(0)
        inCode = false
    }

    private fun consumeHeading(trimmed: String): Boolean {
        val heading = HEADING.find(trimmed) ?: return false
        flushParagraph()
        closeList()
        val level = heading.groupValues[1].length
        out.append("<h").append(level).append(">")
            .append(inlineMarkdown(heading.groupValues[2]))
            .append("</h").append(level).append(">\n")
        return true
    }

    private fun consumeQuote(trimmed: String): Boolean {
        if (!trimmed.startsWith(">")) return false
        flushParagraph()
        closeList()
        val quote = inlineMarkdown(trimmed.removePrefix(">").trim())
        out.append("<blockquote>").append(quote).append("</blockquote>\n")
        return true
    }

    private fun consumeListItem(trimmed: String): Boolean {
        val bullet = BULLET.find(trimmed)
        if (bullet != null) {
            emitListItem("ul", bullet.groupValues[1])
            return true
        }
        val numbered = NUMBERED.find(trimmed)
        if (numbered != null) {
            emitListItem("ol", numbered.groupValues[1])
            return true
        }
        return false
    }

    private fun emitListItem(kind: String, text: String) {
        flushParagraph()
        if (listType != kind) {
            closeList()
            out.append("<").append(kind).append(">\n")
            listType = kind
        }
        out.append("<li>").append(inlineMarkdown(text)).append("</li>\n")
    }

    private fun appendParagraphText(trimmed: String) {
        if (paraBuf.isNotEmpty()) paraBuf.append(" ")
        paraBuf.append(trimmed)
    }

    private fun closeList() {
        if (listType != null) {
            out.append("</").append(listType).append(">\n")
            listType = null
        }
    }

    private fun flushParagraph() {
        if (paraBuf.isNotBlank()) {
            out.append("<p>").append(inlineMarkdown(paraBuf.toString().trim())).append("</p>\n")
        }
        paraBuf.setLength(0)
    }

    private companion object {
        const val FENCE = "```"
    }
}

private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val BULLET = Regex("^[-*+]\\s+(.*)$")
private val NUMBERED = Regex("^\\d+\\.\\s+(.*)$")

private val INLINE_CODE = Regex("`([^`]+)`")
private val BOLD = Regex("\\*\\*([^*]+)\\*\\*|__([^_]+)__")
private val ITALIC = Regex("\\*([^*]+)\\*|_([^_]+)_")
private val LINK = Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)")

/** Applies inline Markdown to already-plain text (escapes HTML first). */
private fun inlineMarkdown(text: String): String {
    var s = escapeHtml(text)
    s = INLINE_CODE.replace(s) { "<code>${it.groupValues[1]}</code>" }
    s = BOLD.replace(s) { "<strong>${it.groupValues[1].ifEmpty { it.groupValues[2] }}</strong>" }
    s = ITALIC.replace(s) { "<em>${it.groupValues[1].ifEmpty { it.groupValues[2] }}</em>" }
    s = LINK.replace(s) { "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>" }
    return s
}

private fun escapeHtml(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
