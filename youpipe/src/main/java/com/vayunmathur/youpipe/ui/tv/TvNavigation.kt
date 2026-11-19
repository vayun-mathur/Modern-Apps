package com.vayunmathur.youpipe.ui.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.tv.TvAppFrame
import com.vayunmathur.library.tv.TvIcon
import com.vayunmathur.library.tv.TvRouteDestination
import com.vayunmathur.library.ui.IconHistory
import com.vayunmathur.library.ui.IconHome
import com.vayunmathur.library.ui.IconPhotoLibrary
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.IconSubscriptions
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.SiblingPage
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
 * The TV navigation host: [rememberNavBackStack] + [MainNavigation] over [TvRoute].
 *
 * The six rail tabs are sibling [TvRoute]s hosted by the route-backed
 * [TvAppFrame]; selecting one resets the stack to that tab (SiblingPage
 * semantics). Detail routes (video, channel, feed, playlist, settings
 * sub-pages, dialogs-as-pages) push on top without the rail, so BACK from
 * them returns to the tab that opened them. Each tab entry keeps its own
 * screen, so focus and rememberSaveable state restore per tab.
 *
 * Search is only the [TvRoute.Search] sibling — there is no direct-index
 * rendering of TvSearchPage inside a rail host anymore.
 */
@Composable
fun TvNavigation(ypvm: YouPipeViewModel, initialBackStack: List<TvRoute> = listOf(TvRoute.Home)) {
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
        entry<TvRoute.Search>(metadata = SiblingPage()) {
            TvTabFrame(backStack) { TvSearchPage(backStack, ypvm) }
        }
        entry<TvRoute.Home>(metadata = SiblingPage()) {
            TvTabFrame(backStack) { TvHomeFeedPage(backStack, ypvm) }
        }
        entry<TvRoute.Subscriptions>(metadata = SiblingPage()) {
            TvTabFrame(backStack) { TvSubscriptionsPage(backStack, ypvm) }
        }
        entry<TvRoute.History>(metadata = SiblingPage()) {
            TvTabFrame(backStack) { TvHistoryPage(backStack, ypvm) }
        }
        entry<TvRoute.Saved>(metadata = SiblingPage()) {
            TvTabFrame(backStack) { TvSavedPage(backStack, ypvm) }
        }
        entry<TvRoute.Settings>(metadata = SiblingPage()) {
            TvTabFrame(backStack) { TvSettingsPage(backStack, ypvm) }
        }
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
 * [com.vayunmathur.youpipe.platform.defaultBackStack] mapping. The start entry is a
 * real sibling rail route; `all_subscriptions` seeds the Subscriptions root beneath
 * the feed so BACK returns to the list instead of exiting.
 */
fun tvDefaultBackStack(key: String?): List<TvRoute> = when (key) {
    DEFAULT_PAGE_SUBSCRIPTIONS -> listOf(TvRoute.Subscriptions)
    DEFAULT_PAGE_ALL_SUBSCRIPTIONS ->
        listOf(TvRoute.Subscriptions, TvRoute.SubscriptionFeed(null))
    DEFAULT_PAGE_HISTORY -> listOf(TvRoute.History)
    // Both the legacy "downloads" key and the new "saved" key open the Saved hub.
    DEFAULT_PAGE_DOWNLOADS, DEFAULT_PAGE_SAVED -> listOf(TvRoute.Saved)
    DEFAULT_PAGE_SETTINGS -> listOf(TvRoute.Settings)
    else -> listOf(TvRoute.Home) // home / unset / unknown
}

/**
 * One rail tab's frame: the route-backed [TvAppFrame] (rail appearance and
 * focus behaviour unchanged from the index-based frame) around [content].
 * Only composed while a tab is on top — pushed pages get no rail.
 * BACK on a root tab returns to the rail; BACK from the rail exits.
 */
@Composable
private fun TvTabFrame(backStack: NavBackStack<TvRoute>, content: @Composable () -> Unit) {
    TvAppFrame(
        backStack = backStack,
        destinations = railDestinations(),
        // This frame is always the bottom of the TV stack: BACK from content returns to
        // the rail, and BACK from the rail exits (the activity finishes).
        backReturnsToRail = backStack.backStack.size <= 1,
    ) { content() }
}

@Composable
private fun railDestinations(): List<TvRouteDestination<TvRoute>> {
    val searchLabel = stringResource(UiR.string.search)
    val homeLabel = stringResource(UiR.string.home)
    val subsLabel = stringResource(R.string.title_subscriptions)
    val historyLabel = stringResource(R.string.title_history)
    val savedLabel = stringResource(UiR.string.saved)
    val settingsLabel = stringResource(UiR.string.settings)
    return remember(searchLabel, homeLabel, subsLabel, historyLabel, savedLabel, settingsLabel) {
        listOf(
            TvRouteDestination(TvRoute.Search, searchLabel) { TvIcon { IconSearch() } },
            TvRouteDestination(TvRoute.Home, homeLabel) { TvIcon { IconHome() } },
            TvRouteDestination(TvRoute.Subscriptions, subsLabel) { TvIcon { IconSubscriptions() } },
            TvRouteDestination(TvRoute.History, historyLabel) { TvIcon { IconHistory() } },
            TvRouteDestination(TvRoute.Saved, savedLabel) { TvIcon { IconPhotoLibrary() } },
            TvRouteDestination(TvRoute.Settings, settingsLabel) { TvIcon { IconSettings() } },
        )
    }
}
