package com.vayunmathur.code.domain.syntax
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.vayunmathur.library.ui.MaterialTheme

/**
 * Self-contained, regex-driven syntax highlighting.
 *
 * Each [Language] compiles once into a single alternation [Regex] whose top-level
 * alternatives are capturing groups (internally everything uses non-capturing groups so
 * group indices map 1:1 to [TokenKind]s). Highlighting is then a single left-to-right
 * `findAll` pass — because regex alternation is ordered and scanning resumes past each
 * match, comments/strings naturally "swallow" any keywords or numbers inside them.
 *
 * Colors are resolved from the Material color scheme at draw time (via [SyntaxColors]) so
 * the same tokenization looks correct in both light and dark themes.
 */
enum class TokenKind { COMMENT, STRING, NUMBER, ANNOTATION, KEYWORD }

/**
 * Above this many characters no whole-document highlighting is attempted at all.
 *
 * Every whole-document pass — the regex tokenizer, the native tree-sitter parse, and the
 * per-character rainbow-bracket scan — is O(document) and produces one span per token, and the
 * resulting spans are re-applied by Compose on every text layout. 150k characters is roughly a
 * 4000-line source file: larger than anything hand-written, and small enough that a single pass
 * stays in the low tens of milliseconds. Past it the file still opens and edits fine, uncolored.
 */
const val MAX_HIGHLIGHT_CHARS = 150_000

/** Precompiled tokenizer for one language: the alternation regex + per-group kinds. */
class LanguageSpec(private val parts: List<Pair<TokenKind, String>>) {
    private val kinds: List<TokenKind> = parts.map { it.first }
    val regex: Regex = Regex(
        parts.joinToString("|") { "(${it.second})" },
        setOf(RegexOption.MULTILINE),
    )

    /** Which token a match belongs to, found by the first non-null capturing group. */
    fun kindFor(match: MatchResult): TokenKind? {
        for (i in kinds.indices) {
            if (match.groups[i + 1] != null) return kinds[i]
        }
        return null
    }
}

enum class Language(val label: String, val lineCommentPrefix: String? = null) {
    KOTLIN("Kotlin", "//"),
    JAVA("Java", "//"),
    JAVASCRIPT("JavaScript", "//"),
    TYPESCRIPT("TypeScript", "//"),
    PYTHON("Python", "#"),
    C("C", "//"),
    CPP("C++", "//"),
    RUST("Rust", "//"),
    GO("Go", "//"),
    SWIFT("Swift", "//"),
    RUBY("Ruby", "#"),
    PHP("PHP", "//"),
    JSON("JSON"),
    XML("XML"),
    MARKDOWN("Markdown"),
    YAML("YAML", "#"),
    TOML("TOML", "#"),
    SHELL("Shell", "#"),
    CSS("CSS"),
    SQL("SQL", "--"),
    DOCKERFILE("Dockerfile", "#"),
    GRADLE("Gradle", "//"),
    PLAINTEXT("Plain Text");

    /** The tokenizer for this language, or null for languages we render without colors. */
    val spec: LanguageSpec? by lazy { specFor(this) }

    companion object {
        /** Picks a language from a file name's extension, defaulting to plain text. */
        fun fromFileName(name: String): Language {
            if (name == "Dockerfile" || name.startsWith("Dockerfile.")) return DOCKERFILE
            val ext = name.substringAfterLast('.', "").lowercase()
            return EXTENSION_LANGUAGE[ext] ?: PLAINTEXT
        }
    }
}

private val EXTENSION_LANGUAGE: Map<String, Language> = buildMap {
    fun map(language: Language, vararg extensions: String) {
        for (ext in extensions) put(ext, language)
    }
    map(Language.KOTLIN, "kt", "kts")
    map(Language.JAVA, "java")
    map(Language.JAVASCRIPT, "js", "jsx", "mjs", "cjs")
    map(Language.TYPESCRIPT, "ts", "tsx")
    map(Language.PYTHON, "py", "pyw")
    map(Language.C, "c", "h")
    map(Language.CPP, "cpp", "cc", "cxx", "hpp", "hh", "hxx")
    map(Language.RUST, "rs")
    map(Language.GO, "go")
    map(Language.SWIFT, "swift")
    map(Language.RUBY, "rb")
    map(Language.PHP, "php")
    map(Language.JSON, "json")
    map(Language.XML, "xml", "html", "htm", "svg", "xhtml")
    map(Language.MARKDOWN, "md", "markdown")
    map(Language.YAML, "yml", "yaml")
    map(Language.TOML, "toml")
    map(Language.SHELL, "sh", "bash", "zsh")
    map(Language.CSS, "css")
    map(Language.SQL, "sql")
    map(Language.GRADLE, "gradle")
}

// --- Reusable regex fragments (only non-capturing groups inside each) ---
private const val DOUBLE_STRING = "\"(?:\\\\.|[^\"\\\\\\n])*\""
private const val SINGLE_STRING = "'(?:\\\\.|[^'\\\\\\n])*'"
private const val BACKTICK_STRING = "`(?:\\\\.|[^`\\\\])*`"
private const val TRIPLE_DOUBLE = "\"\"\"[\\s\\S]*?\"\"\""
private const val TRIPLE_SINGLE = "'''[\\s\\S]*?'''"
private const val LINE_COMMENT = "//[^\\n]*"
private const val BLOCK_COMMENT = "/\\*[\\s\\S]*?\\*/"
private const val HASH_COMMENT = "#[^\\n]*"
private const val SQL_LINE_COMMENT = "--[^\\n]*"
private const val NUMBER = "\\b(?:0[xX][0-9a-fA-F_]+|\\d[\\d_]*(?:\\.\\d[\\d_]*)?(?:[eE][+-]?\\d+)?[fFlLuU]*)\\b"
private const val ANNOTATION = "@\\w+"

private fun keywords(vararg words: String) = "\\b(?:" + words.joinToString("|") + ")\\b"

/** Case-insensitive keyword alternation, for languages whose keywords ignore case (SQL, Dockerfile). */
private fun keywordsCI(vararg words: String) = "\\b(?i:" + words.joinToString("|") + ")\\b"

private val LANGUAGE_SPECS: Map<Language, () -> LanguageSpec> = mapOf(
    Language.KOTLIN to ::kotlinJvmSpec,
    Language.JAVA to ::kotlinJvmSpec,
    Language.JAVASCRIPT to ::javaScriptSpec,
    Language.TYPESCRIPT to ::javaScriptSpec,
    Language.PYTHON to ::pythonSpec,
    Language.C to ::cSpec,
    Language.CPP to ::cSpec,
    Language.RUST to ::rustSpec,
    Language.JSON to ::jsonSpec,
    Language.XML to ::xmlSpec,
    Language.MARKDOWN to ::markdownSpec,
    Language.GO to ::goSpec,
    Language.SWIFT to ::swiftSpec,
    Language.RUBY to ::rubySpec,
    Language.PHP to ::phpSpec,
    Language.YAML to ::yamlSpec,
    Language.TOML to ::tomlSpec,
    Language.SHELL to ::shellSpec,
    Language.CSS to ::cssSpec,
    Language.SQL to ::sqlSpec,
    Language.DOCKERFILE to ::dockerfileSpec,
    Language.GRADLE to ::gradleSpec,
)

private fun specFor(language: Language): LanguageSpec? =
    LANGUAGE_SPECS[language]?.invoke()

private fun kotlinJvmSpec(): LanguageSpec = LanguageSpec(
        listOf(
            TokenKind.COMMENT to BLOCK_COMMENT,
            TokenKind.COMMENT to LINE_COMMENT,
            TokenKind.STRING to TRIPLE_DOUBLE,
            TokenKind.STRING to DOUBLE_STRING,
            TokenKind.STRING to SINGLE_STRING,
            TokenKind.ANNOTATION to ANNOTATION,
            TokenKind.KEYWORD to keywords(
                "abstract", "as", "assert", "boolean", "break", "byte", "case", "catch", "char",
                "class", "companion", "const", "constructor", "continue", "crossinline", "data",
                "default", "do", "double", "dynamic", "else", "enum", "extends", "external",
                "false", "final", "finally", "float", "for", "fun", "get", "goto", "if",
                "implements", "import", "in", "infix", "init", "inline", "inner", "instanceof",
                "int", "interface", "internal", "is", "lateinit", "lazy", "long", "native", "new",
                "null", "object", "open", "operator", "out", "override", "package", "private",
                "protected", "public", "reified", "return", "sealed", "set", "short", "static",
                "strictfp", "super", "suspend", "switch", "synchronized", "tailrec", "this",
                "throw", "throws", "transient", "true", "try", "typealias", "val", "var", "vararg",
                "void", "volatile", "when", "where", "while",
            ),
            TokenKind.NUMBER to NUMBER,
        )
    )

private fun javaScriptSpec(): LanguageSpec = LanguageSpec(
        listOf(
            TokenKind.COMMENT to BLOCK_COMMENT,
            TokenKind.COMMENT to LINE_COMMENT,
            TokenKind.STRING to DOUBLE_STRING,
            TokenKind.STRING to SINGLE_STRING,
            TokenKind.STRING to BACKTICK_STRING,
            TokenKind.KEYWORD to keywords(
                "abstract", "any", "as", "async", "await", "boolean", "break", "case", "catch",
                "class", "const", "continue", "debugger", "declare", "default", "delete", "do",
                "else", "enum", "export", "extends", "false", "finally", "for", "from", "function",
                "get", "if", "implements", "import", "in", "instanceof", "interface", "let",
                "namespace", "never", "new", "null", "number", "object", "of", "package", "private",
                "protected", "public", "readonly", "return", "set", "static", "string", "super",
                "switch", "this", "throw", "true", "try", "type", "typeof", "undefined", "var",
                "void", "while", "with", "yield",
            ),
            TokenKind.NUMBER to NUMBER,
        )
    )

private fun pythonSpec(): LanguageSpec = LanguageSpec(
        listOf(
            TokenKind.STRING to TRIPLE_DOUBLE,
            TokenKind.STRING to TRIPLE_SINGLE,
            TokenKind.COMMENT to HASH_COMMENT,
            TokenKind.STRING to DOUBLE_STRING,
            TokenKind.STRING to SINGLE_STRING,
            TokenKind.ANNOTATION to ANNOTATION,
            TokenKind.KEYWORD to keywords(
                "False", "None", "True", "and", "as", "assert", "async", "await", "break", "case",
                "class", "continue", "def", "del", "elif", "else", "except", "finally", "for",
                "from", "global", "if", "import", "in", "is", "lambda", "match", "nonlocal", "not",
                "or", "pass", "raise", "return", "self", "try", "while", "with", "yield",
            ),
            TokenKind.NUMBER to NUMBER,
        )
    )

private fun cSpec(): LanguageSpec = LanguageSpec(
        listOf(
            TokenKind.COMMENT to BLOCK_COMMENT,
            TokenKind.COMMENT to LINE_COMMENT,
            TokenKind.STRING to DOUBLE_STRING,
            TokenKind.STRING to SINGLE_STRING,
            TokenKind.ANNOTATION to "^\\s*#\\s*\\w+", // preprocessor directives
            TokenKind.KEYWORD to keywords(
                "alignas", "alignof", "and", "asm", "auto", "bool", "break", "case", "catch",
                "char", "class", "const", "constexpr", "const_cast", "continue", "decltype",
                "default", "delete", "do", "double", "dynamic_cast", "else", "enum", "explicit",
                "export", "extern", "false", "float", "for", "friend", "goto", "if", "inline",
                "int", "long", "mutable", "namespace", "new", "noexcept", "nullptr", "operator",
                "private", "protected", "public", "register", "reinterpret_cast", "return", "short",
                "signed", "sizeof", "static", "static_assert", "static_cast", "struct", "switch",
                "template", "this", "throw", "true", "try", "typedef", "typeid", "typename",
                "union", "unsigned", "using", "virtual", "void", "volatile", "wchar_t", "while",
            ),
            TokenKind.NUMBER to NUMBER,
        )
    )

private fun rustSpec(): LanguageSpec = LanguageSpec(
        listOf(
            TokenKind.COMMENT to BLOCK_COMMENT,
            TokenKind.COMMENT to LINE_COMMENT,
            TokenKind.STRING to DOUBLE_STRING,
            TokenKind.ANNOTATION to "#!?\\[[^\\]]*\\]", // attributes like #[derive(...)]
            TokenKind.KEYWORD to keywords(
                "as", "async", "await", "break", "const", "continue", "crate", "dyn", "else",
                "enum", "extern", "false", "fn", "for", "if", "impl", "in", "let", "loop", "match",
                "mod", "move", "mut", "pub", "ref", "return", "self", "Self", "static", "struct",
                "super", "trait", "true", "type", "unsafe", "use", "where", "while",
            ),
            TokenKind.NUMBER to NUMBER,
        )
    )

private fun jsonSpec(): LanguageSpec = LanguageSpec(
        listOf(
            TokenKind.STRING to DOUBLE_STRING,
            TokenKind.KEYWORD to keywords("true", "false", "null"),
            TokenKind.NUMBER to NUMBER,
        )
    )

private fun xmlSpec(): LanguageSpec = LanguageSpec(
        listOf(
            TokenKind.COMMENT to "<!--[\\s\\S]*?-->",
            TokenKind.STRING to DOUBLE_STRING,
            TokenKind.STRING to SINGLE_STRING,
            TokenKind.KEYWORD to "</?[?!]?[A-Za-z][\\w:.-]*|/?>",
            TokenKind.NUMBER to "&#?\\w+;", // entities
        )
    )

private fun markdownSpec(): LanguageSpec = LanguageSpec(
        listOf(
            TokenKind.STRING to "```[\\s\\S]*?```",
            TokenKind.STRING to "`[^`\\n]+`",
            TokenKind.KEYWORD to "^#{1,6} .*$",
            TokenKind.NUMBER to "\\[[^\\]\\n]*\\]\\([^)\\n]*\\)", // links
            TokenKind.ANNOTATION to "\\*\\*[^*\\n]+\\*\\*|__[^_\\n]+__", // bold
            TokenKind.ANNOTATION to "\\*[^*\\n]+\\*|_[^_\\n]+_",       // italic
            TokenKind.COMMENT to "^>.*$", // blockquote
        )
    )

private fun goSpec(): LanguageSpec = LanguageSpec(
        listOf(
            TokenKind.COMMENT to BLOCK_COMMENT,
            TokenKind.COMMENT to LINE_COMMENT,
            TokenKind.STRING to BACKTICK_STRING,
            TokenKind.STRING to DOUBLE_STRING,
            TokenKind.STRING to SINGLE_STRING,
            TokenKind.KEYWORD to keywords(
                "break", "case", "chan", "const", "continue", "default", "defer", "else",
                "fallthrough", "false", "for", "func", "go", "goto", "if", "import", "interface",
                "iota", "map", "nil", "package", "range", "return", "select", "struct", "switch",
                "true", "type", "var",
            ),
            TokenKind.NUMBER to NUMBER,
        )
    )

private fun swiftSpec(): LanguageSpec = LanguageSpec(
        listOf(
            TokenKind.COMMENT to BLOCK_COMMENT,
            TokenKind.COMMENT to LINE_COMMENT,
            TokenKind.STRING to DOUBLE_STRING,
            TokenKind.ANNOTATION to ANNOTATION,
            TokenKind.KEYWORD to keywords(
                "as", "associatedtype", "async", "await", "break", "case", "catch", "class",
                "continue", "default", "defer", "deinit", "do", "else", "enum", "extension",
                "fallthrough", "false", "fileprivate", "final", "for", "func", "guard", "if",
                "import", "in", "init", "internal", "is", "lazy", "let", "nil", "open", "override",
                "private", "protocol", "public", "repeat", "return", "self", "static", "struct",
                "super", "switch", "throw", "throws", "true", "try", "typealias", "unowned", "var",
                "weak", "where", "while",
            ),
            TokenKind.NUMBER to NUMBER,
        )
    )

private fun rubySpec(): LanguageSpec = LanguageSpec(
        listOf(
            TokenKind.COMMENT to HASH_COMMENT,
            TokenKind.STRING to DOUBLE_STRING,
            TokenKind.STRING to SINGLE_STRING,
            TokenKind.KEYWORD to keywords(
                "and", "attr_accessor", "attr_reader", "attr_writer", "begin", "break", "case",
                "class", "def", "do", "else", "elsif", "end", "ensure", "false", "for", "if", "in",
                "lambda", "module", "next", "nil", "not", "or", "proc", "puts", "raise", "redo",
                "require", "require_relative", "rescue", "retry", "return", "self", "super", "then",
                "true", "unless", "until", "when", "while", "yield",
            ),
            TokenKind.NUMBER to NUMBER,
        )
    )

private fun phpSpec(): LanguageSpec = LanguageSpec(
        listOf(
            TokenKind.COMMENT to BLOCK_COMMENT,
            TokenKind.COMMENT to LINE_COMMENT,
            TokenKind.COMMENT to HASH_COMMENT,
            TokenKind.STRING to DOUBLE_STRING,
            TokenKind.STRING to SINGLE_STRING,
            TokenKind.ANNOTATION to "\\$\\w+", // variables
            TokenKind.KEYWORD to keywords(
                "abstract", "and", "array", "as", "break", "callable", "case", "catch", "class",
                "clone", "const", "continue", "declare", "default", "do", "echo", "else", "elseif",
                "empty", "enum", "extends", "final", "finally", "fn", "for", "foreach", "function",
                "global", "goto", "if", "implements", "include", "include_once", "instanceof",
                "insteadof", "interface", "isset", "list", "match", "namespace", "new", "null",
                "or", "print", "private", "protected", "public", "readonly", "require",
                "require_once", "return", "static", "switch", "throw", "trait", "try", "unset",
                "use", "var", "while", "xor", "yield", "true", "false",
            ),
            TokenKind.NUMBER to NUMBER,
        )
    )

private fun yamlSpec(): LanguageSpec = LanguageSpec(
        listOf(
            TokenKind.COMMENT to HASH_COMMENT,
            TokenKind.STRING to DOUBLE_STRING,
            TokenKind.STRING to SINGLE_STRING,
            TokenKind.KEYWORD to keywords("true", "false", "null", "yes", "no", "on", "off"),
            TokenKind.NUMBER to NUMBER,
        )
    )

private fun tomlSpec(): LanguageSpec = LanguageSpec(
        listOf(
            TokenKind.COMMENT to HASH_COMMENT,
            TokenKind.STRING to DOUBLE_STRING,
            TokenKind.STRING to SINGLE_STRING,
            TokenKind.KEYWORD to keywords("true", "false"),
            TokenKind.NUMBER to NUMBER,
        )
    )

private fun shellSpec(): LanguageSpec = LanguageSpec(
        listOf(
            TokenKind.COMMENT to HASH_COMMENT,
            TokenKind.STRING to DOUBLE_STRING,
            TokenKind.STRING to SINGLE_STRING,
            TokenKind.ANNOTATION to "\\$\\w+|\\$\\{[^}]*\\}", // variable expansions
            TokenKind.KEYWORD to keywords(
                "break", "case", "cd", "continue", "declare", "do", "done", "echo", "elif", "else",
                "esac", "exit", "export", "fi", "for", "function", "if", "in", "local", "readonly",
                "return", "select", "source", "then", "until", "while",
            ),
            TokenKind.NUMBER to NUMBER,
        )
    )

private fun cssSpec(): LanguageSpec = LanguageSpec(
        listOf(
            TokenKind.COMMENT to BLOCK_COMMENT,
            TokenKind.STRING to DOUBLE_STRING,
            TokenKind.STRING to SINGLE_STRING,
            TokenKind.ANNOTATION to "@[\\w-]+", // at-rules like @media, @import
            TokenKind.NUMBER to "#[0-9a-fA-F]{3,8}\\b|\\b\\d+(?:\\.\\d+)?(?:px|em|rem|%|vh|vw|pt|s|ms)?\\b",
        )
    )

private fun sqlSpec(): LanguageSpec = LanguageSpec(
        listOf(
            TokenKind.COMMENT to BLOCK_COMMENT,
            TokenKind.COMMENT to SQL_LINE_COMMENT,
            TokenKind.STRING to SINGLE_STRING,
            TokenKind.STRING to DOUBLE_STRING,
            TokenKind.KEYWORD to keywordsCI(
                "add", "all", "alter", "and", "as", "asc", "between", "by", "case", "column",
                "create", "cross", "delete", "desc", "distinct", "drop", "else", "end", "exists",
                "foreign", "from", "full", "group", "having", "in", "index", "inner", "insert",
                "into", "is", "join", "key", "left", "like", "limit", "not", "null", "on", "or",
                "order", "outer", "primary", "references", "right", "select", "set", "table", "then",
                "top", "union", "unique", "update", "values", "view", "where",
            ),
            TokenKind.NUMBER to NUMBER,
        )
    )

private fun dockerfileSpec(): LanguageSpec = LanguageSpec(
        listOf(
            TokenKind.COMMENT to HASH_COMMENT,
            TokenKind.STRING to DOUBLE_STRING,
            TokenKind.STRING to SINGLE_STRING,
            TokenKind.KEYWORD to keywordsCI(
                "add", "arg", "cmd", "copy", "entrypoint", "env", "expose", "from", "healthcheck",
                "label", "maintainer", "onbuild", "run", "shell", "stopsignal", "user", "volume",
                "workdir",
            ),
            TokenKind.NUMBER to NUMBER,
        )
    )

private fun gradleSpec(): LanguageSpec = LanguageSpec(
        listOf(
            TokenKind.COMMENT to BLOCK_COMMENT,
            TokenKind.COMMENT to LINE_COMMENT,
            TokenKind.STRING to TRIPLE_DOUBLE,
            TokenKind.STRING to DOUBLE_STRING,
            TokenKind.STRING to SINGLE_STRING,
            TokenKind.KEYWORD to keywords(
                "abstract", "as", "assert", "break", "case", "catch", "class", "continue", "def",
                "default", "do", "else", "enum", "extends", "false", "final", "finally", "for",
                "if", "implements", "import", "in", "interface", "new", "null", "package",
                "private", "protected", "public", "return", "static", "super", "switch", "this",
                "throw", "trait", "true", "try", "void", "while",
            ),
            TokenKind.NUMBER to NUMBER,
        )
    )

/** Named color presets for the editor, theme-aware for [DEFAULT] and fixed for the others. */
object EditorThemes {
    const val DEFAULT = "default"
    const val MONOKAI = "monokai"
    const val SOLARIZED = "solarized"
    val ALL = listOf(DEFAULT to "Default", MONOKAI to "Monokai", SOLARIZED to "Solarized")
}

/** Theme-derived colors for each token kind plus find-match, bracket and whitespace highlights. */
data class SyntaxColors(
    val keyword: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val annotation: Color,
    val function: Color,
    val type: Color,
    val match: Color,
    val activeMatch: Color,
    val currentLine: Color,
    val matchedBracket: Color,
    val trailingWhitespace: Color,
    val brackets: List<Color>,
) {
    fun colorFor(kind: TokenKind): Color = when (kind) {
        TokenKind.COMMENT -> comment
        TokenKind.STRING -> string
        TokenKind.NUMBER -> number
        TokenKind.ANNOTATION -> annotation
        TokenKind.KEYWORD -> keyword
    }
}

@Composable
fun rememberSyntaxColors(theme: String = EditorThemes.DEFAULT): SyntaxColors {
    val scheme = MaterialTheme.colorScheme
    return when (theme) {
        EditorThemes.MONOKAI -> monokaiColors(scheme)
        EditorThemes.SOLARIZED -> solarizedColors(scheme)
        else -> defaultColors(scheme)
    }
}

private const val MATCH_ALPHA = 0.30f
private const val ACTIVE_MATCH_ALPHA = 0.55f
private const val CURRENT_LINE_ALPHA = 0.06f
private const val MATCHED_BRACKET_ALPHA = 0.35f
private const val TRAILING_WS_ALPHA = 0.15f
private const val SOLARIZED_LINE_ALPHA = 0.12f

private fun defaultColors(scheme: androidx.compose.material3.ColorScheme) = SyntaxColors(
    keyword = scheme.primary,
    string = scheme.tertiary,
    number = scheme.secondary,
    comment = scheme.onSurfaceVariant,
    annotation = scheme.error,
    function = scheme.primary,
    type = scheme.secondary,
    match = scheme.primary.copy(alpha = MATCH_ALPHA),
    activeMatch = scheme.tertiary.copy(alpha = ACTIVE_MATCH_ALPHA),
    currentLine = scheme.onSurface.copy(alpha = CURRENT_LINE_ALPHA),
    matchedBracket = scheme.primary.copy(alpha = MATCHED_BRACKET_ALPHA),
    trailingWhitespace = scheme.error.copy(alpha = TRAILING_WS_ALPHA),
    brackets = listOf(scheme.primary, scheme.tertiary, scheme.secondary),
)

private val MONOKAI_PINK = Color(0xFFF92672)
private val MONOKAI_YELLOW = Color(0xFFE6DB74)
private val MONOKAI_PURPLE = Color(0xFFAE81FF)
private val MONOKAI_GREY = Color(0xFF75715E)
private val MONOKAI_GREEN = Color(0xFFA6E22E)
private val MONOKAI_BLUE = Color(0xFF66D9EF)
private val MONOKAI_WHITE = Color(0xFFFFFFFF)
private val MONOKAI_ORANGE = Color(0xFFFD971F)

private fun monokaiColors(scheme: androidx.compose.material3.ColorScheme) = SyntaxColors(
    keyword = MONOKAI_PINK,
    string = MONOKAI_YELLOW,
    number = MONOKAI_PURPLE,
    comment = MONOKAI_GREY,
    annotation = MONOKAI_GREEN,
    function = MONOKAI_GREEN,
    type = MONOKAI_BLUE,
    match = scheme.primary.copy(alpha = MATCH_ALPHA),
    activeMatch = scheme.tertiary.copy(alpha = ACTIVE_MATCH_ALPHA),
    currentLine = MONOKAI_WHITE.copy(alpha = CURRENT_LINE_ALPHA),
    matchedBracket = MONOKAI_PINK.copy(alpha = MATCHED_BRACKET_ALPHA),
    trailingWhitespace = MONOKAI_PINK.copy(alpha = TRAILING_WS_ALPHA),
    brackets = listOf(MONOKAI_PINK, MONOKAI_GREEN, MONOKAI_BLUE, MONOKAI_ORANGE),
)

private val SOLARIZED_GREEN = Color(0xFF859900)
private val SOLARIZED_CYAN = Color(0xFF2AA198)
private val SOLARIZED_MAGENTA = Color(0xFFD33682)
private val SOLARIZED_GREY = Color(0xFF93A1A1)
private val SOLARIZED_YELLOW = Color(0xFFB58900)
private val SOLARIZED_BLUE = Color(0xFF268BD2)
private val SOLARIZED_SLATE = Color(0xFF586E75)
private val SOLARIZED_RED = Color(0xFFDC322F)
private val SOLARIZED_VIOLET = Color(0xFF6C71C4)

private fun solarizedColors(scheme: androidx.compose.material3.ColorScheme) = SyntaxColors(
    keyword = SOLARIZED_GREEN,
    string = SOLARIZED_CYAN,
    number = SOLARIZED_MAGENTA,
    comment = SOLARIZED_GREY,
    annotation = SOLARIZED_YELLOW,
    function = SOLARIZED_BLUE,
    type = SOLARIZED_YELLOW,
    match = scheme.primary.copy(alpha = MATCH_ALPHA),
    activeMatch = scheme.tertiary.copy(alpha = ACTIVE_MATCH_ALPHA),
    currentLine = SOLARIZED_SLATE.copy(alpha = SOLARIZED_LINE_ALPHA),
    matchedBracket = SOLARIZED_BLUE.copy(alpha = MATCHED_BRACKET_ALPHA),
    trailingWhitespace = SOLARIZED_RED.copy(alpha = TRAILING_WS_ALPHA),
    brackets = listOf(SOLARIZED_BLUE, SOLARIZED_VIOLET, SOLARIZED_CYAN, SOLARIZED_YELLOW),
)

/** Index of the bracket matching the one at [index], or -1 if [index] isn't a bracket / unmatched. */
private fun matchingBracketIndex(text: String, index: Int): Int {
    if (index < 0 || index >= text.length) return -1
    val c = text[index]
    val openers = "([{"
    val closers = ")]}"
    val openIdx = openers.indexOf(c)
    if (openIdx >= 0) {
        val close = closers[openIdx]
        var depth = 0
        var i = index
        while (i < text.length) {
            val ch = text[i]
            if (ch == c) depth++ else if (ch == close) {
                depth--
                if (depth == 0) return i
            }
            i++
        }
        return -1
    }
    val closeIdx = closers.indexOf(c)
    if (closeIdx >= 0) {
        val open = openers[closeIdx]
        var depth = 0
        var i = index
        while (i >= 0) {
            val ch = text[i]
            if (ch == c) depth++ else if (ch == open) {
                depth--
                if (depth == 0) return i
            }
            i--
        }
        return -1
    }
    return -1
}

/**
 * A [VisualTransformation] that leaves the text unchanged (identity offset mapping) but
 * layers syntax spans and find-match backgrounds onto it. Highlighting is skipped for
 * very large documents to keep typing responsive.
 *
 * When [caret] is a valid collapsed offset, the caret's line gets a subtle background and, if
 * the caret sits next to a bracket, that bracket and its match are highlighted.
 */
/** A resolved colour span `[start, end)` from the native tree-sitter highlighter. */
data class TsColorSpan(val start: Int, val end: Int, val color: Color)

class SyntaxTransformation(
    private val spec: LanguageSpec?,
    private val colors: SyntaxColors,
    private val matches: List<IntRange> = emptyList(),
    private val activeMatch: Int = -1,
    private val caret: Int = -1,
    /** Structure-aware colour spans from tree-sitter; when non-null they replace the regex pass. */
    private val tsSpans: List<TsColorSpan>? = null,
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val painter = SyntaxPainter(text.text, AnnotatedString.Builder(text), spec, colors, tsSpans, caret)
        painter.paintCurrentLine()
        painter.paintHighlight()
        painter.paintBracketMatch()
        painter.paintMatches(matches, activeMatch)
        return TransformedText(painter.builder.toAnnotatedString(), OffsetMapping.Identity)
    }

    internal companion object {
        val FUNCTION_REGEX = Regex("\\b[A-Za-z_]\\w*(?=\\s*\\()")
        val TYPE_REGEX = Regex("\\b[A-Z]\\w*\\b")
        val TRAILING_WS_REGEX = Regex("[ \\t]+$", RegexOption.MULTILINE)
    }
}

private class SyntaxPainter(
    val raw: String,
    val builder: AnnotatedString.Builder,
    val spec: LanguageSpec?,
    val colors: SyntaxColors,
    val tsSpans: List<TsColorSpan>?,
    val caret: Int,
) {
    val withinBudget = raw.length <= MAX_HIGHLIGHT_CHARS

    fun paintCurrentLine() {
        // Current-line background (drawn first so token colors sit on top).
        if (caret !in 0..raw.length) return
        val lineStart = raw.lastIndexOf('\n', caret - 1) + 1
        var lineEnd = raw.indexOf('\n', caret)
        if (lineEnd < 0) lineEnd = raw.length
        if (lineEnd > lineStart) {
            builder.addStyle(SpanStyle(background = colors.currentLine), lineStart, lineEnd)
        }
    }

    fun paintHighlight() {
        if (tsSpans != null && withinBudget) {
            paintRainbowBrackets()
            paintTsSpans()
            paintTrailingWhitespace()
        } else if (spec != null && withinBudget) {
            paintRainbowBrackets()
            val covered = paintSpecTokens()
            paintFunctions(covered)
            paintTypes(covered)
            paintTrailingWhitespace()
        }
    }

    private fun paintRainbowBrackets() {
        // Rainbow brackets first, so string/comment spec spans repaint any brackets inside them.
        if (colors.brackets.isEmpty()) return
        var depth = 0
        val n = colors.brackets.size
        for (i in raw.indices) {
            when (raw[i]) {
                '(', '[', '{' -> {
                    builder.addStyle(SpanStyle(color = colors.brackets[depth % n]), i, i + 1)
                    depth++
                }
                ')', ']', '}' -> {
                    depth = (depth - 1).coerceAtLeast(0)
                    builder.addStyle(SpanStyle(color = colors.brackets[depth % n]), i, i + 1)
                }
            }
        }
    }

    private fun paintTsSpans() {
        // Tree-sitter colours paint on top of the rainbow brackets.
        for (s in tsSpans.orEmpty()) {
            val a = s.start.coerceIn(0, raw.length)
            val b = s.end.coerceIn(a, raw.length)
            if (b > a) builder.addStyle(SpanStyle(color = s.color), a, b)
        }
    }

    private fun paintSpecTokens(): BooleanArray {
        val covered = BooleanArray(raw.length)
        for (match in spec!!.regex.findAll(raw)) {
            val kind = spec.kindFor(match) ?: continue
            val first = match.range.first
            val lastExclusive = match.range.last + 1
            builder.addStyle(SpanStyle(color = colors.colorFor(kind)), first, lastExclusive)
            for (p in first until lastExclusive) covered[p] = true
        }
        return covered
    }

    private fun paintFunctions(covered: BooleanArray) {
        // Function calls: an identifier immediately before "(", where not already a token.
        for (m in SyntaxTransformation.FUNCTION_REGEX.findAll(raw)) {
            if (rangeCovered(covered, m.range)) continue
            builder.addStyle(SpanStyle(color = colors.function), m.range.first, m.range.last + 1)
            for (p in m.range) covered[p] = true
        }
    }

    private fun paintTypes(covered: BooleanArray) {
        // Types: Capitalized identifiers not already covered.
        for (m in SyntaxTransformation.TYPE_REGEX.findAll(raw)) {
            if (rangeCovered(covered, m.range)) continue
            builder.addStyle(SpanStyle(color = colors.type), m.range.first, m.range.last + 1)
        }
    }

    private fun paintTrailingWhitespace() {
        // Trailing whitespace: a subtle background flag per line.
        for (m in SyntaxTransformation.TRAILING_WS_REGEX.findAll(raw)) {
            val first = m.range.first
            val last = m.range.last + 1
            builder.addStyle(SpanStyle(background = colors.trailingWhitespace), first, last)
        }
    }

    fun paintBracketMatch() {
        // Bracket matching: check the char before and at the caret. Finding the partner is a scan
        // that can run to the end of the document, so it shares the highlighting budget.
        if (!withinBudget || caret !in 0..raw.length) return
        val candidate = bracketCandidate() ?: return
        val other = matchingBracketIndex(raw, candidate)
        if (other < 0) return
        builder.addStyle(SpanStyle(background = colors.matchedBracket), candidate, candidate + 1)
        builder.addStyle(SpanStyle(background = colors.matchedBracket), other, other + 1)
    }

    private fun bracketCandidate(): Int? = when {
        caret > 0 && raw[caret - 1] in "()[]{}" -> caret - 1
        caret < raw.length && raw[caret] in "()[]{}" -> caret
        else -> null
    }

    fun paintMatches(matches: List<IntRange>, activeMatch: Int) {
        matches.forEachIndexed { index, range ->
            if (range.isEmpty()) return@forEachIndexed
            val background = if (index == activeMatch) colors.activeMatch else colors.match
            builder.addStyle(
                SpanStyle(background = background),
                range.first.coerceIn(0, raw.length),
                (range.last + 1).coerceIn(0, raw.length),
            )
        }
    }
}

/** True if any index in [range] is already marked in [covered]. */
private fun rangeCovered(covered: BooleanArray, range: IntRange): Boolean {
    for (p in range) if (p in covered.indices && covered[p]) return true
    return false
}
