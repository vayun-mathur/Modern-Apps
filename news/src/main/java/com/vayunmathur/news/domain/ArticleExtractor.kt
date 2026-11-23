package com.vayunmathur.news.domain

/**
 * Readability-style on-device extraction. Scores candidate containers by
 * paragraph text density (Readability's core idea), then walks the winner
 * in document order keeping paragraphs, headings, list items, quotes and
 * inline images — dropping navigation, ads, related-links and share chrome
 * by tag, class/id hint and link density. Pure: same input, same output.
 */
object ArticleExtractor {
    private val chromeTags = setOf(
        "nav", "header", "footer", "aside", "form", "button", "select",
        "textarea", "input", "figure", "figcaption",
    )
    private val chromeHints = listOf(
        "nav", "menu", "sidebar", "footer", "header", "advert", "promo",
        "related", "recommend", "share", "social", "newsletter", "cookie",
        "consent", "paywall", "subscribe", "signup", "sign-up", "breadcrumb",
        "pagination", "comment", "toolbar", "masthead", "outbrain", "taboola",
    )
    private val dropPhrases = listOf(
        "read more", "read next", "sign up", "subscribe to", "already a subscriber",
        "share this", "follow us", "advertisement", "click here",
    )

    fun extract(html: String, url: String): ExtractedArticle {
        val doc = HtmlDom.parse(html)
        val title = metaContent(doc, "og:title")
            ?: doc.first("h1")?.textContent()?.takeIf { it.isNotBlank() }
            ?: doc.first("title")?.textContent()?.substringBefore(" | ")?.substringBefore(" - ")
            ?: ""
        val byline = metaContent(doc, "author")
            ?: metaContent(doc, "article:author")
            ?: doc.findAll("a").firstOrNull { it.attr("rel") == "author" }?.textContent()
        val published = metaContent(doc, "article:published_time")
            ?: doc.findAll("time").firstOrNull()?.attr("datetime")
        val hero = metaContent(doc, "og:image")
        val main = pickMain(doc)
        val blocks = mutableListOf<ArticleBlock>()
        val seen = HashSet<String>()
        if (main != null) collectBlocks(main, url, blocks, seen)
        if (hero != null && blocks.none { it is ArticleBlock.Image && it.url == hero }) {
            blocks.add(0, ArticleBlock.Image(hero))
        }
        return ExtractedArticle(
            url = url, title = title.trim(), byline = byline?.trim()?.takeIf { it.isNotEmpty() },
            publishedAtMillis = DateParsing.parseMillis(published),
            heroImageUrl = hero, blocks = blocks,
        )
    }

    private fun metaContent(doc: HtmlElement, property: String): String? =
        doc.findAll("meta").firstOrNull {
            it.attr("property") == property || it.attr("name") == property
        }?.attr("content")?.takeIf { it.isNotBlank() }

    /** The element with the highest paragraph-density score wins, like Readability. */
    private fun pickMain(doc: HtmlElement): HtmlElement? {
        val candidates = mutableListOf<HtmlElement>()
        candidates += doc.findAll("article")
        candidates += doc.findAll("main")
        candidates += doc.findAll("div")
        candidates += doc.findAll("section")
        var best: HtmlElement? = null
        var bestScore = -1.0
        for (candidate in candidates) {
            if (isChrome(candidate)) continue
            var score = 0.0
            for (p in candidate.findAll("p")) {
                val len = p.textContent().length
                if (len >= 40) score += len + if (p.textContent().contains(',')) 20.0 else 0.0
            }
            // Link density is the classic chrome signal: penalise link-heavy containers.
            val totalText = candidate.textContent().length.coerceAtLeast(1)
            val linkText = candidate.findAll("a").sumOf { it.textContent().length }
            score *= (1.0 - linkText.toDouble() / totalText).coerceIn(0.0, 1.0)
            if (candidate.tag == "article" || candidate.tag == "main") score *= 1.5
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return best ?: doc.first("body") ?: doc
    }

    private fun collectBlocks(
        element: HtmlElement,
        baseUrl: String,
        out: MutableList<ArticleBlock>,
        seen: MutableSet<String>,
    ) {
        for (child in element.children) {
            if (child !is HtmlElement || isChrome(child)) continue
            when (child.tag) {
                "p", "blockquote", "li" -> {
                    val text = child.textContent()
                    if (isKeepableParagraph(text) && seen.add(text)) {
                        out += ArticleBlock.Paragraph(text)
                    }
                }
                "h2", "h3", "h4" -> {
                    val text = child.textContent()
                    if (text.length in 3..200 && seen.add(text)) out += ArticleBlock.Heading(text)
                }
                "img" -> imageOf(child, baseUrl)?.let { out += it }
                else -> collectBlocks(child, baseUrl, out, seen)
            }
        }
    }

    private fun imageOf(img: HtmlElement, baseUrl: String): ArticleBlock.Image? {
        val raw = img.attr("src") ?: img.attr("data-src") ?: return null
        if (raw.startsWith("data:") || raw.contains("pixel") || raw.contains("tracker")) return null
        val resolved = resolveUrl(baseUrl, raw) ?: return null
        return ArticleBlock.Image(resolved, img.attr("alt")?.takeIf { it.isNotBlank() })
    }

    private fun isKeepableParagraph(text: String): Boolean {
        if (text.length < 40) return false
        val lower = text.lowercase()
        if (dropPhrases.any { lower.startsWith(it) }) return false
        return true
    }

    private fun isChrome(element: HtmlElement): Boolean {
        if (element.tag in chromeTags) return true
        val hint = ((element.attr("class") ?: "") + " " + (element.attr("id") ?: "")).lowercase()
        if (hint.isNotBlank() && chromeHints.any { hint.contains(it) }) return true
        // A block that is mostly links is navigation, not prose.
        val textLen = element.textContent().length
        if (textLen > 0 && (element.tag == "ul" || element.tag == "div")) {
            val linkLen = element.findAll("a").sumOf { it.textContent().length }
            if (linkLen.toDouble() / textLen > 0.7 && textLen < 600) return true
        }
        return false
    }

    internal fun resolveUrl(base: String, raw: String): String? {
        val value = raw.trim()
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        val schemeEnd = base.indexOf("://")
        if (schemeEnd < 0) return null
        val scheme = base.substring(0, schemeEnd)
        val host = base.substring(schemeEnd + 3).substringBefore('/')
        return when {
            value.startsWith("//") -> "$scheme:$value"
            value.startsWith("/") -> "$scheme://$host$value"
            value.startsWith("./") -> "$scheme://$host/${value.drop(2)}"
            else -> {
                val dir = base.substringBefore('?').substringBeforeLast('/', "")
                if (dir.isEmpty()) null else "$dir/$value"
            }
        }
    }
}
