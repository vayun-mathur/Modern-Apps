package com.vayunmathur.appstore.domain

/**
 * Normalises a store description to the HTML subset Android's Html parser renders.
 *
 * Sources disagree on format: Play sends real HTML, while fastlane-sourced repos send
 * plain text whose line breaks are meaningful, often with inline HTML tags and Markdown
 * (`- ` bullets, `**bold**`, `[text](url)`) mixed in. Text without block-level HTML is
 * treated as the latter.
 */
object DescriptionMarkup {

    fun toHtml(raw: String): String {
        val text = raw.replace("\r\n", "\n").replace('\r', '\n')
            .replace(STRIPPED, "")
            .replace(ANCHOR_OPEN) { if (isSafeUrl(hrefOf(it.value))) it.value else "<a>" }
            .trim()
        if (BLOCK_HTML.containsMatchIn(text)) return text

        var inFence = false
        val lines = text.replace(EXCESS_NEWLINES, "\n\n").split('\n').mapNotNull { line ->
            when {
                FENCE.matches(line) -> { inFence = !inFence; null }
                inFence -> "<tt>${escape(line)}</tt>"
                else -> blockLine(line)
            }
        }
        return lines.joinToString("<br>")
    }

    private fun blockLine(line: String): String {
        if (RULE.matches(line)) return ""
        HEADING.matchEntire(line)?.let { return "<b>${inline(it.groupValues[1])}</b>" }
        BULLET.matchEntire(line)?.let {
            val indent = "&nbsp;&nbsp;".repeat(it.groupValues[1].length / 2)
            return "$indent• ${inline(it.groupValues[2])}"
        }
        QUOTE.matchEntire(line)?.let { return "<i>${inline(it.groupValues[1])}</i>" }
        return inline(line)
    }

    /** Markdown inline syntax, applied only to text between tags and code spans. */
    private fun inline(line: String): String = buildString {
        var last = 0
        for (token in INLINE_TOKEN.findAll(line)) {
            append(markdownInline(line.substring(last, token.range.first)))
            val value = token.value
            when {
                value.startsWith('`') -> append("<tt>${escape(value.trim('`'))}</tt>")
                AUTOLINK.matches(value) -> value.trim('<', '>').let { append("<a href=\"$it\">$it</a>") }
                else -> append(value)
            }
            last = token.range.last + 1
        }
        append(markdownInline(line.substring(last)))
    }

    private fun markdownInline(text: String): String = text
        .replace(IMAGE, "")
        .replace(LINK) {
            val (label, url) = it.destructured
            if (isSafeUrl(url)) "<a href=\"$url\">$label</a>" else label
        }
        .replace(BOLD, "<b>$2</b>")
        .replace(ITALIC_STAR, "<i>$1</i>")
        .replace(ITALIC_UNDERSCORE, "<i>$1</i>")
        .replace(STRIKE, "<s>$1</s>")

    private fun hrefOf(tag: String): String? =
        HREF.find(tag)?.let { m -> m.groupValues[2].ifEmpty { m.groupValues[3] } }

    /** Links open through ACTION_VIEW, so anything but web/mail could launch another app. */
    private fun isSafeUrl(url: String?): Boolean {
        val u = url?.trim()?.lowercase() ?: return false
        return u.startsWith("https://") || u.startsWith("http://") || u.startsWith("mailto:")
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private val IGNORE_CASE = setOf(RegexOption.IGNORE_CASE)

    // Html.fromHtml turns <img> into an object-replacement glyph and prints script/style bodies.
    private val STRIPPED = Regex(
        "<!--[\\s\\S]*?-->|<(script|style)\\b[\\s\\S]*?</\\1\\s*>|<img\\b[^>]*>",
        IGNORE_CASE,
    )
    private val ANCHOR_OPEN = Regex("<a\\b[^>]*>", IGNORE_CASE)
    private val HREF = Regex("href\\s*=\\s*(?:([\"'])(.*?)\\1|([^\\s>]+))", IGNORE_CASE)
    private val BLOCK_HTML = Regex("<(br|p|div|ul|ol|li|h[1-6]|table)\\b", IGNORE_CASE)
    private val EXCESS_NEWLINES = Regex("\\n{3,}")

    private val FENCE = Regex("\\s*```.*")
    private val RULE = Regex("\\s*([-*_])(\\s*\\1){2,}\\s*")
    private val HEADING = Regex("\\s{0,3}#{1,6}\\s+(.*?)\\s*#*\\s*")
    private val BULLET = Regex("(\\s*)[-*+•]\\s+(.*)")
    private val QUOTE = Regex("\\s*>\\s?(.*)")

    private val INLINE_TOKEN = Regex("`[^`]+`|<[^<>]+>")
    private val AUTOLINK = Regex("<(https?://|mailto:)[^\\s<>]+>", IGNORE_CASE)
    private val IMAGE = Regex("!\\[[^\\]]*]\\([^)]*\\)")
    private val LINK = Regex("\\[([^\\]]+)]\\(\\s*<?([^\\s)<>\"]+)>?(?:\\s+\"[^\"]*\")?\\s*\\)")
    private val BOLD = Regex("(\\*\\*|__)(?=\\S)(.+?)(?<=\\S)\\1")
    private val ITALIC_STAR = Regex("(?<![*\\w])\\*(?=\\S)(.+?)(?<=\\S)\\*(?![*\\w])")
    private val ITALIC_UNDERSCORE = Regex("(?<![_\\w])_(?=\\S)(.+?)(?<=\\S)_(?![_\\w])")
    private val STRIKE = Regex("~~(?=\\S)(.+?)(?<=\\S)~~")
}
