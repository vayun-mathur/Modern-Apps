package com.vayunmathur.openassistant.domain

/**
 * On-device readable-text extraction for web_read (pure, unit-tested).
 *
 * Minimal tolerant extractor mirroring the old server reader's shape:
 * title (title tag, then og:title, then first h1) plus block text from
 * paragraphs/headings/list items/pre/blockquote under <main>, <article>
 * or <body>, with script/style/nav and other chrome stripped. It is a
 * deliberate small duplicate of the readability idea (news has its own
 * richer ArticleExtractor, module-private to news, so it is not reused).
 */
object WebPageExtractor {

    /** Extracted page: [title] may be blank, [text] is the readable body. */
    data class ExtractedPage(val title: String, val text: String)

    private val stripTags = listOf(
        "script", "style", "nav", "header", "footer", "aside", "form",
        "noscript", "iframe", "svg", "button", "select", "textarea",
    )

    fun extract(html: String): ExtractedPage {
        val withoutComments = html.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), " ")
        val stripped = stripChromeBlocks(withoutComments)
        val title = extractTitle(stripped)
        val root = rootInner(stripped)
        val blocks = blockTexts(root)
        val text = if (blocks.isNotEmpty()) {
            blocks.joinToString("\n\n")
        } else {
            collapseWs(stripTags(root))
        }
        return ExtractedPage(title, text)
    }

    /** Plain-text pages: body is the text itself, title is its first line. */
    fun extractPlainText(body: String): ExtractedPage {
        val title = body.lineSequence().firstOrNull()?.trim()?.take(200).orEmpty()
        return ExtractedPage(title, body)
    }

    private fun stripChromeBlocks(html: String): String {
        var out = html
        for (tag in stripTags) {
            out = out.replace(
                Regex("<$tag\\b[^>]*>.*?</$tag\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
                " ",
            )
            // Unclosed chrome opener: drop from the tag to end of input.
            out = out.replace(
                Regex("<$tag\\b[^>]*>.*\\z", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
                " ",
            )
        }
        return out
    }

    private fun extractTitle(html: String): String {
        val titleTag = Regex("<title\\b[^>]*>(.*?)</title\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(1)?.let { collapseWs(stripTags(it)) }.orEmpty()
        if (titleTag.isNotBlank()) return titleTag
        val og = Regex(
            "<meta\\b[^>]*property\\s*=\\s*\"og:title\"[^>]*>",
            RegexOption.IGNORE_CASE,
        ).find(html)?.value?.let { attrValue(it, "content") }.orEmpty()
        if (og.isNotBlank()) return collapseWs(og)
        val h1 = Regex("<h1\\b[^>]*>(.*?)</h1\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(1)?.let { collapseWs(stripTags(it)) }.orEmpty()
        return h1
    }

    private fun attrValue(tag: String, name: String): String? {
        val m = Regex("$name\\s*=\\s*\"([^\"]*)\"|$name\\s*=\\s*'([^']*)'", RegexOption.IGNORE_CASE).find(tag)
            ?: return null
        return m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }
    }

    /** Inner HTML of <main>, else <article>, else <body>, else the whole input. */
    private fun rootInner(html: String): String {
        for (tag in listOf("main", "article", "body")) {
            val m = Regex(
                "<$tag\\b[^>]*>(.*)</$tag\\s*>",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            ).find(html)
            if (m != null) return m.groupValues[1]
        }
        return html
    }

    private fun blockTexts(root: String): List<String> {
        val blockRe = Regex(
            "<(p|h1|h2|h3|h4|h5|h6|li|pre|blockquote)\\b[^>]*>(.*?)</\\1\\s*>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        return blockRe.findAll(root).mapNotNull { m ->
            collapseWs(stripTags(m.groupValues[2])).takeIf { it.isNotEmpty() }
        }.toList()
    }

    fun stripTags(fragment: String): String =
        decodeEntities(fragment.replace(Regex("<[^>]*>"), " "))

    fun collapseWs(text: String): String = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    fun decodeEntities(text: String): String {
        var out = text
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
        out = Regex("&#x([0-9a-fA-F]+);").replace(out) { m ->
            m.groupValues[1].toIntOrNull(16)?.takeIf { it in 0..0x10FFFF }?.let { Character.toChars(it).concatToString() } ?: m.value
        }
        out = Regex("&#(\\d+);").replace(out) { m ->
            m.groupValues[1].toIntOrNull()?.takeIf { it in 0..0x10FFFF }?.let { Character.toChars(it).concatToString() } ?: m.value
        }
        // &amp; last so &amp;lt; stays a literal &lt; text, not a tag char.
        return out.replace("&amp;", "&")
    }
}

/** Why a tool-supplied URL was refused on device, as a stable error code. */
data class UrlRefusal(val code: String, val message: String)

/**
 * Proportionate on-device URL sanity for web_read: http/https only, a real
 * host, and no localhost / .local / private-or-reserved IP-literal targets.
 * (Unlike the old server fetcher there is no DNS pinning on device: the
 * user's own assistant fetches a URL the model chose, so scheme + literal
 * checks plus size/timeout caps in the fetcher are the proportionate bar.)
 */
/** Outcome of [WebReadUrlPolicy.validate]: the usable URL, or why it was refused. */
sealed interface UrlCheck {
    data class Ok(val url: String) : UrlCheck
    data class Refused(val refusal: UrlRefusal) : UrlCheck
}

object WebReadUrlPolicy {

    fun validate(raw: String): UrlCheck {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return UrlCheck.Refused(UrlRefusal("invalid_url", "empty url"))
        val uri = try {
            java.net.URI(trimmed)
        } catch (expected: Exception) {
            return UrlCheck.Refused(UrlRefusal("invalid_url", "unparseable url"))
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return UrlCheck.Refused(UrlRefusal("unsupported_scheme", "unsupported scheme: ${scheme ?: "none"}"))
        }
        val host = uri.host?.lowercase()?.trimEnd('.')
            ?: return UrlCheck.Refused(UrlRefusal("invalid_url", "url has no host"))
        if (host == "localhost" || host.endsWith(".local")) {
            return UrlCheck.Refused(UrlRefusal("blocked_url", "internal hostnames are not allowed"))
        }
        val literal = host.removePrefix("[").removeSuffix("]")
        if (isIpv4Literal(literal) && !isPublicIpv4(literal)) {
            return UrlCheck.Refused(UrlRefusal("blocked_url", "host is a non-public address"))
        }
        if (literal.contains(':') && !isPublicIpv6(literal)) {
            return UrlCheck.Refused(UrlRefusal("blocked_url", "host is a non-public address"))
        }
        return UrlCheck.Ok(trimmed)
    }

    /** Refusal for [url], or null when it passes — for the fetcher's final-URL recheck. */
    fun refusalFor(url: String): UrlRefusal? = when (val r = validate(url)) {
        is UrlCheck.Ok -> null
        is UrlCheck.Refused -> r.refusal
    }

    private fun isIpv4Literal(host: String): Boolean =
        host.split('.').let { it.size == 4 && it.all { p -> p.isNotEmpty() && p.all(Char::isDigit) } }

    private fun isPublicIpv4(host: String): Boolean {
        val o = host.split('.').map { it.toIntOrNull() ?: return false }
        if (o.any { it !in 0..255 }) return false
        if (o[0] == 0 || o[0] == 10 || o[0] == 127) return false
        if (o[0] == 172 && o[1] in 16..31) return false
        if (o[0] == 192 && o[1] == 168) return false
        if (o[0] == 169 && o[1] == 254) return false
        if (o[0] == 100 && o[1] in 64..127) return false
        if (o[0] == 192 && o[1] == 0 && o[2] == 2) return false
        if (o[0] == 198 && o[1] == 51 && o[2] == 100) return false
        if (o[0] == 203 && o[1] == 0 && o[2] == 113) return false
        if (o[0] == 198 && (o[1] == 18 || o[1] == 19)) return false
        if (o[0] >= 224) return false
        return true
    }

    private fun isPublicIpv6(host: String): Boolean {
        val h = host.lowercase()
        if (h == "::" || h == "::1") return false
        if (h.startsWith("fe8") || h.startsWith("fe9") || h.startsWith("fea") || h.startsWith("feb")) return false
        if (h.startsWith("fc") || h.startsWith("fd")) return false
        if (h.startsWith("2001:db8")) return false
        if (h.startsWith("ff")) return false
        // IPv4-mapped: judge by the embedded v4 tail.
        val tail = h.substringAfterLast(':')
        if (isIpv4Literal(tail)) return isPublicIpv4(tail)
        return true
    }
}
