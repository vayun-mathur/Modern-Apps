package com.vayunmathur.youpipe.ui.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.tv.TvAppFrame
import com.vayunmathur.library.tv.TvDestination
import com.vayunmathur.library.tv.TvIcon
import com.vayunmathur.library.ui.IconHistory
import com.vayunmathur.library.ui.IconHome
import com.vayunmathur.library.ui.IconPhotoLibrary
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.IconSubscriptions
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.youpipe.platform.DEFAULT_PAGE_ALL_SUBSCRIPTIONS
import com.vayunmathur.youpipe.platform.DEFAULT_PAGE_DOWNLOADS
import com.vayunmathur.youpipe.platform.DEFAULT_PAGE_HISTORY
import com.vayunmathur.youpipe.platform.DEFAULT_PAGE_SAVED
import com.vayunmathur.youpipe.platform.DEFAULT_PAGE_SETTINGS
import com.vayunmathur.youpipe.platform.DEFAULT_PAGE_SUBSCRIPTIONS
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.platform.YouPipeViewModel

/**
 * The TV navigation host: [rememberNavBackStack] + [MainNavigation] over [TvRoute], inside a
 * [TvAppFrame] icon rail.
 *
 * The rail (Search, Home, Subscriptions, History, Saved, Settings) owns the top-level
 * destinations — focus moves select, CENTER jumps into content, BACK from content returns to
 * the rail. Detail routes (video, channel, feed, playlist, settings sub-pages, dialogs-as-pages)
 * push above the frame; popping them returns to the rail screen underneath.
 */
@Composable
fun TvNavigation(ypvm: YouPipeViewModel, initialBackStack: List<TvRoute> = listOf(TvRoute.Main())) {
    val backStack = rememberNavBackStack(initialBackStack)
    // The remote map lives here, not in an Activity dispatchKeyEvent override: preview key
    // events reach this root before the focus system, so TvDpadInput claims a key only while
    // there is something to act on (player attached, overlay up); everything else falls
    // through to focus and nav, which is what the browse screens need.
    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { TvDpadInput.onKeyEvent(it.nativeKeyEvent) },
    ) {
    MainNavigation(backStack) {
        entry<TvRoute.Main> { TvRailHost(backStack, ypvm, it.initialTab) }
        entry<TvRoute.Search> { TvSearchPage(backStack, ypvm) }
        entry<TvRoute.Video> { TvVideoPage(backStack, ypvm, it.videoID) }
        entry<TvRoute.VideoPlayerRoute> { TvPlayerPage(backStack, ypvm, it.videoID) }
        entry<TvRoute.Channel> { TvChannelPage(backStack, ypvm, it.channelID) }
        entry<TvRoute.SubscriptionFeed> { TvSubscriptionFeedPage(backStack, ypvm, it.category) }
        entry<TvRoute.CreateSubscriptionCategory> { TvCreateCategoryPage(backStack, ypvm, it.id) }
        entry<TvRoute.Downloads> { TvDownloadsPage(backStack, ypvm) }
        entry<TvRoute.PlaylistDetail> { TvPlaylistDetailPage(backStack, ypvm, it.playlistId) }
        entry<TvRoute.CreatePlaylist> { TvCreatePlaylistPage(backStack, ypvm) }
        entry<TvRoute.AddToPlaylist> { TvAddToPlaylistPage(backStack, ypvm, it.videoID, it.includeWatchLater) }
        entry<TvRoute.SettingsGeneral> { TvGeneralSettingsPage(ypvm) }
        entry<TvRoute.SettingsSponsorBlock> { TvSponsorBlockSettingsPage(ypvm) }
        entry<TvRoute.SettingsRecommendations> { TvRecommendationsSettingsPage(backStack, ypvm) }
        entry<TvRoute.SettingsData> { TvDataSettingsPage(ypvm) }
        entry<TvRoute.ManageInterests> { TvManageInterestsPage(ypvm) }
    }
    }
}

/**
 * Map a persisted default-page key to the TV initial backstack, mirroring the phone
 * [com.vayunmathur.youpipe.platform.defaultBackStack] mapping. Rail indices match [TvRailHost]:
 * `all_subscriptions` seeds the Subscriptions root beneath the feed so BACK returns to the
 * list instead of exiting.
 */
fun tvDefaultBackStack(key: String?): List<TvRoute> = when (key) {
    DEFAULT_PAGE_SUBSCRIPTIONS -> listOf(TvRoute.Main(TV_RAIL_SUBSCRIPTIONS))
    DEFAULT_PAGE_ALL_SUBSCRIPTIONS ->
        listOf(TvRoute.Main(TV_RAIL_SUBSCRIPTIONS), TvRoute.SubscriptionFeed(null))
    DEFAULT_PAGE_HISTORY -> listOf(TvRoute.Main(TV_RAIL_HISTORY))
    // Both the legacy "downloads" key and the new "saved" key open the Saved hub.
    DEFAULT_PAGE_DOWNLOADS, DEFAULT_PAGE_SAVED -> listOf(TvRoute.Main(TV_RAIL_SAVED))
    DEFAULT_PAGE_SETTINGS -> listOf(TvRoute.Main(TV_RAIL_SETTINGS))
    else -> listOf(TvRoute.Main(TV_RAIL_HOME)) // home / unset / unknown
}

/**
 * The six TV rail destinations inside [TvAppFrame]: Search is a destination (never a grid
 * card), then Home, Subscriptions, History, Saved/Library, Settings.
 */
@Composable
private fun TvRailHost(
    backStack: NavBackStack<TvRoute>,
    ypvm: YouPipeViewModel,
    initialTab: Int,
) {
    var rail by rememberSaveable { mutableIntStateOf(initialTab.coerceIn(0, TV_RAIL_COUNT - 1)) }
    val searchLabel = stringResource(UiR.string.search)
    val homeLabel = stringResource(UiR.string.home)
    val subsLabel = stringResource(R.string.title_subscriptions)
    val historyLabel = stringResource(R.string.title_history)
    val savedLabel = stringResource(UiR.string.saved)
    val settingsLabel = stringResource(UiR.string.settings)
    val destinations = remember(searchLabel, homeLabel, subsLabel, historyLabel, savedLabel, settingsLabel) {
        listOf(
            TvDestination(searchLabel) { TvIcon { IconSearch() } },
            TvDestination(homeLabel) { TvIcon { IconHome() } },
            TvDestination(subsLabel) { TvIcon { IconSubscriptions() } },
            TvDestination(historyLabel) { TvIcon { IconHistory() } },
            TvDestination(savedLabel) { TvIcon { IconPhotoLibrary() } },
            TvDestination(settingsLabel) { TvIcon { IconSettings() } },
        )
    }
    TvAppFrame(
        destinations = destinations,
        selected = rail,
        onSelect = { rail = it },
        // This frame is always the bottom of the TV stack: BACK from content returns to
        // the rail, and BACK from the rail exits (the activity finishes).
        backReturnsToRail = backStack.backStack.size <= 1,
    ) {
        when (rail) {
            TV_RAIL_SEARCH -> TvSearchPage(backStack, ypvm)
            TV_RAIL_HOME -> TvHomeFeedPage(backStack, ypvm)
            TV_RAIL_SUBSCRIPTIONS -> TvSubscriptionsPage(backStack, ypvm)
            TV_RAIL_HISTORY -> TvHistoryPage(backStack, ypvm)
            TV_RAIL_SAVED -> TvSavedPage(backStack, ypvm)
            else -> TvSettingsPage(backStack, ypvm)
        }
    }
}
