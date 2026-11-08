package com.vayunmathur.youpipe

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.util.OfflineAware
import com.vayunmathur.library.ui.IconHistory
import com.vayunmathur.library.ui.IconHome
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconSubscriptions
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.PagerTab
import com.vayunmathur.library.ui.TabStyle
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.DialogPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.MorphPage
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.youpipe.data.SubscriptionRepository
import com.vayunmathur.youpipe.ui.ChannelPage
import com.vayunmathur.youpipe.ui.DownloadedVideosPage
import com.vayunmathur.youpipe.ui.HistoryPage
import com.vayunmathur.youpipe.ui.PlaylistDetailPage
import com.vayunmathur.youpipe.ui.SavedPage
import com.vayunmathur.youpipe.util.PlaybackService
import com.vayunmathur.youpipe.util.YouPipeViewModel
import com.vayunmathur.youpipe.util.YouPipeViewModelFactory
import com.vayunmathur.youpipe.ui.SearchPage
import com.vayunmathur.youpipe.ui.SettingsPage
import com.vayunmathur.youpipe.ui.GeneralSettingsPage
import com.vayunmathur.youpipe.ui.SponsorBlockSettingsPage
import com.vayunmathur.youpipe.ui.RecommendationsSettingsPage
import com.vayunmathur.youpipe.ui.DataSettingsPage
import com.vayunmathur.youpipe.ui.ManageInterestsPage
import com.vayunmathur.youpipe.ui.SubscriptionVideosPage
import com.vayunmathur.youpipe.ui.SubscriptionsPage
import com.vayunmathur.youpipe.ui.VideoPage
import com.vayunmathur.youpipe.ui.dialogs.CreateSubscriptionCategory
import com.vayunmathur.youpipe.ui.dialogs.CreatePlaylist
import com.vayunmathur.youpipe.ui.dialogs.AddToPlaylist
import com.vayunmathur.youpipe.ui.dialogs.AddToWatchLater
import kotlinx.serialization.Serializable
import com.vayunmathur.youpipe.util.parseSharedVideoId

internal fun Context.findActivity(): ComponentActivity {
    var context = this
    while (context is ContextWrapper) {
        if (context is ComponentActivity) return context
        context = context.baseContext
    }
    throw IllegalStateException("Picture in picture should be called in the context of an Activity")
}

@Composable
fun rememberIsInPipMode(): Boolean {
    val activity = LocalContext.current.findActivity()
    var pipMode by remember { mutableStateOf(activity.isInPictureInPictureMode) }
    DisposableEffect(activity) {
        val observer = Consumer<PictureInPictureModeChangedInfo> { info ->
            pipMode = info.isInPictureInPictureMode
        }
        activity.addOnPictureInPictureModeChangedListener(
            observer
        )
        onDispose { activity.removeOnPictureInPictureModeChangedListener(observer) }
    }
    return pipMode
}



class MainActivity : MaActivity() {
    private val youPipeViewModel: YouPipeViewModel by viewModels {
        YouPipeViewModelFactory(
            application,
            SubscriptionRepository.get(application),
        )
    }
    private lateinit var initialBackStack: List<Route>

    override fun onCreateBeforeContent() {
        // Touch the VM so its init { setupHourlyTask(...) } runs at startup
        // (replaces the previous LaunchedEffect(Unit) in setContent).
        youPipeViewModel
        initialBackStack = resolveInitialBackStack(intent)
    }

    @Composable
    override fun Content() {
        OfflineAware {
            Navigation(initialBackStack, youPipeViewModel)
        }
    }

    /**
     * Decide the initial backstack at launch. A shared YouTube URL (ACTION_SEND) routes to the
     * video, optionally seeding an add-to-Watch-later or add-to-playlist dialog depending on which
     * share target was tapped. Otherwise a `watch` deep-link wins; failing that, fall back to the
     * user's chosen default page (see [DEFAULT_PAGE_KEY]), defaulting to Home. Read synchronously
     * here because this runs in [onCreate] before `setContent`.
     */
    private fun resolveInitialBackStack(intent: Intent): List<Route> {
        if (intent.action == Intent.ACTION_APPLICATION_PREFERENCES) {
            return listOf(Route.Main(TAB_SETTINGS))
        }
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val videoID = intent.getStringExtra(Intent.EXTRA_TEXT)?.let { parseSharedVideoId(it) }
            if (videoID != null) {
                return when (intent.component?.className) {
                    "$packageName.ShareWatchLater" ->
                        listOf(Route.Main(TAB_HOME), Route.VideoPage(videoID), Route.AddToWatchLater(videoID))
                    "$packageName.SharePlaylist" ->
                        listOf(
                            Route.Main(TAB_HOME),
                            Route.VideoPage(videoID),
                            Route.AddToPlaylist(videoID, includeWatchLater = false),
                        )
                    else -> listOf(Route.Main(TAB_HOME), Route.VideoPage(videoID))
                }
            }
        }
        val uri = intent.data
        // Total parse: watch, youtu.be, /shorts/, /live/ and /embed/ links route to the
        // video; anything without an 11-char id (channel pages, playlists, garbage `v`
        // params that used to crash videoURLtoID in onCreate) falls through to default.
        if (uri != null) {
            parseSharedVideoId(uri.toString())?.let { videoID ->
                return listOf(Route.Main(TAB_HOME), Route.VideoPage(videoID))
            }
        }
        return defaultBackStack(DataStoreUtils.getInstance(this).getString(DEFAULT_PAGE_KEY))
    }
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // Previously we stopped PlaybackService when PiP closed (isFinishing). New behavior:
        // Keep audio playing and switch to audio-only when PiP is dismissed. PlaybackService
        // handles the audio-only switch via track selection override; we do NOT stop it here.
    }

    override fun onDestroy() {
        // Only stop service when truly destroying the whole activity, not when entering PiP.
        // When the user swipes away PiP window, system destroys activity with isFinishing=true,
        // but we want to KEEP audio (background playback). So we do NOT stop service here.
        // PlaybackService's own onTaskRemoved will handle final cleanup when notification is
        // dismissed or user explicitly stops.
        super.onDestroy()
    }
}

// --- Default startup page ("Open to" setting) ---

/** Tab indices in [YouPipeTabs], shared by deep-link and default-page routing. */
private const val TAB_HOME = 0
private const val TAB_SUBSCRIPTIONS = 1
private const val TAB_HISTORY = 2
private const val TAB_SAVED = 3
private const val TAB_SETTINGS = 4

/** DataStore key holding the persisted default-page choice. */
const val DEFAULT_PAGE_KEY = "default_page"

const val DEFAULT_PAGE_HOME = "home"
const val DEFAULT_PAGE_SUBSCRIPTIONS = "subscriptions"
const val DEFAULT_PAGE_ALL_SUBSCRIPTIONS = "all_subscriptions"
const val DEFAULT_PAGE_HISTORY = "history"
const val DEFAULT_PAGE_DOWNLOADS = "downloads"
const val DEFAULT_PAGE_SAVED = "saved"
const val DEFAULT_PAGE_SETTINGS = "settings"

/**
 * Ordered options for the "Open to" picker: (persisted key, label string res).
 * Stable string keys are used instead of [Route] objects so the choice
 * serializes cleanly and survives refactors.
 */
val DEFAULT_PAGE_OPTIONS: List<Pair<String, Int>> = listOf(
    DEFAULT_PAGE_HOME to UiR.string.home,
    DEFAULT_PAGE_SUBSCRIPTIONS to R.string.title_subscriptions,
    DEFAULT_PAGE_ALL_SUBSCRIPTIONS to R.string.label_all_subscriptions,
    DEFAULT_PAGE_HISTORY to R.string.title_history,
    DEFAULT_PAGE_SAVED to UiR.string.saved,
    DEFAULT_PAGE_SETTINGS to UiR.string.settings,
)

/** Map a persisted default-page key to the initial backstack to launch with. */
fun defaultBackStack(key: String?): List<Route> = when (key) {
    DEFAULT_PAGE_SUBSCRIPTIONS -> listOf(Route.Main(TAB_SUBSCRIPTIONS))
    // Seed the Subscriptions root beneath the all-subscriptions feed so Back
    // returns to the Subscriptions list instead of exiting the app.
    DEFAULT_PAGE_ALL_SUBSCRIPTIONS ->
        listOf(Route.Main(TAB_SUBSCRIPTIONS), Route.SubscriptionVideosPage(null))
    DEFAULT_PAGE_HISTORY -> listOf(Route.Main(TAB_HISTORY))
    // Both the legacy "downloads" key and the new "saved" key open the Saved hub.
    DEFAULT_PAGE_DOWNLOADS, DEFAULT_PAGE_SAVED -> listOf(Route.Main(TAB_SAVED))
    DEFAULT_PAGE_SETTINGS -> listOf(Route.Main(TAB_SETTINGS))
    else -> listOf(Route.Main(TAB_HOME)) // home / unset / unknown
}

@Serializable
sealed interface Route: NavKey {
    @Serializable
    data class Main(val initialTab: Int = 0) : Route

    @Serializable
    data class VideoPage(val videoID: Long) : Route

    @Serializable
    data class ChannelPage(val channelID: String): Route

    @Serializable
    data class SubscriptionVideosPage(val category: String?): Route

    @Serializable
    data class CreateSubscriptionCategory(val id: String?): Route

    @Serializable
    data object Downloads: Route

    @Serializable
    data class PlaylistDetail(val playlistId: Long): Route

    @Serializable
    data object CreatePlaylist: Route

    @Serializable
    data class AddToPlaylist(val videoID: Long, val includeWatchLater: Boolean = true): Route

    @Serializable
    data class AddToWatchLater(val videoID: Long): Route

    @Serializable
    data object SettingsGeneral: Route

    @Serializable
    data object SettingsSponsorBlock: Route

    @Serializable
    data object SettingsRecommendations: Route

    @Serializable
    data object SettingsData: Route

    @Serializable
    data object ManageInterests: Route
}

@Composable
fun Navigation(initialBackStack: List<Route>, ypvm: YouPipeViewModel) {
    val backStack = rememberNavBackStack(initialBackStack)
    MainNavigation(backStack) {
        entry<Route.Main> { YouPipeTabs(backStack, ypvm, it.initialTab) }
        // Morph, not Fullscreen: the feed row's title travels into the title under the player, and
        // a morph only works against a crossfade - the immersive scale would move the target while
        // the title is still on its way to it.
        entry<Route.VideoPage>(metadata = MorphPage()) {
            VideoPage(backStack, ypvm, it.videoID)
        }
        // Morph: a subscription row carries its avatar and name up into the header here. The other
        // ways in - a search result, a video's author line - are unkeyed and simply crossfade.
        entry<Route.ChannelPage>(metadata = MorphPage()) {
            ChannelPage(backStack, ypvm, it.channelID)
        }
        entry<Route.SubscriptionVideosPage> {
            SubscriptionVideosPage(backStack, ypvm, it.category)
        }
        entry<Route.CreateSubscriptionCategory>(metadata = DialogPage()) {
            CreateSubscriptionCategory(backStack, ypvm, it.id)
        }
        entry<Route.Downloads> {
            DownloadedVideosPage(backStack, ypvm)
        }
        entry<Route.PlaylistDetail>(metadata = MorphPage()) {
            PlaylistDetailPage(backStack, ypvm, it.playlistId)
        }
        entry<Route.CreatePlaylist>(metadata = DialogPage()) {
            CreatePlaylist(backStack, ypvm)
        }
        entry<Route.AddToPlaylist>(metadata = DialogPage()) {
            AddToPlaylist(backStack, ypvm, it.videoID, it.includeWatchLater)
        }
        entry<Route.AddToWatchLater>(metadata = DialogPage()) {
            AddToWatchLater(backStack, ypvm, it.videoID)
        }
        entry<Route.SettingsGeneral> {
            GeneralSettingsPage(backStack, ypvm)
        }
        entry<Route.SettingsSponsorBlock> {
            SponsorBlockSettingsPage(backStack, ypvm)
        }
        entry<Route.SettingsRecommendations> {
            RecommendationsSettingsPage(backStack, ypvm)
        }
        entry<Route.SettingsData> {
            DataSettingsPage(backStack, ypvm)
        }
        entry<Route.ManageInterests> {
            ManageInterestsPage(backStack, ypvm)
        }
    }
}

@Composable
private fun YouPipeTabs(backStack: NavBackStack<Route>, ypvm: YouPipeViewModel, initialTab: Int) {
    val pagerState = rememberPagerState(initialPage = initialTab, pageCount = { 5 })
    val tabs = listOf(
        PagerTab("Home", { IconHome() }) { SearchPage(backStack, ypvm) },
        PagerTab("Subscriptions", { IconSubscriptions() }) { SubscriptionsPage(backStack, ypvm) },
        PagerTab("History", { IconHistory() }) { HistoryPage(backStack, ypvm) },
        PagerTab("Saved", { IconSave() }) { SavedPage(backStack, ypvm) },
        PagerTab("Settings", { IconSettings() }) { SettingsPage(backStack, ypvm) },
    )
    TabbedPagerScaffold(tabs = tabs, pagerState = pagerState, tabStyle = TabStyle.BottomNav)
}
