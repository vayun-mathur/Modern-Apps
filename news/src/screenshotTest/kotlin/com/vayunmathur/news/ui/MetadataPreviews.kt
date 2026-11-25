package com.vayunmathur.news.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.news.data.CachedArticle
import com.vayunmathur.news.domain.ArticleBlock
import com.vayunmathur.news.domain.ExtractedArticle
import com.vayunmathur.news.domain.FeedArticle
import com.vayunmathur.news.domain.FeedSort
import com.vayunmathur.news.domain.Story

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/** Desktop-wide (1280dp >= 840dp), so the feed's adaptive grid and capped reader measure render. */
private const val EXPANDED = "spec:width=1280dp,height=800dp,dpi=240"

/**
 * Store listing images for `:news`, rendered from Compose previews instead of from an
 * instrumented test on a device.
 *
 * `./gradlew :news:metadata` renders these and copies the PNGs into per-bucket
 * `metadata_data/photos/news/<bucket>/` dirs (`phone/` and `large-tablet/`), where the
 * release build picks them up. News has no wear companion, so there is no `wear/` bucket.
 *
 * The rules that keep these honest (same as every other module's MetadataPreviews):
 *  - Order comes from the function names (`Preview1…`, `Preview2…`), per bucket.
 *  - Each preview needs `@PreviewTest` as well as `@Preview`, and a `group` naming the
 *    form-factor bucket.
 *  - Everything is a literal fixture below — no ViewModel, no database, no network —
 *    which is what makes the renders reproducible. Timestamps are 0 on purpose:
 *    `relativeTime` renders "" for them, so the images never drift with the date.
 *  - Previews must be members of a class, not top-level functions, or the screenshot
 *    engine silently discovers no tests.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-feed", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview1Feed() {
        PreviewTheme { FeedFixture() }
    }

    @PreviewTest
    @Preview(name = "2-reader", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview2Reader() {
        PreviewTheme { ReaderFixture() }
    }

    @PreviewTest
    @Preview(name = "3-saved", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview3Saved() {
        PreviewTheme { SavedFixture() }
    }

    @PreviewTest
    @Preview(name = "4-feed-expanded", device = EXPANDED, group = "large-tablet", showSystemUi = true)
    @Composable
    fun Preview4FeedExpanded() {
        PreviewTheme { FeedFixture() }
    }

    @PreviewTest
    @Preview(name = "5-reader-expanded", device = EXPANDED, group = "large-tablet", showSystemUi = true)
    @Composable
    fun Preview5ReaderExpanded() {
        PreviewTheme { ReaderFixture() }
    }
}

@Composable
private fun PreviewTheme(content: @Composable () -> Unit) {
    DynamicTheme(darkTheme = true) { content() }
}

private fun fixtureStory(id: String, sourceId: String, title: String, oneLiner: String? = null): Story {
    val article = FeedArticle(
        url = "https://example.com/$id",
        sourceId = sourceId,
        title = title,
        publishedAtMillis = 0L,
        serverStoryId = id,
        storyOneLiner = oneLiner,
    )
    return Story(id = "srv:$id", representative = article, articles = listOf(article))
}

private val fixtureStories = listOf(
    fixtureStory("s1", "bbc", "City approves the new riverfront plan after a long night of debate", "Councillors backed the scheme 8-3, with construction due to start in spring."),
    fixtureStory("s2", "techcrunch", "A small chip startup says its new accelerator sips power", null),
    fixtureStory("s3", "npr", "Morning markets: what to watch before the opening bell", null),
    fixtureStory("s4", "ars", "Deep-sea survey maps a canyon no one had charted before", "The remotely operated vehicle spent nine days on the survey."),
    fixtureStory("s5", "aljazeera", "Farmers adapt as the rains arrive later each year", null),
)

private val fixtureCached: Map<String, CachedArticle> = fixtureStories.associate { story ->
    val a = story.representative
    a.url to CachedArticle(url = a.url, sourceId = a.sourceId, title = a.title, publishedAtMillis = 0L)
}

@Composable
private fun FeedFixture() {
    FeedContent(
        stories = fixtureStories,
        searchStories = emptyList(),
        cachedByUrl = fixtureCached,
        isRefreshing = false,
        refreshFailed = false,
        categoryFilter = null,
        feedSort = FeedSort.TOP,
        localLocation = null,
        searchQuery = "",
        onSearchQueryChange = {},
        onRefresh = {},
        onFeedSortChange = {},
        onCategoryFilterChange = {},
        onToggleBookmark = {},
        onOpenSaved = {},
        onOpenStory = {},
        onOpenSettings = {},
    )
}

@Composable
private fun ReaderFixture() {
    ReaderContent(
        body = ExtractedArticle(
            url = "https://example.com/s1",
            title = "City approves the new riverfront plan after a long night of debate",
            byline = "By A. Reporter",
            blocks = listOf(
                ArticleBlock.Paragraph("The council voted late on Tuesday to approve the riverfront plan, ending months of argument about flood defences, housing and the old ferry terminal."),
                ArticleBlock.Paragraph("Supporters said the scheme would open two miles of bank to the public for the first time in a generation. Critics asked who would pay for the upkeep once the initial grant runs out."),
                ArticleBlock.Heading("What happens next"),
                ArticleBlock.Paragraph("Construction is due to start in spring, beginning with the flood wall. The first section of path is expected to open the following year."),
            ),
        ),
        error = false,
        bookmarked = true,
        textSp = 18,
        serif = true,
        onToggleBookmark = {},
        onTextSp = {},
        onSerif = {},
        onBack = {},
    )
}

@Composable
private fun SavedFixture() {
    SavedContent(
        saved = fixtureStories.map { story ->
            val a = story.representative
            CachedArticle(url = a.url, sourceId = a.sourceId, title = a.title, publishedAtMillis = 0L, bookmarked = true)
        },
        onOpenArticle = {},
        onToggleBookmark = {},
        onBack = {},
    )
}
