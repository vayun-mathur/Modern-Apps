package com.vayunmathur.securemail.domain.mail

/**
 * Light markup for plain-text mail bodies. Proton (and other clients that
 * derive text/plain from rich text) write links as `[text](url)`, bold as
 * `**x**`, italics as `*x*`/`_x_`, bullets as `* item`, headings as `# x`.
 * This turns those into styled runs without reflowing anything: line breaks
 * are kept exactly as sent, and only http(s)/mailto targets become links.
 * Bare URLs and email addresses are linked too.
 */
object PlainTextMarkup {

    /** One styled stretch of text; [url] set when it should be tappable. */
    data class Run(
        val text: String,
        val url: String? = null,
        val bold: Boolean = false,
        val italic: Boolean = false,
    )

    fun parse(text: String): List<Run> {
        val runs = mutableListOf<Run>()
        text.split("\n").forEachIndexed { index, rawLine ->
            if (index > 0) runs += Run("\n")
            val heading = HEADING.matchEntire(rawLine)
            val bullet = BULLET.matchEntire(rawLine)
            when {
                heading != null -> inline(heading.groupValues[1], runs, bold = true)
                bullet != null -> {
                    runs += Run(bullet.groupValues[1] + "\u2022 ")
                    inline(bullet.groupValues[2], runs, bold = false)
                }
                else -> inline(rawLine, runs, bold = false)
            }
        }
        return merge(runs)
    }

    private fun inline(line: String, out: MutableList<Run>, bold: Boolean) {
        var pos = 0
        for (m in TOKEN.findAll(line)) {
            if (m.range.first > pos) out += Run(line.substring(pos, m.range.first), bold = bold)
            val g = m.groupValues
            when {
                m.value.startsWith("![") -> out += linkOrRaw(g[1].ifBlank { "image" }, g[2], m.value, bold)
                g[3].isNotEmpty() -> out += linkOrRaw(g[3], g[4], m.value, bold)
                g[5].isNotEmpty() -> out += Run(g[5].removePrefix("mailto:"), g[5], bold = bold)
                g[6].isNotEmpty() || g[7].isNotEmpty() -> out += Run(g[6] + g[7], bold = true)
                g[8].isNotEmpty() || g[9].isNotEmpty() -> out += Run(g[8] + g[9], bold = bold, italic = true)
                g[10].isNotEmpty() -> {
                    val url = g[10].trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '\'', '"')
                    out += Run(url, url, bold = bold)
                    if (url.length < g[10].length) out += Run(g[10].substring(url.length), bold = bold)
                }
                g[11].isNotEmpty() -> out += Run(g[11], "mailto:${g[11]}", bold = bold)
            }
            pos = m.range.last + 1
        }
        if (pos < line.length) out += Run(line.substring(pos), bold = bold)
    }

    /** A markdown link only becomes tappable for safe schemes; anything else stays literal. */
    private fun linkOrRaw(label: String, target: String, raw: String, bold: Boolean): Run =
        if (SAFE_SCHEME.containsMatchIn(target)) Run(label, target, bold = bold) else Run(raw, bold = bold)

    /** Joins adjacent runs with identical styling so the UI builds fewer spans. */
    private fun merge(runs: List<Run>): List<Run> {
        val out = mutableListOf<Run>()
        for (r in runs) {
            if (r.text.isEmpty()) continue
            val last = out.lastOrNull()
            if (last != null && last.joinsWith(r)) {
                out[out.lastIndex] = last.copy(text = last.text + r.text)
            } else {
                out += r
            }
        }
        return out
    }

    /** Two plain (non-link) runs with the same weight and slant render identically. */
    private fun Run.joinsWith(next: Run): Boolean =
        url == null && next.url == null && bold == next.bold && italic == next.italic

    private val HEADING = Regex("""^#{1,6}\s+(.+?)\s*#*\s*$""")
    private val BULLET = Regex("""^(\s*)[*+-]\s+(.*)$""")
    private val SAFE_SCHEME = Regex("""^(https?://|mailto:)""", RegexOption.IGNORE_CASE)
    private val TOKEN = Regex(
        """!\[([^\]\n]*)]\(([^)\s]+)\)""" + // 1,2 image: shown as a link, never loaded
            """|\[([^\]\n]+)]\(([^)\s]+)\)""" + // 3,4 link
            """|<((?:https?://|mailto:)[^>\s]+)>""" + // 5 autolink
            """|\*\*([^*\n]+)\*\*|__([^_\n]+)__""" + // 6,7 bold
            """|(?<![\w*])\*([^*\s](?:[^*\n]*[^*\s])?)\*(?![\w*])""" + // 8 italic
            """|(?<![\w_])_([^_\s](?:[^_\n]*[^_\s])?)_(?![\w_])""" + // 9 italic
            """|(https?://[^\s<>"]+)""" + // 10 bare URL
            """|([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,})""", // 11 email address
    )
}
