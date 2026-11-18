package com.vayunmathur.web.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire contract for GET https://api.maproj.org/api/search
 * (`location_share_server/src/handlers/search.rs`).
 *
 * Single-shape envelope: only the requested `type` field is populated.
 * Field names must match the Rust serde structs exactly.
 */
@Serializable
data class WebSearchResponse(
    @SerialName("type") val kind: String = "text",
    val query: String = "",
    val text: List<WebSearchTextItem>? = null,
    val images: List<WebSearchImageItem>? = null,
    val news: List<WebSearchNewsItem>? = null,
    val error: String? = null,
)

/** Immutable value describing web search text item. */
@Serializable
data class WebSearchTextItem(
    val title: String = "",
    val url: String = "",
    val snippet: String = "",
)

/** Immutable value describing web search image item. */
@Serializable
data class WebSearchImageItem(
    val title: String = "",
    @SerialName("image_url") val imageUrl: String = "",
    val thumbnail: String = "",
    @SerialName("source_url") val sourceUrl: String = "",
)

/** Immutable value describing web search news item. */
@Serializable
data class WebSearchNewsItem(
    val title: String = "",
    val url: String = "",
    val snippet: String = "",
    val date: Long = 0L,
    val source: String = "",
)

/** Result category, matching the server `type` query param. */
enum class WebSearchKind(val param: String) {
    TEXT("text"),
    IMAGES("images"),
    NEWS("news"),
}
