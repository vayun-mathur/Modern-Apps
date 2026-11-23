package com.vayunmathur.news.domain

import kotlinx.serialization.Serializable

/** One article stub from a sitemap or RSS feed, before its body is fetched. */
data class FeedArticle(
    val url: String,
    val sourceId: String,
    val title: String,
    val publishedAtMillis: Long,
    val imageUrl: String? = null,
    val keywords: List<String> = emptyList(),
    /** Server-feed only: source meters this article. Not persisted (catalog badges cover it). */
    val metered: Boolean = false,
)

/** One block of an extracted article body, in reading order. */
@Serializable
sealed interface ArticleBlock {
    @Serializable
    data class Paragraph(val text: String) : ArticleBlock

    @Serializable
    data class Heading(val text: String) : ArticleBlock

    @Serializable
    data class Image(val url: String, val caption: String? = null) : ArticleBlock
}

/** The clean, extracted article the reader renders. */
@Serializable
data class ExtractedArticle(
    val url: String,
    val title: String,
    val byline: String? = null,
    val publishedAtMillis: Long? = null,
    val heroImageUrl: String? = null,
    val blocks: List<ArticleBlock> = emptyList(),
) {
    /** Plain-text body length; used to judge extraction quality / truncation. */
    val bodyChars: Int get() = blocks.sumOf {
        when (it) {
            is ArticleBlock.Paragraph -> it.text.length
            is ArticleBlock.Heading -> it.text.length
            is ArticleBlock.Image -> 0
        }
    }
}
