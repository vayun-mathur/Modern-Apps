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
    /** BCP-47 language (server `language`, or the source catalog default). Not persisted. */
    val language: String? = null,
    /**
     * Server story assignment (`story_id` in news.json): same value for
     * every article the server grouped into one story. This is the
     * *only* grouping key — the client makes no grouping decisions
     * (see [StoryGrouping]). Null/blank (always the case for the
     * fallback sitemap/RSS crawl) means the article is its own
     * singleton story. Persisted in Room (CachedArticle.storyId) so
     * it survives caching.
     */
    val serverStoryId: String? = null,
    /**
     * Server-generated one-line story summary (`story_one_liner`).
     * The ONLY server-generated text the client may display (labelled
     * as a summary). Extracted article text is never served or shown;
     * the reader still fetches bodies on-device. Persisted like
     * [serverStoryId].
     */
    val storyOneLiner: String? = null,
    /** Server canonical story headline (`story_title`), if it differs. */
    val serverStoryTitle: String? = null,
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
