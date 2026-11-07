package com.vayunmathur.library.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

private val mdListLineRegex = Regex("^(\\s*)([•*+-]|\\d+[.)])(\\s+.*)")
private val mdBlockMathRegex = Regex("""\$\$(.*?)\$\$""", RegexOption.DOT_MATCHES_ALL)
private val mdInlineMathRegex = Regex("""(?<![$\\])\$([^\s$](?:[^$]*[^\s$])?)\$(?!$)""")
private val mdHeaderRegex = Regex("(?m)^(#{1,6} )(.*(?:\\R|$))")
private val mdListItemRegex = Regex(
    "(?m)^(\\s*)([•*+-]|\\d+[.)])[^\\S\\r\\n]+" +
        "(?:\\[([ xX])][^\\S\\r\\n]+)?(.*(?:\\R|$))"
)
private val mdLatexFormatRegex = Regex(
    """\\(cancel|mathbf|mathrm|underline|mathtt|mathsf|mathit)\{((?:[^{}]|\{[^{}]*\})*)\}"""
)
private val mdLinkRegex = Regex("\\[(.*?)\\]\\((.*?)\\)")
private val mdBoldRegex = Regex("(\\*\\*|__)(.*?)\\1")
private val mdItalicRegex = Regex(
    "(?<!\\*)\\*(?!\\*)(.*?)(?<!\\*)\\*(?!\\*)|(?<!_)_(?!_)(.*?)(?<!_)_(?!_)"
)
private val mdFencedRegex = Regex(
    "(?m)^([^\\S\\r\\n]*```[^\\n]*)\\n([\\s\\S]*?)\\n([^\\S\\r\\n]*```[^\\S\\r\\n]*)$"
)
private val mdCodeRegex = Regex("`(.+?)`")
private val mdStrikethroughRegex = Regex("~~(.+?)~~")
private val mdBlockquoteRegex = Regex("(?m)^>\\s")
private val mdFracRegex = Regex(
    """\\frac\{((?:[^{}]|\{[^{}]*\})*)\}\{((?:[^{}]|\{[^{}]*\})*)\}"""
)
private val mdSqrtNRegex = Regex("""\\sqrt\[([^]]*)\]\{([^}]*)\}""")
private val mdSqrtRegex = Regex("""\\sqrt\{([^}]*)\}""")

private const val HEADER_BASE_SP = 32
private const val HEADER_STEP_SP = 2

private fun appendPreprocessedLine(
    out: StringBuilder,
    line: String,
    lines: List<String>,
    currentIndex: Int,
    process: Boolean,
    softWrap: Boolean
): Int {
    val specialMatch = specialMatchOf(line, process)
    if (specialMatch != null || isSpecialLine(line, process)) {
        out.appendSpecialLine(line, specialMatch)
        return currentIndex + 1
    }
    return appendMergedLine(out, lines, currentIndex, softWrap)
}

private fun specialMatchOf(line: String, process: Boolean): MatchResult? {
    if (!process) return null
    return mdListLineRegex.matchEntire(line)
}

private fun appendMergedLine(
    out: StringBuilder,
    lines: List<String>,
    currentIndex: Int,
    softWrap: Boolean
): Int {
    var index = currentIndex
    var merged = if (softWrap) lines[index].trim() else lines[index]
    if (softWrap) {
        while (index + 1 < lines.size && lines[index + 1].isNotBlank()) {
            val nextLine = lines[index + 1]
            if (isSpecialLine(nextLine, true)) break
            merged += " " + nextLine.trim()
            index++
        }
    }
    out.append(merged)
    if (!softWrap || index < lines.size - 1) {
        out.append("\n")
    }
    return index + 1
}

private fun isSpecialLine(line: String, process: Boolean): Boolean {
    if (!process) return false
    val trimmed = line.trimStart()
    return trimmed.startsWith("#") ||
        trimmed.startsWith(">") ||
        trimmed.startsWith("$$") ||
        mdListLineRegex.matchEntire(line) != null
}

private fun StringBuilder.appendSpecialLine(line: String, listMatch: MatchResult?) {
    if (listMatch == null) {
        append(line.trimEnd() + "\n")
        return
    }
    val rawIndent = listMatch.groups[1]!!.value
    val level = rawIndent.length / LIST_INDENT_SPACES
    val normalizedIndent = "  ".repeat(level)
    val marker = listMatch.groups[2]!!.value
    val rest = listMatch.groups[3]!!.value
    val newMarker = if (marker.length == 1 && "*+-".contains(marker)) "•" else marker
    append("$normalizedIndent$newMarker ${rest.trimStart()}\n")
}

private fun listMarkerSize(markerString: String, process: Boolean): TextUnit {
    if (!process) return TextUnit.Unspecified
    if (markerString == "•") return LIST_MARKER_LARGE_SP.sp
    return LIST_MARKER_SMALL_SP.sp
}

private fun AnnotatedString.Builder.applyListIndent(
    paragraphScope: AnnotatedString.Builder,
    indentation: String,
    markerString: String,
    start: Int,
    end: Int
) {
    val level = indentation.length / LIST_INDENT_SPACES
    val firstLineIndent = (LIST_INDENT_BASE_SP + (level * LIST_INDENT_STEP_SP)).sp
    val markerOffset = if (markerString.any { it.isDigit() }) {
        LIST_MARKER_NUMERIC_SP.sp
    } else {
        LIST_MARKER_BULLET_SP.sp
    }
    paragraphScope.addStyle(
        ParagraphStyle(
            textIndent = TextIndent(
                firstLine = firstLineIndent,
                restLine = (firstLineIndent.value + markerOffset.value).sp
            )
        ),
        start,
        end
    )
}

private const val LIST_INDENT_SPACES = 2
private const val LIST_INDENT_BASE_SP = 12
private const val LIST_INDENT_STEP_SP = 24
private const val LIST_MARKER_NUMERIC_SP = 32
private const val LIST_MARKER_BULLET_SP = 16
private const val LIST_MARKER_LARGE_SP = 18
private const val LIST_MARKER_SMALL_SP = 16

/**
 * Converts a Markdown string into an AnnotatedString for Jetpack Compose.
 * @param mdtext The raw markdown text.
 * @param showMarkers If false, the formatting symbols (#, *, etc.) are hidden and occupy no space.
 * @param process If true, the text is preprocessed for list and header normalization.
 * @param softWrap If true, newlines are merged and redundant blank lines removed.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod")
fun parseMarkdown(
    mdtext: String,
    showMarkers: Boolean = true,
    process: Boolean = true,
    softWrap: Boolean = true,
    searchQuery: String = "",
    searchIndex: Int = -1
): AnnotatedString {
    // 1. Preprocess text for newline rules and list normalization
    val processedText = if (process || softWrap) {
        val lines = mdtext.lines()
        buildString {
            var i = 0
            while (i < lines.size) {
                val line = lines[i]

                if (softWrap && line.isBlank()) {
                    while (i + 1 < lines.size && lines[i + 1].isBlank()) i++
                    i++
                    continue
                }

                i = appendPreprocessedLine(
                    this,
                    line,
                    lines,
                    currentIndex = i,
                    process = process,
                    softWrap = softWrap
                )
            }
        }.let { if (softWrap) it.trim() else it }
    } else {
        mdtext
    }

    // 2. Math content formatting if markers are hidden
    val finalText = if (!showMarkers) {
        // Block Math $$ ... $$
        mdBlockMathRegex.replace(processedText) {
            "$$" + formatMathContent(it.groupValues[1]) + "$$"
        }.let { text ->
            // Inline Math $ ... $
            mdInlineMathRegex.replace(text) {
                "$" + formatMathContent(it.groupValues[1]) + "$"
            }
        }
    } else {
        processedText
    }

    return buildAnnotatedString {
        append(finalText)

        // Helper to hide formatting markers
        fun hideRange(start: Int, end: Int) {
            if (!showMarkers && start < end) {
                addStyle(
                    style = SpanStyle(
                        color = Color.Transparent,
                        fontSize = 0.sp,
                        textGeometricTransform = TextGeometricTransform(scaleX = 0f)
                    ),
                    start = start,
                    end = end
                )
            }
        }

        // 1. Headers
        mdHeaderRegex.findAll(finalText).forEach { match ->
            val start = match.range.first
            val end = match.range.last + 1
            val markers = match.groups[1]!!
            val level = markers.value.trim().length
            val fontSize = (HEADER_BASE_SP - (level * HEADER_STEP_SP)).sp

            hideRange(markers.range.first, markers.range.last + 1)

            addStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = fontSize), start, end)
            addStyle(
                ParagraphStyle(
                    lineHeight = fontSize * LINE_HEIGHT_FACTOR,
                    lineHeightStyle = LineHeightStyle(
                        LineHeightStyle.Alignment.Center,
                        LineHeightStyle.Trim.Both
                    )
                ),
                start,
                end
            )
        }

        // 2. Lists
        mdListItemRegex.findAll(finalText).forEach { match ->
            val start = match.range.first
            val end = match.range.last + 1
            val indentation = match.groups[1]!!.value
            val markerString = match.groups[2]!!.value
            val taskStatus = match.groups[3]?.value
            val contentStart = match.groups[4]!!.range.first

            if (!showMarkers) {
                hideRange(match.groups[1]!!.range.first, match.groups[1]!!.range.last + 1)
            }

            if (process) {
                applyListIndent(
                    paragraphScope = this,
                    indentation = indentation,
                    markerString = markerString,
                    start = start,
                    end = end
                )
            }

            addStyle(
                SpanStyle(
                    color = if (showMarkers) Color.Gray else Color.Unspecified,
                    fontWeight = FontWeight.Bold
                ).copy(fontSize = listMarkerSize(markerString, process)),
                start,
                contentStart
            )

            if (!showMarkers && taskStatus != null) {
                addStyle(SpanStyle(color = Color.Transparent), match.groups[2]!!.range.first, contentStart)
            }

            if (taskStatus != null && taskStatus.lowercase() == "x") {
                addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = Color.Gray), contentStart, end)
            }
        }

        // 3. Math Formulas

        // Block Math $$ ... $$
        mdBlockMathRegex.findAll(finalText).forEach { match ->
            val start = match.range.first
            val end = match.range.last + 1
            hideRange(start, start + 2)
            hideRange(end - 2, end)

            addStyle(
                SpanStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic),
                start,
                end
            )
            if (process) {
                addStyle(
                    ParagraphStyle(textAlign = TextAlign.Center),
                    start,
                    end
                )
            }
        }

        // Inline Math $ ... $
        mdInlineMathRegex.findAll(finalText).forEach { match ->
            val start = match.range.first
            val end = match.range.last + 1
            hideRange(start, start + 1)
            hideRange(end - 1, end)

            addStyle(
                SpanStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic),
                start,
                end
            )
        }

        // 4. LaTeX Specific Formatting (\cancel, \mathbf, etc.)
        mdLatexFormatRegex.findAll(finalText).forEach { match ->
            val start = match.range.first
            val end = match.range.last + 1
            val cmd = match.groups[1]!!.value
            val contentStart = match.groups[2]!!.range.first
            val contentEnd = match.groups[2]!!.range.last + 1

            hideRange(start, contentStart)
            hideRange(contentEnd, end)

            val style = when (cmd) {
                "cancel" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                "mathbf" -> SpanStyle(fontWeight = FontWeight.Bold)
                "mathrm" -> SpanStyle(fontStyle = FontStyle.Normal)
                "underline" -> SpanStyle(textDecoration = TextDecoration.Underline)
                "mathtt" -> SpanStyle(fontFamily = FontFamily.Monospace)
                "mathsf" -> SpanStyle(fontFamily = FontFamily.SansSerif)
                "mathit" -> SpanStyle(fontStyle = FontStyle.Italic)
                else -> null
            }
            if (style != null) addStyle(style, contentStart, contentEnd)
        }

        // 5. Inline Formatting (Post-processing)
        
        // Links
        mdLinkRegex.findAll(finalText).forEach { match ->
            val textStart = match.groups[1]!!.range.first
            val textEnd = match.groups[1]!!.range.last + 1
            val url = match.groups[2]!!.value

            hideRange(match.range.first, textStart)
            hideRange(textEnd, match.range.last + 1)

            addStyle(
                SpanStyle(
                    color = Color(LINK_BLUE_HEX),
                    textDecoration = TextDecoration.Underline
                ),
                textStart,
                textEnd
            )
            addLink(
                LinkAnnotation.Url(url),
                textStart,
                textEnd
            )
        }

        applyInlineSpans(this, finalText, ::hideRange, fencedRangesOf(this, finalText))

        if (searchQuery.isNotEmpty()) {
            val query = searchQuery.lowercase()
            val text = finalText.lowercase()
            var index = text.indexOf(query)
            var count = 0
            while (index >= 0) {
                val isCurrent = count == searchIndex
                addStyle(
                    SpanStyle(
                        background = if (isCurrent) Color(SEARCH_CURRENT_HEX) else Color.Yellow,
                        color = Color.Black
                    ),
                    index,
                    index + query.length
                )
                index = text.indexOf(query, index + query.length)
                count++
            }
        }
    }
}

private const val LINK_BLUE_HEX = 0xFF2196F3
private const val CODE_RED_HEX = 0xFFD32F2F
private const val SEARCH_CURRENT_HEX = 0xFFFFA500
private const val CODE_BACKGROUND_ALPHA = 0.2f
private const val LINE_HEIGHT_FACTOR = 1.3f

private fun fencedRangesOf(
    scope: AnnotatedString.Builder,
    finalText: String
): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    // Fenced code blocks ``` ... ``` (must run before inline code so the
    // single-backtick regex does not chew on the fence backticks).
    mdFencedRegex.findAll(finalText).forEach { match ->
        ranges += match.range
        val content = match.groups[2]!!
        scope.styleFenceContent(content)
    }
    return ranges
}

private fun AnnotatedString.Builder.styleFenceContent(content: MatchGroup) {
    addStyle(
        SpanStyle(
            fontFamily = FontFamily.Monospace,
            background = Color.LightGray.copy(CODE_BACKGROUND_ALPHA)
        ),
        content.range.first,
        content.range.last + 1
    )
}

private fun applyInlineSpans(
    scope: AnnotatedString.Builder,
    finalText: String,
    hideRange: (Int, Int) -> Unit,
    fencedRanges: List<IntRange>
) {
    // Bold
    mdBoldRegex.findAll(finalText).forEach { match ->
        val marker = match.groups[1]!!.value
        hideRange(match.range.first, match.range.first + marker.length)
        hideRange(match.range.last + 1 - marker.length, match.range.last + 1)
        scope.addStyle(
            SpanStyle(fontWeight = FontWeight.Bold),
            match.range.first,
            match.range.last + 1
        )
    }

    // Italic
    mdItalicRegex.findAll(finalText).forEach { match ->
        hideRange(match.range.first, match.range.first + 1)
        hideRange(match.range.last, match.range.last + 1)
        scope.addStyle(
            SpanStyle(fontStyle = FontStyle.Italic),
            match.range.first,
            match.range.last + 1
        )
    }

    // Inline code (skips fenced blocks collected above).
    mdCodeRegex.findAll(finalText).forEach { match ->
        if (fencedRanges.any { match.range.first in it }) return@forEach
        hideRange(match.range.first, match.range.first + 1)
        hideRange(match.range.last, match.range.last + 1)
        scope.addStyle(
            SpanStyle(
                fontFamily = FontFamily.Monospace,
                background = Color.LightGray.copy(CODE_BACKGROUND_ALPHA),
                color = Color(CODE_RED_HEX)
            ),
            match.range.first,
            match.range.last + 1
        )
    }

    // Strikethrough
    mdStrikethroughRegex.findAll(finalText).forEach { match ->
        hideRange(match.range.first, match.range.first + STRIKE_MARKER_LEN)
        hideRange(match.range.last - 1, match.range.last + 1)
        scope.addStyle(
            SpanStyle(textDecoration = TextDecoration.LineThrough),
            match.range.first,
            match.range.last + 1
        )
    }

    // Blockquotes
    mdBlockquoteRegex.findAll(finalText).forEach { match ->
        hideRange(match.range.first, match.range.last + 1)
        val lineEnd = finalText.indexOf('\n', match.range.first).let {
            if (it == -1) finalText.length else it
        }
        scope.addStyle(
            SpanStyle(color = Color.Gray, fontStyle = FontStyle.Italic),
            match.range.first,
            lineEnd
        )
    }
}

private const val STRIKE_MARKER_LEN = 2

private val latexCommands = mapOf(
    // Greek letters (Lowercase)
    "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ", "\\delta" to "δ",
    "\\epsilon" to "ε", "\\zeta" to "ζ", "\\eta" to "η", "\\theta" to "θ",
    "\\iota" to "ι", "\\kappa" to "κ", "\\lambda" to "λ", "\\mu" to "μ",
    "\\nu" to "ν", "\\xi" to "ξ", "\\omicron" to "ο", "\\pi" to "π",
    "\\rho" to "ρ", "\\sigma" to "σ", "\\tau" to "τ", "\\upsilon" to "υ",
    "\\phi" to "φ", "\\chi" to "χ", "\\psi" to "ψ", "\\omega" to "ω",
    "\\varepsilon" to "ε", "\\vartheta" to "ϑ", "\\varpi" to "ϖ",
    "\\varrho" to "ϱ", "\\varsigma" to "ς", "\\varphi" to "ϕ",

    // Greek letters (Uppercase)
    "\\Alpha" to "Α", "\\Beta" to "Β", "\\Gamma" to "Γ", "\\Delta" to "Δ",
    "\\Epsilon" to "Ε", "\\Zeta" to "Ζ", "\\Eta" to "Η", "\\Theta" to "Θ",
    "\\Iota" to "Ι", "\\Kappa" to "Κ", "\\Lambda" to "Λ", "\\Mu" to "Μ",
    "\\Nu" to "Ν", "\\Xi" to "Ξ", "\\Omicron" to "Ο", "\\Pi" to "Π",
    "\\Rho" to "Ρ", "\\Sigma" to "Σ", "\\Tau" to "Τ", "\\Upsilon" to "Υ",
    "\\Phi" to "Φ", "\\Chi" to "Χ", "\\Psi" to "Ψ", "\\Omega" to "Ω",

    // Operators
    "\\times" to "×", "\\cdot" to "·", "\\div" to "÷", "\\pm" to "±", "\\mp" to "∓",
    "\\oplus" to "⊕", "\\ominus" to "⊖", "\\otimes" to "⊗", "\\oslash" to "⊘",
    "\\odot" to "⊙", "\\bullet" to "•", "\\ast" to "∗", "\\star" to "★",
    "\\circ" to "∘", "\\dagger" to "†", "\\ddagger" to "‡",

    // Relations
    "\\leq" to "≤", "\\le" to "≤", "\\geq" to "≥", "\\ge" to "≥",
    "\\neq" to "≠", "\\ne" to "≠", "\\approx" to "≈", "\\cong" to "≅",
    "\\equiv" to "≡", "\\sim" to "∼", "\\propto" to "∝", "\\perp" to "⊥",
    "\\parallel" to "∥", "\\subset" to "⊂", "\\subseteq" to "⊆",
    "\\supset" to "⊃", "\\supseteq" to "⊇", "\\in" to "∈", "\\notin" to "∉",
    "\\ni" to "∋", "\\forall" to "∀", "\\exists" to "∃", "\\neg" to "¬",
    "\\approxeq" to "approx", "\\mid" to "∣", "\\cap" to "∩", "\\cup" to "∪",

    // Logic & Arrows
    "\\to" to "→", "\\rightarrow" to "→", "\\leftarrow" to "←",
    "\\Rightarrow" to "⇒", "\\Leftarrow" to "⇐", "\\iff" to "⇔",
    "\\leftrightarrow" to "↔", "\\uparrow" to "↑", "\\downarrow" to "↓",
    "\\mapsto" to "↦", "\\implies" to "⟹",

    // Functions
    "\\sin" to "sin", "\\cos" to "cos", "\\tan" to "tan",
    "\\cot" to "cot", "\\sec" to "sec", "\\csc" to "csc",
    "\\arcsin" to "arcsin", "\\arccos" to "arccos", "\\arctan" to "arctan",
    "\\log" to "log", "\\ln" to "ln", "\\exp" to "exp",
    "\\lim" to "lim", "\\max" to "max", "\\min" to "min",
    "\\sup" to "sup", "\\inf" to "inf", "\\det" to "det",
    "\\ker" to "ker", "\\deg" to "deg", "\\gcd" to "gcd",

    // Delimiters
    "\\langle" to "⟨", "\\rangle" to "⟩", "\\lceil" to "⌈", "\\rceil" to "⌉",
    "\\lfloor" to "⌊", "\\rfloor" to "⌋",

    // Others
    "\\infty" to "∞", "\\partial" to "∂", "\\nabla" to "∇",
    "\\triangle" to "△", "\\angle" to "∠", "\\dots" to "...",
    "\\cdots" to "...", "\\vdots" to "⋮", "\\ddots" to "⋱",
    "\\quad" to "  ", "\\qquad" to "    ", "\\," to " ", "\\;" to " ",
    "\\!" to "", "\\sum" to "∑", "\\int" to "∫", "\\prod" to "∏",
    "\\degree" to "°", "\\{" to "{", "\\}" to "}", "\\\\" to "\n",
)

private fun formatMathContent(content: String): String {
    var result = content

    // 1. Strip \left and \right (but not \leftarrow / \rightarrow, which are
    // commands of their own and are replaced below).
    result = result
        .replace(Regex("\\\\left(?![A-Za-z])"), "")
        .replace(Regex("\\\\right(?![A-Za-z])"), "")

    // 2. Handle \frac{a}{b} -> ((a)/(b))
    repeat(FRAC_REPLACE_PASSES) {
        result = result.replace(mdFracRegex, "(($1)/($2))")
    }

    // 3. Handle \sqrt[n]{a} or \sqrt{a}
    result = result.replace(mdSqrtNRegex, "($2)^(1/$1)")
    result = result.replace(mdSqrtRegex, "√($1)")

    // 4. Replace known commands (Longer commands first to avoid partial matches)
    latexCommands.entries.sortedByDescending { it.key.length }.forEach { (cmd, replacement) ->
        result = result.replace(cmd, replacement)
    }

    return result
}

private const val FRAC_REPLACE_PASSES = 3
