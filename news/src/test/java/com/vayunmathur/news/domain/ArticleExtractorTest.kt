package com.vayunmathur.news.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArticleExtractorTest {
    private val html = """
        <html><head><title>Site | Big Story</title>
        <meta property="og:title" content="Big Story"/>
        <meta name="author" content="Jane Doe"/>
        <meta property="article:published_time" content="2026-11-23T10:00:00Z"/>
        <meta property="og:image" content="https://example.com/hero.jpg"/></head>
        <body>
        <nav><p>Menu link one and more navigation text that should never appear in output.</p></nav>
        <header><p>Site header promo text that is definitely chrome and not article prose.</p></header>
        <article>
          <h1>Big Story</h1>
          <p>The first paragraph of the article carries the lede, with enough words to be real prose.</p>
          <p>A second paragraph follows with more detail, commas, and a complete thought for readers.</p>
          <h2>A section heading</h2>
          <p>The closing paragraph wraps the story up with a final observation about what happens next.</p>
          <div class="related"><p>Read more stories like this one on our related links rail today.</p></div>
        </article>
        <footer><p>Footer legal text and subscription prompts that must be stripped from reading.</p></footer>
        </body></html>
    """.trimIndent()

    @Test
    fun extractsTitleBylineAndParagraphsInOrder() {
        val article = ArticleExtractor.extract(html, "https://example.com/story")
        assertEquals("Big Story", article.title)
        assertEquals("Jane Doe", article.byline)
        assertEquals("https://example.com/hero.jpg", article.heroImageUrl)
        val paragraphs = article.blocks.filterIsInstance<ArticleBlock.Paragraph>().map { it.text }
        assertEquals(3, paragraphs.size)
        assertTrue(paragraphs[0].startsWith("The first paragraph"))
        assertTrue(paragraphs[2].startsWith("The closing paragraph"))
        assertTrue(article.blocks.any { it is ArticleBlock.Heading && it.text == "A section heading" })
    }

    @Test
    fun stripsChromeAndRelatedLinks() {
        val article = ArticleExtractor.extract(html, "https://example.com/story")
        val allText = article.blocks.filterIsInstance<ArticleBlock.Paragraph>().joinToString(" ") { it.text }
        assertFalse(allText.contains("navigation"))
        assertFalse(allText.contains("Footer legal"))
        assertFalse(allText.contains("related links rail"))
        assertFalse(allText.contains("header promo"))
    }

    @Test
    fun emptyHtmlYieldsEmptyBlocks() {
        val article = ArticleExtractor.extract("<html></html>", "https://example.com/x")
        assertTrue(article.blocks.isEmpty())
    }
}
