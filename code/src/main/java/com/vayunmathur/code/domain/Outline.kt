package com.vayunmathur.code.domain
import com.vayunmathur.code.domain.syntax.Language
/** The kind of a source symbol, used to pick a label/icon in the outline. */
enum class SymbolKind { CLASS, FUNCTION, PROPERTY, HEADING, KEY }

/**
 * One entry in a file's outline: a [name], the 1-based [line] it starts on, its [kind], and an
 * [indentDepth] for nesting display.
 */
data class Symbol(
    val name: String,
    val line: Int,
    val kind: SymbolKind,
    val indentDepth: Int = 0,
)

/**
 * Pure, per-language symbol extraction for the outline / go-to-symbol feature. Deliberately
 * regex/line based (fast, dependency-free, good enough for navigation) rather than a real parser:
 * classes/functions/properties for code, headings for Markdown, and top-level keys for JSON/YAML.
 * No Android dependencies, so it is unit-tested directly.
 */
fun extractSymbols(text: String, language: Language): List<Symbol> = when (language) {
    Language.JSON -> jsonSymbols(text)
    Language.YAML -> yamlSymbols(text)
    Language.MARKDOWN -> markdownSymbols(text)
    else -> {
        val rules = rulesFor(language)
        if (rules.isEmpty()) emptyList() else lineScan(text, rules)
    }
}

private data class OutlineRule(val regex: Regex, val kind: SymbolKind, val group: Int = 1)

/** Applies [rules] line by line, taking the first rule that matches each line. */
private fun lineScan(text: String, rules: List<OutlineRule>): List<Symbol> {
    val out = ArrayList<Symbol>()
    text.lineSequence().forEachIndexed { index, line ->
        matchRule(line, rules)?.let { (name, kind) ->
            out.add(Symbol(name, index + 1, kind, indentDepthOf(line)))
        }
    }
    return out
}

private fun matchRule(line: String, rules: List<OutlineRule>): Pair<String, SymbolKind>? =
    rules.firstNotNullOfOrNull { rule ->
        val match = rule.regex.find(line) ?: return@firstNotNullOfOrNull null
        val name = match.groupValues.getOrNull(rule.group)?.takeIf { it.isNotEmpty() }
            ?: return@firstNotNullOfOrNull null
        name to rule.kind
    }

/** Leading-whitespace nesting: one level per tab, or per two spaces. */
private fun indentDepthOf(line: String): Int {
    var spaces = 0
    var tabs = 0
    for (c in line) {
        when (c) {
            ' ' -> spaces++
            '\t' -> tabs++
            else -> return tabs + spaces / 2
        }
    }
    return tabs + spaces / 2
}

private fun markdownSymbols(text: String): List<Symbol> {
    val heading = Regex("^(#{1,6})\\s+(.*\\S)")
    val out = ArrayList<Symbol>()
    text.lineSequence().forEachIndexed { index, line ->
        val m = heading.find(line) ?: return@forEachIndexed
        out.add(Symbol(m.groupValues[2].trim(), index + 1, SymbolKind.HEADING, m.groupValues[1].length - 1))
    }
    return out
}

private fun yamlSymbols(text: String): List<Symbol> {
    val topKey = Regex("^([A-Za-z0-9_.-]+):(?:\\s|$)")
    val out = ArrayList<Symbol>()
    text.lineSequence().forEachIndexed { index, line ->
        val m = topKey.find(line) ?: return@forEachIndexed
        out.add(Symbol(m.groupValues[1], index + 1, SymbolKind.KEY, 0))
    }
    return out
}

/** Captures object keys at nesting depth 1, tracking string/brace state so nested keys are skipped. */
private fun jsonSymbols(text: String): List<Symbol> = JsonKeyScanner(text).scan()

private const val TOP_LEVEL_DEPTH = 1

private class JsonKeyScanner(val text: String) {
    val out = ArrayList<Symbol>()
    var depth = 0
    var line = 1
    var inString = false
    var escaped = false
    var stringStart = -1
    val n = text.length
    var i = 0

    fun scan(): List<Symbol> {
        while (i < n) {
            if (inString) consumeStringChar() else consumeStructural()
            i++
        }
        return out
    }

    private fun consumeStringChar() {
        val c = text[i]
        if (escaped) {
            escaped = false
            return
        }
        if (c == '\\') {
            escaped = true
            return
        }
        if (c != '"') return
        inString = false
        if (depth == TOP_LEVEL_DEPTH) emitKeyIfFollowedByColon()
    }

    private fun emitKeyIfFollowedByColon() {
        var j = i + 1
        while (j < n && text[j].isWhitespace()) j++
        if (j >= n || text[j] != ':') return
        out.add(Symbol(text.substring(stringStart + 1, i), line, SymbolKind.KEY, 0))
    }

    private fun consumeStructural() {
        when (text[i]) {
            '"' -> {
                inString = true
                stringStart = i
            }
            '{', '[' -> depth++
            '}', ']' -> depth--
            '\n' -> line++
        }
    }
}

private val KOTLIN_RULES = listOf(
    OutlineRule(
        Regex("^\\s*(?:[\\w@]+\\s+)*?(?:class|interface|object)\\s+([A-Za-z_][A-Za-z0-9_]*)"),
        SymbolKind.CLASS,
    ),
    OutlineRule(
        Regex("^\\s*(?:[\\w@]+\\s+)*?fun\\s+(?:<[^>]+>\\s*)?(?:[A-Za-z_][A-Za-z0-9_]*\\.)?([A-Za-z_][A-Za-z0-9_]*)"),
        SymbolKind.FUNCTION,
    ),
    OutlineRule(Regex("^\\s*(?:[\\w@]+\\s+)*?(?:val|var)\\s+([A-Za-z_][A-Za-z0-9_]*)"), SymbolKind.PROPERTY),
)

private val JAVA_RULES = listOf(
    OutlineRule(
        Regex("^\\s*(?:[\\w@]+\\s+)*?(?:class|interface|enum)\\s+([A-Za-z_][A-Za-z0-9_]*)"),
        SymbolKind.CLASS,
    ),
    OutlineRule(
        Regex(
            "^\\s*(?:public|private|protected|static|final|abstract|synchronized|native)\\s+" +
                "(?:[\\w<>\\[\\].]+\\s+)+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(",
        ),
        SymbolKind.FUNCTION,
    ),
)

private val JS_RULES = listOf(
    OutlineRule(Regex("^\\s*(?:export\\s+)?(?:default\\s+)?class\\s+([A-Za-z_$][\\w$]*)"), SymbolKind.CLASS),
    OutlineRule(Regex("^\\s*(?:export\\s+)?(?:async\\s+)?function\\s+\\*?([A-Za-z_$][\\w$]*)"), SymbolKind.FUNCTION),
    OutlineRule(Regex("^\\s*(?:export\\s+)?(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)"), SymbolKind.PROPERTY),
)

private val PYTHON_RULES = listOf(
    OutlineRule(Regex("^\\s*class\\s+([A-Za-z_][A-Za-z0-9_]*)"), SymbolKind.CLASS),
    OutlineRule(Regex("^\\s*(?:async\\s+)?def\\s+([A-Za-z_][A-Za-z0-9_]*)"), SymbolKind.FUNCTION),
)

private val RUST_RULES = listOf(
    OutlineRule(Regex("^\\s*(?:pub\\s+)?(?:struct|enum|trait|impl)\\s+([A-Za-z_][A-Za-z0-9_]*)"), SymbolKind.CLASS),
    OutlineRule(Regex("^\\s*(?:pub\\s+)?(?:async\\s+)?fn\\s+([A-Za-z_][A-Za-z0-9_]*)"), SymbolKind.FUNCTION),
)

private val GO_RULES = listOf(
    OutlineRule(Regex("^\\s*type\\s+([A-Za-z_][A-Za-z0-9_]*)"), SymbolKind.CLASS),
    OutlineRule(Regex("^\\s*func\\s+(?:\\([^)]*\\)\\s*)?([A-Za-z_][A-Za-z0-9_]*)"), SymbolKind.FUNCTION),
)

private val SWIFT_RULES = listOf(
    OutlineRule(
        Regex(
            "^\\s*(?:public|private|internal|fileprivate|open|\\s)*" +
                "(?:class|struct|enum|protocol|extension)\\s+([A-Za-z_][A-Za-z0-9_]*)",
        ),
        SymbolKind.CLASS,
    ),
    OutlineRule(
        Regex(
            "^\\s*(?:public|private|internal|fileprivate|open|static|\\s)*func\\s+" +
                "([A-Za-z_][A-Za-z0-9_]*)",
        ),
        SymbolKind.FUNCTION,
    ),
)

private val C_RULES = listOf(
    OutlineRule(
        Regex("^\\s*(?:class|struct)\\s+([A-Za-z_][A-Za-z0-9_]*)"),
        SymbolKind.CLASS,
    ),
    OutlineRule(
        Regex(
            "^[A-Za-z_][\\w<>:*&\\s]*?\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\([^;{]*\\)" +
                "\\s*\\{?\\s*$",
        ),
        SymbolKind.FUNCTION,
    ),
)

private fun rulesFor(language: Language): List<OutlineRule> = when (language) {
    Language.KOTLIN -> KOTLIN_RULES
    Language.JAVA -> JAVA_RULES
    Language.JAVASCRIPT, Language.TYPESCRIPT -> JS_RULES
    Language.PYTHON -> PYTHON_RULES
    Language.RUST -> RUST_RULES
    Language.GO -> GO_RULES
    Language.SWIFT -> SWIFT_RULES
    Language.C, Language.CPP -> C_RULES
    else -> emptyList()
}
