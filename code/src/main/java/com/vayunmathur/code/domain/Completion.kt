package com.vayunmathur.code.domain
import com.vayunmathur.code.domain.syntax.Language
/** What a completion candidate came from, used to pick an icon/label in the popup. */
enum class CompletionKind { WORD, KEYWORD, SNIPPET }

/**
 * One completion candidate.
 *
 * [insertText] replaces the current word prefix; [caretOffset] is where the caret lands within
 * [insertText] after accepting (so snippets can place it inside a bracket pair or body).
 */
data class Completion(
    val label: String,
    val insertText: String,
    val caretOffset: Int = insertText.length,
    val kind: CompletionKind = CompletionKind.WORD,
)

/** A snippet template. [CARET_MARKER] in [template] marks where the caret should land. */
private data class Snippet(val trigger: String, val template: String)

private const val CARET_MARKER = "\u0000"

/** The visible caret token users type in their own snippet templates (converted on expansion). */
const val SNIPPET_CARET_TOKEN = "\$0"

/**
 * A user-defined snippet. [languageId] is a [Language] name (from `Language.name`), or null to
 * offer the snippet in every language. Templates use [SNIPPET_CARET_TOKEN] for the caret position.
 */
data class UserSnippet(val trigger: String, val template: String, val languageId: String? = null)

private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

/** The identifier characters immediately before [caret] (the word being typed). */
fun currentWordPrefix(text: String, caret: Int): String {
    if (caret <= 0 || caret > text.length) return ""
    var i = caret
    while (i > 0) {
        val c = text[i - 1]
        if (c.isLetterOrDigit() || c == '_') i-- else break
    }
    return text.substring(i, caret)
}

/**
 * Buffer + keyword + snippet completions for [prefix], most relevant first.
 *
 * Candidates: snippet triggers for [language], distinct identifiers pulled from every open
 * buffer ([bufferTexts], ranked by frequency then length), and the language's keywords. All
 * are case-sensitive prefix matches; the exact word already typed is excluded. Pure and
 * testable — the ViewModel supplies the buffers and the UI draws the returned list.
 */
fun computeCompletions(
    prefix: String,
    language: Language,
    bufferTexts: List<String>,
    limit: Int = 50,
    userSnippets: List<UserSnippet> = emptyList(),
): List<Completion> {
    if (prefix.isEmpty()) return emptyList()
    val collector = CompletionCollector(prefix, limit)
    collector.addSnippets(userSnippetsFor(userSnippets, language) + snippetsFor(language))
    collector.addWords(bufferWords(bufferTexts, prefix))
    collector.addKeywords(keywordsFor(language).filter { it.startsWith(prefix) && it != prefix }.sorted())
    return collector.take()
}

private class CompletionCollector(val prefix: String, val limit: Int) {
    val results = ArrayList<Completion>()
    val used = HashSet<String>()

    fun addSnippets(snippets: List<Snippet>) {
        // Snippets whose trigger starts with the prefix (user snippets first, so they win ties).
        for (s in snippets) {
            if (s.trigger.startsWith(prefix) && used.add("s:${s.trigger}")) {
                val caret = s.template.indexOf(CARET_MARKER).let { if (it < 0) s.template.length else it }
                val body = s.template.replace(CARET_MARKER, "")
                results.add(Completion("${s.trigger}…", body, caret, CompletionKind.SNIPPET))
            }
        }
    }

    fun addWords(words: List<Pair<String, Int>>) {
        // Identifiers from the open buffers, ranked by frequency then length.
        for ((word, _) in words) {
            if (used.add("w:$word")) results.add(Completion(word, word, word.length, CompletionKind.WORD))
        }
    }

    fun addKeywords(keywords: List<String>) {
        for (k in keywords) {
            if (used.add("k:$k")) results.add(Completion(k, k, k.length, CompletionKind.KEYWORD))
        }
    }

    fun take(): List<Completion> = results.take(limit)
}

private fun bufferWords(bufferTexts: List<String>, prefix: String): List<Pair<String, Int>> {
    val freq = HashMap<String, Int>()
    for (text in bufferTexts) {
        for (m in IDENTIFIER.findAll(text)) freq.merge(m.value, 1) { a, b -> a + b }
    }
    return freq.keys
        .filter { it.startsWith(prefix) && it != prefix }
        .sortedWith(compareByDescending<String> { freq[it] ?: 0 }.thenBy { it.length }.thenBy { it })
        .map { it to (freq[it] ?: 0) }
}

// --- Per-language keyword and snippet tables (a focused subset; buffer words fill the rest) ---

/** Maps user snippets that apply to [language] into the internal [Snippet] form. */
private fun userSnippetsFor(list: List<UserSnippet>, language: Language): List<Snippet> =
    list.filter { it.trigger.isNotEmpty() && (it.languageId == null || it.languageId == language.name) }
        .map { Snippet(it.trigger, it.template.replace(SNIPPET_CARET_TOKEN, CARET_MARKER)) }

private val C_LIKE_SNIPPETS = listOf(
    Snippet("if", "if ($CARET_MARKER) {\n}"),
    Snippet("for", "for ($CARET_MARKER) {\n}"),
    Snippet("while", "while ($CARET_MARKER) {\n}"),
)

private fun snippetsFor(language: Language): List<Snippet> = when (language) {
    Language.KOTLIN -> C_LIKE_SNIPPETS + listOf(
        Snippet("fun", "fun $CARET_MARKER() {\n}"),
        Snippet("val", "val $CARET_MARKER ="),
        Snippet("var", "var $CARET_MARKER ="),
        Snippet("when", "when ($CARET_MARKER) {\n}"),
    )

    Language.JAVA -> C_LIKE_SNIPPETS + listOf(
        Snippet("class", "class $CARET_MARKER {\n}"),
    )

    Language.JAVASCRIPT, Language.TYPESCRIPT -> C_LIKE_SNIPPETS + listOf(
        Snippet("function", "function $CARET_MARKER() {\n}"),
        Snippet("const", "const $CARET_MARKER ="),
        Snippet("let", "let $CARET_MARKER ="),
    )

    Language.PYTHON -> listOf(
        Snippet("def", "def $CARET_MARKER():\n    pass"),
        Snippet("if", "if $CARET_MARKER:\n    pass"),
        Snippet("for", "for $CARET_MARKER in :\n    pass"),
        Snippet("while", "while $CARET_MARKER:\n    pass"),
        Snippet("class", "class $CARET_MARKER:\n    pass"),
    )

    Language.C, Language.CPP, Language.RUST, Language.GO, Language.SWIFT -> C_LIKE_SNIPPETS

    else -> emptyList()
}

private val KOTLIN_KEYWORDS = listOf(
    "abstract", "as", "break", "class", "companion", "const", "continue", "data", "do", "else",
    "enum", "false", "final", "for", "fun", "if", "import", "in", "infix", "init", "inline",
    "interface", "internal", "is", "lateinit", "null", "object", "open", "operator", "override",
    "package", "private", "protected", "public", "return", "sealed", "super", "suspend", "this",
    "throw", "true", "try", "typealias", "val", "var", "vararg", "when", "where", "while",
)

private val JS_KEYWORDS = listOf(
    "async", "await", "break", "case", "catch", "class", "const", "continue", "default", "delete",
    "do", "else", "export", "extends", "false", "finally", "for", "from", "function", "if",
    "import", "in", "instanceof", "interface", "let", "new", "null", "return", "super", "switch",
    "this", "throw", "true", "try", "type", "typeof", "undefined", "var", "void", "while", "yield",
)

private val PYTHON_KEYWORDS = listOf(
    "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif",
    "else", "except", "False", "finally", "for", "from", "global", "if", "import", "in", "is",
    "lambda", "None", "nonlocal", "not", "or", "pass", "raise", "return", "True", "try", "while",
    "with", "yield",
)

private val C_KEYWORDS = listOf(
    "auto", "bool", "break", "case", "char", "class", "const", "continue", "default", "do",
    "double", "else", "enum", "extern", "false", "float", "for", "if", "int", "long", "namespace",
    "new", "nullptr", "return", "short", "signed", "sizeof", "static", "struct", "switch",
    "template", "this", "true", "typedef", "unsigned", "using", "void", "volatile", "while",
)

private val RUST_KEYWORDS = listOf(
    "as", "async", "await", "break", "const", "continue", "crate", "else", "enum", "false", "fn",
    "for", "if", "impl", "in", "let", "loop", "match", "mod", "move", "mut", "pub", "ref", "return",
    "self", "static", "struct", "super", "trait", "true", "type", "unsafe", "use", "where", "while",
)

private val GO_KEYWORDS = listOf(
    "break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough",
    "false", "for", "func", "go", "goto", "if", "import", "interface", "map", "nil", "package",
    "range", "return", "select", "struct", "switch", "true", "type", "var",
)

private fun keywordsFor(language: Language): List<String> = when (language) {
    Language.KOTLIN -> KOTLIN_KEYWORDS
    Language.JAVASCRIPT, Language.TYPESCRIPT -> JS_KEYWORDS
    Language.PYTHON -> PYTHON_KEYWORDS
    Language.JAVA, Language.C, Language.CPP -> C_KEYWORDS
    Language.RUST -> RUST_KEYWORDS
    Language.GO -> GO_KEYWORDS
    else -> emptyList()
}
