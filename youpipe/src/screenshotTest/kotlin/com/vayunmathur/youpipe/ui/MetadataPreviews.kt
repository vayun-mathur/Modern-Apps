package com.vayunmathur.youpipe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.IconHistory
import com.vayunmathur.library.ui.IconHome
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.IconSubscriptions
import com.vayunmathur.library.ui.RouteTab
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.tabRoute
import com.vayunmathur.youpipe.util.SearchActions
import com.vayunmathur.youpipe.util.SearchUiState
import com.vayunmathur.youpipe.util.SubscriptionFeedActions
import com.vayunmathur.youpipe.util.SubscriptionFeedUiState
import com.vayunmathur.youpipe.util.VideoDetailActions
import com.vayunmathur.youpipe.util.VideoDetailUiState
import com.vayunmathur.youpipe.util.VideoRowState

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/** Desktop-shaped: exercises the Expanded two-pane and adaptive feed grids. */
private const val EXPANDED = "spec:width=1280dp,height=800dp,dpi=240"

/**
 * Store listing images for `:youpipe`, rendered from Compose previews instead of from an
 * instrumented test on a device. See `common-conventions-preview-metadata`.
 *
 * Things to keep in mind when editing:
 *
 *  - Order comes from the function names. The generated PNG filenames embed them, so
 *    `Preview1Home`/`Preview2Video`/... sort into listing order. Renumber when reordering.
 *  - Everything is a literal, including the "1.2M views | 3 days ago" lines. The app formats
 *    those against the current clock; hard-coding them is what keeps a re-render from
 *    producing a different image tomorrow.
 *  - Thumbnails and avatars are left blank on purpose. Layoutlib has no network, so
 *    [VideoRow] draws its placeholder block instead — which is also what the app shows for
 *    the first few hundred milliseconds of a cold feed.
 *  - The player is a plain black block. Layoutlib cannot render an ExoPlayer surface, so
 *    [VideoDetailScreen] takes it as a slot and these previews stand something in.
 *  - Each preview needs @PreviewTest as well as @Preview, and they must be members of a
 *    class. @Preview alone, or a top-level function, renders in Studio but is not collected
 *    as a screenshot test — which surfaces as "did not discover any tests".
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-home", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview1Home() {
        ChromePreview(selectedTab = 0)
    }

    @PreviewTest
    @Preview(name = "2-video", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview2Video() {
        DynamicTheme(darkTheme = true) {
            VideoDetailScreen(
                state = SampleVideo,
                actions = VideoDetailActions.Noop,
                player = { PlayerPlaceholder() },
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-related", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview3Related() {
        DynamicTheme(darkTheme = true) {
            VideoDetailScreen(
                state = SampleVideo,
                actions = VideoDetailActions.Noop,
                initialTab = 1,
                player = { PlayerPlaceholder() },
            )
        }
    }

    @PreviewTest
    @Preview(name = "4-subscriptions", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview4Subscriptions() {
        DynamicTheme(darkTheme = true) {
            SubscriptionFeedContent()
        }
    }

    /** The Subscriptions tab body (Preview4), factored out so [ChromePreview] can reuse it. */
    @Composable
    private fun SubscriptionFeedContent() {
        SubscriptionVideosScreen(
            state = SubscriptionFeedUiState(
                    videos = listOf(
                        VideoRowState(
                            videoID = 11,
                            title = "Rewiring a 1970s Synthesizer, Part 4",
                            author = "Look Mum No Computer",
                            stats = "94K views | 6 hours ago",
                        ),
                        VideoRowState(
                            videoID = 12,
                            title = "The Fastest Way to Sort a Million Integers",
                            author = "Creel",
                            stats = "137K views | 1 day ago",
                            percentWatched = 0.63f,
                        ),
                        VideoRowState(
                            videoID = 13,
                            title = "Restoring a Water-Damaged ThinkPad",
                            author = "Rossmann Repair",
                            stats = "421K views | 2 days ago",
                        ),
                        VideoRowState(
                            videoID = 14,
                            title = "A Field Guide to the Birds of the Cairngorms",
                            author = "Slow Nature",
                            stats = "38K views | 3 days ago",
                        ),
                        VideoRowState(
                            videoID = 15,
                            title = "Building a CPU From Scratch: The Fetch Unit",
                            author = "Ben Eater",
                            stats = "1.1M views | 4 days ago",
                            percentWatched = 0.08f,
                        ),
                    ),
                ),
                actions = SubscriptionFeedActions.Noop,
            )
    }

    @PreviewTest
    @Preview(name = "5-expanded-home", device = EXPANDED, group = "large-tablet", showSystemUi = true)
    @Composable
    fun Preview5ExpandedHome() {
        ChromePreview(selectedTab = 0)
    }

    @PreviewTest
    @Preview(name = "6-expanded-video", device = EXPANDED, group = "large-tablet", showSystemUi = true)
    @Composable
    fun Preview6ExpandedVideo() {
        DynamicTheme(darkTheme = true) {
            VideoDetailScreen(
                state = SampleVideo,
                actions = VideoDetailActions.Noop,
                player = { PlayerPlaceholder() },
            )
        }
    }

    @PreviewTest
    @Preview(name = "7-expanded-subscriptions", device = EXPANDED, group = "large-tablet", showSystemUi = true)
    @Composable
    // CHROME EXEMPT: pushed subscription-videos route renders with no tab chrome on device.
    fun Preview7ExpandedSubscriptions() {
        DynamicTheme(darkTheme = true) {
            SubscriptionVideosScreen(
                state = SubscriptionFeedUiState(
                    videos = listOf(
                        VideoRowState(
                            videoID = 11,
                            title = "Rewiring a 1970s Synthesizer, Part 4",
                            author = "Look Mum No Computer",
                            stats = "94K views | 6 hours ago",
                        ),
                        VideoRowState(
                            videoID = 12,
                            title = "The Fastest Way to Sort a Million Integers",
                            author = "Creel",
                            stats = "137K views | 1 day ago",
                            percentWatched = 0.63f,
                        ),
                        VideoRowState(
                            videoID = 13,
                            title = "Restoring a Water-Damaged ThinkPad",
                            author = "Rossmann Repair",
                            stats = "421K views | 2 days ago",
                        ),
                        VideoRowState(
                            videoID = 14,
                            title = "A Field Guide to the Birds of the Cairngorms",
                            author = "Slow Nature",
                            stats = "38K views | 3 days ago",
                        ),
                    ),
                ),
                actions = SubscriptionFeedActions.Noop,
            )
        }
    }

    /** The Home tab body, factored out so [ChromePreview] can host it behind the real tabs. */
    @Composable
    private fun HomeContent() {
        SearchScreen(
            state = SearchUiState(
                recommendations = listOf(
                    VideoRowState(
                        videoID = 1,
                        title = "The Absurd Engineering Inside a Modern Camera Sensor",
                        author = "Veritasium",
                        stats = "4.1M views | 2 weeks ago",
                        reason = "you watch a lot of optics",
                        percentWatched = 0.42f,
                    ),
                    VideoRowState(
                        videoID = 2,
                        title = "I Rebuilt My Home Server Rack (Again)",
                        author = "Linus Tech Tips",
                        stats = "1.2M views | 3 days ago",
                        reason = "from a channel you follow",
                    ),
                    VideoRowState(
                        videoID = 3,
                        title = "Why Bridges Don't Fall Down",
                        author = "Practical Engineering",
                        stats = "873K views | 1 month ago",
                        reason = "similar to Structural Failures Explained",
                        percentWatched = 1f,
                    ),
                    VideoRowState(
                        videoID = 4,
                        title = "Making Sourdough With No Starter",
                        author = "Adam Ragusea",
                        stats = "612K views | 5 days ago",
                        reason = "new upload",
                    ),
                    VideoRowState(
                        videoID = 5,
                        title = "Every Rust Borrow Checker Error, Explained",
                        author = "No Boilerplate",
                        stats = "298K views | 2 months ago",
                        reason = "you watch a lot of Rust",
                        percentWatched = 0.15f,
                    ),
                ),
            ),
            actions = SearchActions.Noop,
        )
    }

    /**
     * The app's real tab chrome around the home content: the same five
     * route tabs `Navigation` in MainActivity declares, in the same
     * route-backed [TabbedPagerScaffold]. History/Saved/Settings take
     * their ViewModels directly, so those entries show an empty page
     * (never visible — the shot stays on the selected tab).
     */
    @Composable
    private fun ChromePreview(selectedTab: Int) {
        DynamicTheme(darkTheme = true) {
            val tabs = listOf<RouteTab<Route>>(
                RouteTab(Route.Home, "Home", { IconHome() }),
                RouteTab(Route.Subscriptions, "Subscriptions", { IconSubscriptions() }),
                RouteTab(Route.History, "History", { IconHistory() }),
                RouteTab(Route.Saved, "Saved", { IconSave() }),
                RouteTab(Route.Settings, "Settings", { IconSettings() }),
            )
            val backStack = NavBackStack<Route>(arrayOf(tabRoute(selectedTab)))
            TabbedPagerScaffold(backStack = backStack, tabs = tabs) { route ->
                when (route) {
                    Route.Home -> HomeContent()
                    Route.Subscriptions -> SubscriptionFeedContent()
                    // ViewModel-coupled: empty placeholder, never visible.
                    else -> Box(Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** Stands in for the player surface, which Layoutlib cannot render. */
@Composable
private fun PlayerPlaceholder() {
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black))
}

private val SampleVideo = VideoDetailUiState(
    loaded = true,
    title = "The Absurd Engineering Inside a Modern Camera Sensor",
    byline = "Veritasium | 4.1M views | 2 weeks ago",
    description = "Every photo you take passes through a grid of a few million light wells, " +
        "each one only a couple of microns across. This video takes one apart.",
    comments = listOf(
        Comment(
            text = "The part about microlenses at 8:12 finally made this click for me. Thank you.",
            author = "@marta_builds",
            likes = 2841,
            dislikes = 0,
        ),
        Comment(
            text = "I work in sensor fab and this is the first video I've seen get the well " +
                "capacity explanation right.",
            author = "@quietfab",
            likes = 1930,
            dislikes = 0,
        ),
        Comment(
            text = "Watched this three times. Sending it to my photography class.",
            author = "@ansel_at_home",
            likes = 604,
            dislikes = 0,
        ),
        Comment(
            text = "Would love a follow-up on global vs rolling shutter.",
            author = "@ph_otto",
            likes = 288,
            dislikes = 0,
        ),
    ),
    relatedVideos = listOf(
        VideoRowState(
            videoID = 21,
            title = "How Rolling Shutter Bends Reality",
            author = "Smarter Every Day",
            stats = "9.8M views | 3 years ago",
        ),
        VideoRowState(
            videoID = 22,
            title = "Why Film Still Looks Better (Sometimes)",
            author = "Corridor Crew",
            stats = "2.4M views | 8 months ago",
        ),
        VideoRowState(
            videoID = 23,
            title = "Inside a Photolithography Machine",
            author = "Asianometry",
            stats = "1.7M views | 1 year ago",
            percentWatched = 0.31f,
        ),
        VideoRowState(
            videoID = 24,
            title = "The Colour Science of Bayer Filters",
            author = "Technology Connections",
            stats = "986K views | 2 years ago",
        ),
    ),
)
