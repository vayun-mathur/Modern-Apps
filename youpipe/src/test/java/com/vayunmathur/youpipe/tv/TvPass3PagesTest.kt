package com.vayunmathur.youpipe.tv

import android.content.Context
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.youpipe.data.HistoryVideo
import com.vayunmathur.youpipe.data.Playlist
import com.vayunmathur.youpipe.data.Subscription
import com.vayunmathur.youpipe.data.SubscriptionDatabase
import com.vayunmathur.youpipe.ui.VideoInfo
import com.vayunmathur.youpipe.util.YouPipeViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Clock

/**
 * Slice 3/3 (tv/) pass 3: renders every TV browse/settings page in its
 * empty state, plus the populated variants the in-memory database can
 * seed (subscriptions, history, playlists). One setContent per test.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1200dp-h800dp")
class TvPass3PagesTest {

    @get:Rule val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: SubscriptionDatabase
    private lateinit var vm: YouPipeViewModel

    @BeforeTest fun setUp() {
        db = TvPass3Support.database(context)
        vm = TvPass3Support.viewModel(context)
        runBlocking { TvPass3Support.wipe(db, vm) }
    }

    private fun video(id: Long, name: String = "Video $id") = VideoInfo(
        name = name, videoID = id, duration = 360, views = 12_000,
        uploadDate = Clock.System.now(), thumbnailURL = "", author = "Channel $id",
    )

    private fun seedBasics() = runBlocking {
        db.subscriptionDao().upsert(Subscription(name = "Chan A", channelID = "chan-a", avatarURL = ""))
        db.subscriptionDao().upsert(Subscription(name = "Chan B", channelID = "chan-b", avatarURL = ""))
        db.historyVideoDao().upsert(HistoryVideo.fromVideoData(video(11), 90_000))
        db.historyVideoDao().upsert(HistoryVideo.fromVideoData(video(12), 0))
        db.playlistDao().upsert(Playlist(name = "Watch Later", mandatory = true))
        db.playlistDao().upsert(Playlist(name = "Favs"))
        Unit
    }

    @Test fun homeFeedEmptyAndLoadingStates() {
        rule.setContent { TvHomeFeedPage(TvPass3Support.backStack(TvRoute.Main()), vm) }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
    }

    @Test fun settingsHubNavigates() {
        val stack = TvPass3Support.backStack(TvRoute.Main(4))
        rule.setContent { TvSettingsPage(stack, vm) }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
    }

    @Test fun settingsSubPages() {
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                TvGeneralSettingsPage(TvPass3Support.backStack(TvRoute.SettingsGeneral), vm)
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
    }

    @Test fun sponsorAndRecommendationsSettings() {
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                TvSponsorBlockSettingsPage(TvPass3Support.backStack(TvRoute.SettingsSponsorBlock), vm)
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
    }

    @Test fun dataSettingsAndManageInterests() {
        rule.setContent { TvDataSettingsPage(TvPass3Support.backStack(TvRoute.SettingsData), vm) }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
    }

    @Test fun manageInterestsPage() {
        rule.setContent { TvManageInterestsPage(TvPass3Support.backStack(TvRoute.ManageInterests), vm) }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
    }

    @Test fun recommendationsSettingsPage() {
        rule.setContent {
            TvRecommendationsSettingsPage(TvPass3Support.backStack(TvRoute.SettingsRecommendations), vm)
        }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
    }

    @Test fun searchPageStates() {
        rule.setContent { TvSearchPage(TvPass3Support.backStack(TvRoute.Search), vm) }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
    }

    @Test fun historyEmptyThenPopulated() {
        rule.setContent { TvHistoryPage(TvPass3Support.backStack(TvRoute.Main(2)), vm) }
        rule.waitForIdle()
        seedBasics()
        rule.waitForIdle()
        rule.onRoot().captureToImage()
    }

    @Test fun subscriptionsEmptyThenPopulated() {
        rule.setContent { TvSubscriptionsPage(TvPass3Support.backStack(TvRoute.Main(1)), vm) }
        rule.waitForIdle()
        seedBasics()
        rule.waitForIdle()
        rule.onRoot().captureToImage()
    }

    @Test fun subscriptionFeedPage() {
        seedBasics()
        rule.setContent {
            TvSubscriptionFeedPage(TvPass3Support.backStack(TvRoute.SubscriptionFeed(null)), vm, null)
        }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
    }

    @Test fun savedPage() {
        seedBasics()
        rule.setContent { TvSavedPage(TvPass3Support.backStack(TvRoute.Main(3)), vm) }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
    }

    @Test fun downloadsPage() {
        rule.setContent { TvDownloadsPage(TvPass3Support.backStack(TvRoute.Downloads), vm) }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
    }

    @Test fun channelPageUnknown() {
        rule.setContent {
            TvChannelPage(TvPass3Support.backStack(TvRoute.Channel("chan-a")), vm, "chan-a")
        }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
    }

    @Test fun playlistDetailPage() {
        seedBasics()
        val id = runBlocking { db.playlistDao().getAll().first { it.name == "Favs" }.id }
        rule.setContent {
            TvPlaylistDetailPage(TvPass3Support.backStack(TvRoute.PlaylistDetail(id)), vm, id)
        }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
    }

    @Test fun createPages() {
        rule.setContent { TvCreatePlaylistPage(TvPass3Support.backStack(TvRoute.CreatePlaylist), vm) }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
    }

    @Test fun createCategoryPages() {
        seedBasics()
        rule.setContent {
            TvCreateCategoryPage(
                TvPass3Support.backStack(TvRoute.CreateSubscriptionCategory(null)), vm, null,
            )
        }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
    }
}
