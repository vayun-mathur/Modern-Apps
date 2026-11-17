package com.vayunmathur.code.domain
/**
 * Pure, dependency-free pretty-printers for JSON and XML, used by the editor's "Format document"
 * action. Both re-indent a well-formed document and return null when it is obviously malformed,
 * so the buffer is never clobbered with garbage.
 */

/** Re-indents well-formed JSON with [indentUnit] per level; null if braces/quotes are unbalanced. */
fun formatJson(json: String, indentUnit: String = "  "): String? {
    val s = json.trim()
    if (s.isEmpty()) return null
    val printer = JsonPrinter(indentUnit)
    if (!printer.print(s)) return null
    return printer.result()
}

private class JsonPrinter(val indentUnit: String) {
    val sb = StringBuilder()
    var depth = 0
    var inString = false
    var escaped = false
    var valid = true

    fun print(s: String): Boolean {
        for (c in s) {
            if (inString) {
                consumeStringChar(c)
                continue
            }
            consumeStructural(c)
            if (!valid) return false
        }
        return valid && !inString && depth == 0
    }

    fun result(): String = sb.toString()

    fun newline() {
        sb.append('\n')
        repeat(depth) { sb.append(indentUnit) }
    }

    private fun consumeStringChar(c: Char) {
        sb.append(c)
        if (escaped) {
            escaped = false
        } else if (c == '\\') {
            escaped = true
        } else if (c == '"') {
            inString = false
        }
    }

    private fun consumeStructural(c: Char) {
        when (c) {
            '"' -> {
                inString = true
                sb.append(c)
            }
            '{', '[' -> {
                sb.append(c)
                depth++
                newline()
            }
            '}', ']' -> {
                depth--
                if (depth < 0) {
                    valid = false
                    return
                }
                newline()
                sb.append(c)
            }
            ',' -> {
                sb.append(c)
                newline()
            }
            ':' -> sb.append(": ")
            ' ', '\t', '\n', '\r' -> Unit // collapse insignificant whitespace
            else -> sb.append(c)
        }
    }
}

private val XML_TAG = Regex("<[^>]+>")

/** Re-indents XML by tag nesting with [indentUnit] per level; null if the input is blank. */
fun formatXml(xml: String, indentUnit: String = "  "): String? {
    val trimmed = xml.trim()
    if (trimmed.isEmpty()) return null

    val tokens = ArrayList<String>()
    var last = 0
    for (m in XML_TAG.findAll(trimmed)) {
        if (m.range.first > last) tokens.add(trimmed.substring(last, m.range.first))
        tokens.add(m.value)
        last = m.range.last + 1
    }
    if (last < trimmed.length) tokens.add(trimmed.substring(last))

    val sb = StringBuilder()
    var depth = 0
    for (token in tokens) {
        if (token.startsWith("<")) {
            val tag = token.trim()
            val isClosing = tag.startsWith("</")
            val isSelfContained = tag.endsWith("/>") || tag.startsWith("<?") || tag.startsWith("<!")
            if (isClosing) depth = (depth - 1).coerceAtLeast(0)
            repeat(depth) { sb.append(indentUnit) }
            sb.append(tag).append('\n')
            if (!isClosing && !isSelfContained) depth++
        } else {
            val text = token.trim()
            if (text.isNotEmpty()) {
                repeat(depth) { sb.append(indentUnit) }
                sb.append(text).append('\n')
            }
        }
    }
    return sb.toString().trimEnd('\n')
}
