package com.vayunmathur.code.domain
import com.vayunmathur.code.domain.syntax.Language
import org.json.JSONArray
import org.json.JSONObject
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/** Diagnostic severity, ordered most→least severe for sorting and gutter colouring. */
enum class DiagnosticSeverity { ERROR, WARNING, INFO }

/**
 * One diagnostic on a single [line] (0-based), spanning columns `[startCol, endCol)` (0-based).
 * A zero-width range ([startCol] == [endCol]) marks a whole-line issue.
 */
data class Diagnostic(
    val line: Int,
    val startCol: Int,
    val endCol: Int,
    val severity: DiagnosticSeverity,
    val message: String,
)

private const val MAX_DIAGNOSTICS = 200
private const val XML_SNIFF_LENGTH = 64
private val JSON_OFFSET_PATTERN = Regex("character (\\d+)")

/**
 * In-process, offline diagnostics — a lightweight stand-in for a language server (which cannot run
 * on-device; see the plan). It composes several pure validators: unresolved merge-conflict markers
 * (all languages, via [parseConflicts]), JSON / XML well-formedness, a couple of YAML sanity checks,
 * bracket balance for brace languages, and TODO/FIXME notes. Everything except JSON (`org.json`) and
 * XML (`javax.xml`) is pure Kotlin, so the core is unit-tested directly.
 */
fun computeDiagnostics(text: String, language: Language): List<Diagnostic> {
    if (text.isEmpty()) return emptyList()
    val out = ArrayList<Diagnostic>()

    out += mergeMarkerDiagnostics(text)
    structuredDiagnostics(text, language)?.let { out += it }
    if (language in BRACE_LANGUAGES) out += bracketDiagnostics(text, language)
    out += todoDiagnostics(text)

    return out.asSequence()
        .sortedWith(compareBy({ it.line }, { it.startCol }, { it.severity.ordinal }))
        .take(MAX_DIAGNOSTICS)
        .toList()
}

// ---- Merge markers ----

private fun structuredDiagnostics(text: String, language: Language): List<Diagnostic>? = when (language) {
    Language.JSON -> jsonDiagnostics(text)
    Language.XML -> xmlDiagnostics(text)
    Language.YAML -> yamlDiagnostics(text)
    else -> null
}

private fun mergeMarkerDiagnostics(text: String): List<Diagnostic> =
    parseConflicts(text).map { conflict ->
        val lines = text.split("\n")
        val marker = lines.getOrNull(conflict.startLine).orEmpty()
        Diagnostic(
            line = conflict.startLine,
            startCol = 0,
            endCol = marker.length,
            severity = DiagnosticSeverity.ERROR,
            message = "Unresolved merge conflict",
        )
    }

// ---- JSON ----

private fun jsonDiagnostics(text: String): List<Diagnostic> {
    val trimmed = text.trimStart()
    if (trimmed.isEmpty()) return emptyList()
    val result = runCatching {
        if (trimmed.startsWith("[")) JSONArray(text) else JSONObject(text)
    }
    val error = result.exceptionOrNull() ?: return emptyList()
    val message = error.message ?: "Invalid JSON"
    // org.json reports the failing offset as "... at character N ...".
    val offset = JSON_OFFSET_PATTERN.find(message)?.groupValues?.get(1)?.toIntOrNull()
    val (line, col) = if (offset != null) offsetToLineCol(text, offset) else 0 to 0
    val short = message.substringBefore(" at ").ifBlank { "Invalid JSON" }
    return listOf(Diagnostic(line, col, col, DiagnosticSeverity.ERROR, short))
}

// ---- XML ----

private fun xmlDiagnostics(text: String): List<Diagnostic> {
    val head = text.trimStart().take(XML_SNIFF_LENGTH).lowercase()
    // HTML isn't required to be well-formed XML; don't flag it.
    if (head.startsWith("<!doctype html") || head.startsWith("<html")) return emptyList()
    if (text.isBlank()) return emptyList()

    val factory = DocumentBuilderFactory.newInstance()
    runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
    runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    val error = runCatching {
        val builder = factory.newDocumentBuilder()
        builder.setErrorHandler(null)
        builder.parse(InputSource(StringReader(text)))
    }.exceptionOrNull() ?: return emptyList()

    return if (error is SAXParseException) {
        val line = (error.lineNumber - 1).coerceAtLeast(0)
        val col = (error.columnNumber - 1).coerceAtLeast(0)
        listOf(Diagnostic(line, col, col, DiagnosticSeverity.ERROR, error.message ?: "Malformed XML"))
    } else {
        listOf(Diagnostic(0, 0, 0, DiagnosticSeverity.ERROR, error.message ?: "Malformed XML"))
    }
}

// ---- YAML ----

private fun yamlDiagnostics(text: String): List<Diagnostic> {
    val out = ArrayList<Diagnostic>()
    text.split("\n").forEachIndexed { i, line ->
        // YAML forbids tabs for indentation.
        val leading = line.takeWhile { it == ' ' || it == '\t' }
        val tab = leading.indexOf('\t')
        if (tab >= 0) {
            out.add(Diagnostic(i, tab, tab + 1, DiagnosticSeverity.ERROR, "YAML does not allow tabs for indentation"))
        }
    }
    return out
}

// ---- Bracket balance ----

private val BRACE_LANGUAGES = setOf(
    Language.KOTLIN, Language.JAVA, Language.JAVASCRIPT, Language.TYPESCRIPT,
    Language.C, Language.CPP, Language.GO, Language.CSS,
)

private val OPEN_TO_CLOSE = mapOf('(' to ')', '[' to ']', '{' to '}')

/**
 * String/comment-aware bracket balance for brace languages. Skips `//` line comments (except CSS),
 * `/* */` blocks, and `"`, `'`, `` ` `` and `"""` string forms so brackets inside them don't count.
 */
private fun bracketDiagnostics(text: String, language: Language): List<Diagnostic> =
    BracketScanner(text, language != Language.CSS).scan()

private class OpenBracket(val ch: Char, val line: Int, val col: Int)

private const val MAX_UNCLOSED_REPORTED = 5
private const val TRIPLE_QUOTE_LOOKAHEAD = 2
private const val BLOCK_COMMENT_TAIL = 2

private class BracketScanner(val text: String, val allowLineComment: Boolean) {
    val out = ArrayList<Diagnostic>()
    val stack = ArrayDeque<OpenBracket>()
    var line = 0
    var lineStart = 0
    var i = 0
    val n = text.length

    fun scan(): List<Diagnostic> {
        while (i < n) consumeChar()
        for (open in stack.take(MAX_UNCLOSED_REPORTED)) {
            out.add(
                Diagnostic(
                    open.line,
                    open.col,
                    open.col + 1,
                    DiagnosticSeverity.WARNING,
                    "Unclosed '${open.ch}'",
                ),
            )
        }
        return out
    }

    private fun consumeChar() {
        val c = text[i]
        if (c == '\n') {
            advanceLine()
            return
        }
        if (tryLineComment(c)) return
        if (tryBlockComment(c)) return
        if (tryString(c)) return
        consumeBracket(c, i - lineStart)
        i++
    }

    private fun advanceLine() {
        line++
        lineStart = i + 1
        i++
    }

    private fun isLineCommentAt(c: Char): Boolean =
        allowLineComment && c == '/' && peek(1) == '/'

    private fun isBlockCommentAt(c: Char): Boolean =
        c == '/' && peek(1) == '*'

    private fun peek(offset: Int): Char? =
        if (i + offset < n) text[i + offset] else null

    private fun tryLineComment(c: Char): Boolean {
        if (!isLineCommentAt(c)) return false
        val nl = text.indexOf('\n', i)
        i = if (nl < 0) n else nl
        return true
    }

    private fun tryBlockComment(c: Char): Boolean {
        if (!isBlockCommentAt(c)) return false
        val close = text.indexOf("*/", i + BLOCK_COMMENT_TAIL)
        if (close < 0) {
            i = n
        } else {
            advanceAcross(i, close + BLOCK_COMMENT_TAIL)
            i = close + BLOCK_COMMENT_TAIL
        }
        return true
    }

    private fun advanceAcross(from: Int, to: Int) {
        var k = from
        while (k < to) {
            if (text[k] == '\n') {
                line++
                lineStart = k + 1
            }
            k++
        }
    }

    private fun tryString(c: Char): Boolean {
        when (c) {
            '"' -> {
                i = if (isTripleQuote()) {
                    skipTriple(text, i + TRIPLE_QUOTE_LOOKAHEAD + 1) { onNewlineInSkip(it) }
                } else {
                    skipString(text, i + 1, '"') { onNewlineInSkip(it) }
                }
                return true
            }
            '\'', '`' -> {
                i = skipString(text, i + 1, c) { onNewlineInSkip(it) }
                return true
            }
        }
        return false
    }

    private fun isTripleQuote(): Boolean =
        peek(1) == '"' && peek(TRIPLE_QUOTE_LOOKAHEAD) == '"'

    private fun onNewlineInSkip(at: Int) {
        line++
        lineStart = at + 1
    }

    private fun consumeBracket(c: Char, col: Int) {
        if (c in OPEN_TO_CLOSE) {
            stack.addLast(OpenBracket(c, line, col))
            return
        }
        if (c != ')' && c != ']' && c != '}') return
        val top = stack.lastOrNull()
        if (top == null || OPEN_TO_CLOSE[top.ch] != c) {
            out.add(Diagnostic(line, col, col + 1, DiagnosticSeverity.ERROR, "Unmatched '$c'"))
        } else {
            stack.removeLast()
        }
    }
}

/** Advances past a `"..."`/`'...'`/`` `...` `` string starting at [from]; returns the index after it. */
private inline fun skipString(text: String, from: Int, quote: Char, onNewline: (Int) -> Unit): Int {
    var i = from
    val n = text.length
    while (i < n) {
        val c = text[i]
        if (c == '\\') { i += 2; continue }
        if (c == '\n') onNewline(i)
        if (c == quote) return i + 1
        i++
    }
    return n
}

/** Advances past a `"""..."""` triple-quoted string starting at [from]; returns the index after it. */
private inline fun skipTriple(text: String, from: Int, onNewline: (Int) -> Unit): Int {
    var i = from
    val n = text.length
    while (i < n) {
        if (isTripleEnd(text, i, n)) return i + TRIPLE_QUOTE_LENGTH
        if (text[i] == '\n') onNewline(i)
        i++
    }
    return n
}

private const val TRIPLE_QUOTE_LENGTH = 3

private fun isTripleEnd(text: String, i: Int, n: Int): Boolean =
    text[i] == '"' && i + TRIPLE_QUOTE_LOOKAHEAD < n && isTripleTail(text, i)

private fun isTripleTail(text: String, i: Int): Boolean =
    text[i + 1] == '"' && text[i + TRIPLE_QUOTE_LOOKAHEAD] == '"'

// ---- Pending-work markers in scanned code ----

private val TODO_REGEX = Regex("\\b(TODO|FIXME)\\b")

private fun todoDiagnostics(text: String): List<Diagnostic> {
    val out = ArrayList<Diagnostic>()
    text.split("\n").forEachIndexed { i, line ->
        val match = TODO_REGEX.find(line) ?: return@forEachIndexed
        out.add(
            Diagnostic(
                line = i,
                startCol = match.range.first,
                endCol = match.range.last + 1,
                severity = DiagnosticSeverity.INFO,
                message = match.value,
            ),
        )
    }
    return out
}

// ---- Shared ----

/** Maps a character [offset] in [text] to a 0-based (line, column) pair. */
private fun offsetToLineCol(text: String, offset: Int): Pair<Int, Int> {
    val safe = offset.coerceIn(0, text.length)
    var line = 0
    var lineStart = 0
    for (i in 0 until safe) {
        if (text[i] == '\n') { line++; lineStart = i + 1 }
    }
    return line to (safe - lineStart)
}
