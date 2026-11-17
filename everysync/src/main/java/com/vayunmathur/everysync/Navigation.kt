package com.vayunmathur.everysync

import androidx.compose.runtime.Composable
import com.vayunmathur.everysync.platform.EverySyncViewModel
import com.vayunmathur.everysync.ui.AccountDetailScreen
import com.vayunmathur.everysync.ui.AccountsScreen
import com.vayunmathur.everysync.ui.AddAccountScreen
import com.vayunmathur.everysync.ui.SettingsScreen
import com.vayunmathur.library.util.DialogPage
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.MorphPage
import com.vayunmathur.library.util.OpenSettingsIfRequested
import com.vayunmathur.library.util.rememberNavBackStack

@Composable
fun Navigation(viewModel: EverySyncViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Accounts)
    // Land on settings when opened from the system App Info page.
    backStack.OpenSettingsIfRequested(Route.Settings)
    MainNavigation(backStack) {
        entry<Route.Accounts>(metadata = ListPage()) { AccountsScreen(backStack, viewModel) }
        entry<Route.AddAccount>(metadata = ListPage()) { AddAccountScreen(backStack, viewModel) }
        entry<Route.AccountDetail>(metadata = ListDetailPage() + MorphPage()) {
            AccountDetailScreen(backStack, viewModel, it.accountName)
        }
        entry<Route.Settings>(metadata = ListPage()) { SettingsScreen(backStack, viewModel) }
    }
}
