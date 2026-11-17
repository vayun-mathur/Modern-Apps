package com.vayunmathur.youpipe

import androidx.compose.runtime.Composable
import com.vayunmathur.library.ui.IconHistory
import com.vayunmathur.library.ui.IconHome
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.IconSubscriptions
import com.vayunmathur.library.ui.RouteTab
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.library.util.DialogPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.MorphPage
import com.vayunmathur.library.util.SiblingPage
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.youpipe.platform.YouPipeViewModel
import com.vayunmathur.youpipe.ui.ChannelPage
import com.vayunmathur.youpipe.ui.DataSettingsPage
import com.vayunmathur.youpipe.ui.DownloadedVideosPage
import com.vayunmathur.youpipe.ui.GeneralSettingsPage
import com.vayunmathur.youpipe.ui.HistoryPage
import com.vayunmathur.youpipe.ui.ManageInterestsPage
import com.vayunmathur.youpipe.ui.PlaylistDetailPage
import com.vayunmathur.youpipe.ui.RecommendationsSettingsPage
import com.vayunmathur.youpipe.ui.SavedPage
import com.vayunmathur.youpipe.ui.SearchPage
import com.vayunmathur.youpipe.ui.SettingsPage
import com.vayunmathur.youpipe.ui.SponsorBlockSettingsPage
import com.vayunmathur.youpipe.ui.SubscriptionVideosPage
import com.vayunmathur.youpipe.ui.SubscriptionsPage
import com.vayunmathur.youpipe.ui.VideoPage
import com.vayunmathur.youpipe.ui.dialogs.AddToPlaylist
import com.vayunmathur.youpipe.ui.dialogs.AddToWatchLater
import com.vayunmathur.youpipe.ui.dialogs.CreatePlaylist
import com.vayunmathur.youpipe.ui.dialogs.CreateSubscriptionCategory

@Composable
fun Navigation(initialBackStack: List<Route>, ypvm: YouPipeViewModel) {
    val backStack = rememberNavBackStack(initialBackStack)
    val tabs = listOf<RouteTab<Route>>(
        RouteTab(Route.Home, "Home", { IconHome() }),
        RouteTab(Route.Subscriptions, "Subscriptions", { IconSubscriptions() }),
        RouteTab(Route.History, "History", { IconHistory() }),
        RouteTab(Route.Saved, "Saved", { IconSave() }),
        RouteTab(Route.Settings, "Settings", { IconSettings() }),
    )
    // Route-backed tabs: selecting a tab resets the stack to that sibling
    // (see TabbedPagerScaffold), and MainNavigation renders the top route —
    // a tab with SiblingPage() crossfade, or a detail/settings sub-route
    // pushed over it with no tab selected.
    TabbedPagerScaffold(backStack = backStack, tabs = tabs) {
        MainNavigation(backStack) {
        entry<Route.Home>(metadata = SiblingPage()) { SearchPage(backStack, ypvm) }
        entry<Route.Subscriptions>(metadata = SiblingPage()) { SubscriptionsPage(backStack, ypvm) }
        entry<Route.History>(metadata = SiblingPage()) { HistoryPage(backStack, ypvm) }
        entry<Route.Saved>(metadata = SiblingPage()) { SavedPage(backStack, ypvm) }
        entry<Route.Settings>(metadata = SiblingPage()) { SettingsPage(backStack, ypvm) }
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
}
