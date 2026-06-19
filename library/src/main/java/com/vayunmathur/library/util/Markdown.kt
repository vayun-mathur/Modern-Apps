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

/**
 * Converts a Markdown string into an AnnotatedString for Jetpack Compose.
 * @param mdtext The raw markdown text.
 * @param showMarkers If false, the formatting symbols (#, *, etc.) are hidden and occupy no space.
 * @param process If true, the text is preprocessed for list and header normalization.
 * @param softWrap If true, the text is preprocessed for newline rules (single newlines merged, redundant blank lines removed).
 */
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
                    i++; continue
                }

                val trimmed = line.trimStart()
                val listMatch = if (process) Regex("^(\\s*)([•*+-]|\\d+[.)])(\\s+.*)").matchEntire(line) else null
                val isCurrentSpecial = process && (trimmed.startsWith("#") || trimmed.startsWith(">") || trimmed.startsWith("$$") || listMatch != null)

                if (isCurrentSpecial) {
                    if (listMatch != null) {
                        val rawIndent = listMatch.groups[1]!!.value
                        val level = rawIndent.length / 2
                        val normalizedIndent = "  ".repeat(level)
                        val marker = listMatch.groups[2]!!.value
                        val rest = listMatch.groups[3]!!.value
                        val newMarker = if (marker.length == 1 && "*+-".contains(marker)) "•" else marker
                        append("$normalizedIndent$newMarker ${rest.trimStart()}\n")
                    } else {
                        append(line.trimEnd() + "\n")
                    }
                } else {
                    var merged = if (softWrap) line.trim() else line
                    if (softWrap) {
                        while (i + 1 < lines.size && lines[i + 1].isNotBlank()) {
                            val nextLine = lines[i + 1]
                            val nextTrimmed = nextLine.trimStart()
                            val nextListMatch = if (process) Regex("^(\\s*)([•*+-]|\\d+[.)])(\\s+.*)").matchEntire(nextLine) else null
                            val isNextSpecial = process && (nextTrimmed.startsWith("#") || nextTrimmed.startsWith(">") || nextTrimmed.startsWith("$$") || nextListMatch != null)

                            if (isNextSpecial) break

                            merged += " " + nextLine.trim()
                            i++
                        }
                    }
                    append(merged)
                    if (!softWrap || i < lines.size - 1) {
                        append("\n")
                    }
                }
                i++
            }
        }.let { if (softWrap) it.trim() else it }
    } else {
        mdtext
    }

    // 2. Math content formatting if markers are hidden
    val finalText = if (!showMarkers) {
        // Block Math $$ ... $$
        Regex("""\$\$(.*?)\$\$""", RegexOption.DOT_MATCHES_ALL).replace(processedText) {
            "$$" + formatMathContent(it.groupValues[1]) + "$$"
        }.let { text ->
            // Inline Math $ ... $
            Regex("""(?<![$\\])\$([^\s$](?:[^$]*[^\s$])?)\$(?!$)""").replace(text) {
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
        val headerRegex = Regex("(?m)^(#{1,6} )(.*(?:\\R|$))")
        headerRegex.findAll(finalText).forEach { match ->
            val start = match.range.first
            val end = match.range.last + 1
            val markers = match.groups[1]!!
            val level = markers.value.trim().length
            val fontSize = (32 - (level * 2)).sp

            hideRange(markers.range.first, markers.range.last + 1)

            addStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = fontSize), start, end)
            addStyle(
                ParagraphStyle(
                    lineHeight = fontSize * 1.3f,
                    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)
                ),
                start,
                end
            )
        }

        // 2. Lists
        val listRegex = Regex("(?m)^(\\s*)([•*+-]|\\d+[.)])[^\\S\\r\\n]+(?:\\[([ xX])][^\\S\\r\\n]+)?(.*(?:\\R|$))")
        listRegex.findAll(finalText).forEach { match ->
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
                val level = indentation.length / 2
                val indentBase = 12.sp
                val indentStep = 24.sp
                val firstLineIndent = (indentBase.value + (level * indentStep.value)).sp
                val markerOffset = if (markerString.any { it.isDigit() }) 32.sp else 16.sp

                addStyle(
                    ParagraphStyle(
                        textIndent = TextIndent(firstLine = firstLineIndent, restLine = (firstLineIndent.value + markerOffset.value).sp)
                    ),
                    start,
                    end
                )
            }

            addStyle(
                SpanStyle(
                    color = if (showMarkers) Color.Gray else Color.Unspecified,
                    fontWeight = FontWeight.Bold
                ).copy(fontSize = if (process) (if (markerString == "•") 18.sp else 16.sp) else TextUnit.Unspecified),
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
        Regex("""\$\$(.*?)\$\$""", RegexOption.DOT_MATCHES_ALL).findAll(finalText).forEach { match ->
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
        Regex("""(?<![$\\])\$([^\s$](?:[^$]*[^\s$])?)\$(?!$)""").findAll(finalText).forEach { match ->
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
        val latexFormatRegex = Regex("""\\(cancel|mathbf|mathrm|underline|mathtt|mathsf|mathit)\{((?:[^{}]|\{[^{}]*\})*)\}""")
        latexFormatRegex.findAll(finalText).forEach { match ->
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
        Regex("\\[(.*?)\\]\\((.*?)\\)").findAll(finalText).forEach { match ->
            val textStart = match.groups[1]!!.range.first
            val textEnd = match.groups[1]!!.range.last + 1
            val url = match.groups[2]!!.value

            hideRange(match.range.first, textStart)
            hideRange(textEnd, match.range.last + 1)

            addStyle(
                SpanStyle(color = Color(0xFF2196F3), textDecoration = TextDecoration.Underline),
                textStart,
                textEnd
            )
            addLink(
                LinkAnnotation.Url(url),
                textStart,
                textEnd
            )
        }

        // Bold
        Regex("(\\*\\*|__)(.*?)\\1").findAll(finalText).forEach { match ->
            val m = match.groups[1]!!.value
            hideRange(match.range.first, match.range.first + m.length)
            hideRange(match.range.last + 1 - m.length, match.range.last + 1)
            addStyle(SpanStyle(fontWeight = FontWeight.Bold), match.range.first, match.range.last + 1)
        }

        // Italic
        Regex("(?<!\\*)\\*(?!\\*)(.*?)(?<!\\*)\\*(?!\\*)|(?<!_)_(?!_)(.*?)(?<!_)_(?!_)").findAll(finalText).forEach { match ->
            hideRange(match.range.first, match.range.first + 1)
            hideRange(match.range.last, match.range.last + 1)
            addStyle(SpanStyle(fontStyle = FontStyle.Italic), match.range.first, match.range.last + 1)
        }

        // Code
        Regex("`(.+?)`").findAll(finalText).forEach { match ->
            hideRange(match.range.first, match.range.first + 1)
            hideRange(match.range.last, match.range.last + 1)
            addStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, background = Color.LightGray.copy(0.2f), color = Color(0xFFD32F2F)),
                match.range.first,
                match.range.last + 1
            )
        }

        // Strikethrough
        Regex("~~(.+?)~~").findAll(finalText).forEach { match ->
            hideRange(match.range.first, match.range.first + 2)
            hideRange(match.range.last - 1, match.range.last + 1)
            addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), match.range.first, match.range.last + 1)
        }

        // Blockquotes
        Regex("(?m)^>\\s").findAll(finalText).forEach { match ->
            hideRange(match.range.first, match.range.last + 1)
            val lineEnd = finalText.indexOf('\n', match.range.first).let { if (it == -1) finalText.length else it }
            addStyle(SpanStyle(color = Color.Gray, fontStyle = FontStyle.Italic), match.range.first, lineEnd)
        }

        if (searchQuery.isNotEmpty()) {
            val query = searchQuery.lowercase()
            val text = finalText.lowercase()
            var index = text.indexOf(query)
            var count = 0
            while (index >= 0) {
                val isCurrent = count == searchIndex
                addStyle(
                    SpanStyle(
                        background = if (isCurrent) Color(0xFFFFA500) else Color.Yellow,
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

    // 1. Strip \left and \right
    result = result.replace("\\left", "").replace("\\right", "")

    // 2. Handle \frac{a}{b} -> ((a)/(b))
    repeat(3) {
        result = result.replace(Regex("""\\frac\{((?:[^{}]|\{[^{}]*\})*)\}\{((?:[^{}]|\{[^{}]*\})*)\}"""), "(($1)/($2))")
    }

    // 3. Handle \sqrt[n]{a} or \sqrt{a}
    result = result.replace(Regex("""\\sqrt\[([^]]*)\]\{([^}]*)\}"""), "($2)^(1/$1)")
    result = result.replace(Regex("""\\sqrt\{([^}]*)\}"""), "√($1)")

    // 4. Replace known commands (Longer commands first to avoid partial matches)
    latexCommands.entries.sortedByDescending { it.key.length }.forEach { (cmd, replacement) ->
        result = result.replace(cmd, replacement)
    }

    return result
}
