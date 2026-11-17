package com.vayunmathur.flashcards.domain

/**
 * Best-effort conversion between the app's **markdown** and the HTML that Anki
 * stores in note fields and card templates. Text-only: media is dropped.
 *
 * The conversions are intentionally lossy and lightweight (regex-based) — this is
 * a text flashcard app, not a full HTML engine — but they round-trip the common
 * inline/block constructs (bold, italic, code, links, lists, headings, breaks).
 */
object HtmlConvert {
    private val placeholderRegex = Regex("""\{\{[^}]+\}\}""")
    private val bold = Regex("""\*\*(.+?)\*\*""")
    private val italic = Regex("""(?<!\*)\*(?!\*)(.+?)(?<!\*)\*(?!\*)""")
    private val code = Regex("`(.+?)`")
    private val image = Regex("""!\[(.*?)]\((.*?)\)""")
    private val link = Regex("""\[(.*?)]\((.*?)\)""")

    /** Shallowest HTML heading level converted to a single `#`. */
    private const val MIN_HEADING_LEVEL = 1

    /** Deepest HTML heading level converted (###### ). */
    private const val MAX_HEADING_LEVEL = 6

    // -- HTML -> Markdown --------------------------------------------------

    /** Converts an Anki HTML fragment to markdown, dropping media. */
    fun htmlToMarkdown(html: String): String {
        var s = html
        s = Regex("""\[sound:[^]]*]""").replace(s, "")
        // Links before generic tag stripping (needs the href attribute).
        s = Regex("""(?is)<a[^>]*?href="([^"]*)"[^>]*>(.*?)</a>""").replace(s) {
            "[${it.groupValues[2]}](${it.groupValues[1]})"
        }
        // Images -> markdown image, keeping only the basename of the src (the
        // media file is copied alongside on import/export).
        s = Regex("""(?is)<img[^>]*?>""").replace(s) { match ->
            val tag = match.value
            val src = Regex("""src="([^"]*)"""").find(tag)?.groupValues?.get(1).orEmpty()
            val alt = Regex("""alt="([^"]*)"""").find(tag)?.groupValues?.get(1).orEmpty()
            if (src.isBlank()) "" else "![$alt](${src.substringAfterLast('/')})"
        }
        s = Regex("""(?i)<br\s*/?>""").replace(s, "\n")
        s = Regex("""(?i)<hr[^>]*/?>""").replace(s, "\n---\n")
        for (level in MIN_HEADING_LEVEL..MAX_HEADING_LEVEL) {
            s = Regex("""(?i)<h$level[^>]*>""").replace(s, "#".repeat(level) + " ")
        }
        s = Regex("""(?i)<li[^>]*>""").replace(s, "- ")
        s = Regex("""(?i)</(div|p|li|h[1-6]|blockquote|tr|ul|ol)>""").replace(s, "\n")
        s = Regex("""(?i)</?(b|strong)>""").replace(s, "**")
        s = Regex("""(?i)</?(i|em)>""").replace(s, "*")
        s = Regex("""(?i)</?(code|pre)>""").replace(s, "`")
        // Strip any remaining tags.
        s = Regex("""(?s)<[^>]+>""").replace(s, "")
        s = decodeEntities(s)
        // Collapse excess blank lines.
        s = Regex("""\n{3,}""").replace(s, "\n\n")
        return s.trim()
    }

    /**
     * Converts an Anki template ([qfmt]/[afmt]) HTML into our markdown template
     * syntax, preserving `{{…}}` placeholders (which use the same syntax in Anki).
     */
    fun htmlTemplateToMarkdown(tpl: String): String =
        transformOutsidePlaceholders(tpl) { htmlToMarkdown(it) }

    // -- Markdown -> HTML --------------------------------------------------

    /** Converts a markdown note-field value to HTML for storage in `notes.flds`. */
    fun markdownFieldToHtml(md: String): String = inlineMarkdownToHtml(md).replace("\n", "<br>")

    /**
     * Converts a markdown card template to HTML, preserving `{{…}}` placeholders so
     * Anki can substitute them.
     */
    fun markdownTemplateToHtml(tpl: String): String =
        transformOutsidePlaceholders(tpl) { markdownFieldToHtml(it) }

    // -- helpers -----------------------------------------------------------

    /** Applies [transform] to the literal segments of [text], leaving `{{…}}` intact. */
    private fun transformOutsidePlaceholders(text: String, transform: (String) -> String): String {
        val out = StringBuilder()
        var last = 0
        placeholderRegex.findAll(text).forEach { match ->
            if (match.range.first > last) {
                out.append(transform(text.substring(last, match.range.first)))
            }
            out.append(match.value)
            last = match.range.last + 1
        }
        if (last < text.length) out.append(transform(text.substring(last)))
        return out.toString()
    }

    /** Inline markdown (bold/italic/code/links) -> HTML, escaping the plain text. */
    private fun inlineMarkdownToHtml(md: String): String {
        var s = escapeHtml(md)
        s = code.replace(s) { "<code>${it.groupValues[1]}</code>" }
        s = bold.replace(s) { "<b>${it.groupValues[1]}</b>" }
        s = italic.replace(s) { "<i>${it.groupValues[1]}</i>" }
        // Images before links: an image `![]()` also matches the link pattern.
        s = image.replace(s) { "<img src=\"${it.groupValues[2]}\" alt=\"${it.groupValues[1]}\">" }
        s = link.replace(s) { "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>" }
        return s
    }

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun decodeEntities(text: String): String = text
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}
