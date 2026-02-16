package com.vayunmathur.notes

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.unit.sp

/**
 * Converts a Markdown string into an AnnotatedString for Jetpack Compose.
 * @param mdtext The raw markdown text.
 * @param showMarkers If false, the formatting symbols (#, *, etc.) are hidden and occupy no space.
 */
fun MarkdownAnnotatedString(mdtext: String, showMarkers: Boolean = true): AnnotatedString {
    return buildAnnotatedString {
        append(mdtext)

        // Helper to hide formatting markers if showMarkers is false.
        // We use TextGeometricTransform with scaleX = 0f to collapse the horizontal width.
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

        // 1. Headers (e.g., # Heading)
        val headerRegex = Regex("(?m)^(#{1,6} )(.*(?:\\R|$))")
        headerRegex.findAll(mdtext).forEach { match ->
            val start = match.range.first
            val end = match.range.last + 1

            val markers = match.groups[1]!! // The "### " part
            val content = match.groups[2]!! // The text + newline

            val level = markers.value.trim().length
            val fontSize = (32 - (level * 2)).sp

            // Hide the hashes and the space if requested
            hideRange(markers.range.first, markers.range.last + 1)

            // SpanStyle for the text content
            addStyle(
                style = SpanStyle(
                    fontWeight = FontWeight.Bold,
                    fontSize = fontSize,
                ),
                start = start,
                end = end
            )

            // ParagraphStyle for spacing (using 0.6f multiplier as requested)
            addStyle(
                style = ParagraphStyle(
                    lineHeight = fontSize * 0.6f,
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.None
                    )
                ),
                start = start,
                end = end
            )
        }

        // 2. Bold (**text** or __text__)
        val boldRegex = Regex("(\\*\\*|__)(.*?)\\1")
        boldRegex.findAll(mdtext).forEach { match ->
            val markerType = match.groups[1]!!.value
            val start = match.range.first
            val end = match.range.last + 1

            hideRange(start, start + markerType.length)
            hideRange(end - markerType.length, end)

            addStyle(
                style = SpanStyle(fontWeight = FontWeight.Bold),
                start = start,
                end = end
            )
        }

        // 3. Italic (*text* or _text_)
        val italicRegex = Regex("(?<!\\*)\\*(?!\\*)(.*?)(?<!\\*)\\*(?!\\*)|(?<!_)_(?!_)(.*?)(?<!_)_(?!_)")
        italicRegex.findAll(mdtext).forEach { match ->
            val start = match.range.first
            val end = match.range.last + 1

            hideRange(start, start + 1)
            hideRange(end - 1, end)

            addStyle(
                style = SpanStyle(fontStyle = FontStyle.Italic),
                start = start,
                end = end
            )
        }

        // 4. Inline Code (`code`)
        val codeRegex = Regex("`(.+?)`")
        codeRegex.findAll(mdtext).forEach { match ->
            val start = match.range.first
            val end = match.range.last + 1

            hideRange(start, start + 1)
            hideRange(end - 1, end)

            addStyle(
                style = SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = Color.LightGray.copy(alpha = 0.2f),
                    color = Color(0xFFD32F2F)
                ),
                start = start,
                end = end
            )
        }

        // 5. Strikethrough (~~text~~)
        val strikeRegex = Regex("~~(.+?)~~")
        strikeRegex.findAll(mdtext).forEach { match ->
            val start = match.range.first
            val end = match.range.last + 1

            hideRange(start, start + 2)
            hideRange(end - 2, end)

            addStyle(
                style = SpanStyle(textDecoration = TextDecoration.LineThrough),
                start = start,
                end = end
            )
        }

        // 6. Blockquotes
        val quoteRegex = Regex("(?m)^>\\s")
        quoteRegex.findAll(mdtext).forEach { match ->
            hideRange(match.range.first, match.range.last + 1)

            val lineEnd = mdtext.indexOf('\n', match.range.first).let { if (it == -1) mdtext.length else it }

            addStyle(
                style = SpanStyle(
                    color = Color.Gray,
                    fontStyle = FontStyle.Italic
                ),
                start = match.range.first,
                end = lineEnd
            )
        }
    }
}