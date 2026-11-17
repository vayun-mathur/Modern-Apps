package com.vayunmathur.passwords

import androidx.compose.runtime.Composable
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.MorphPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.OpenSettingsIfRequested
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.passwords.platform.PasswordsViewModel
import com.vayunmathur.passwords.ui.MenuPage
import com.vayunmathur.passwords.ui.PasskeyPage
import com.vayunmathur.passwords.ui.PasswordEditPage
import com.vayunmathur.passwords.ui.PasswordPage
import com.vayunmathur.passwords.ui.SettingsPage

@Composable
fun Navigation(
    passwordsViewModel: PasswordsViewModel,
) {
    val backStack = rememberNavBackStack<Route>(Route.Menu)
    // Land on settings when opened from the system App Info page.
    backStack.OpenSettingsIfRequested(Route.Settings)
    MainNavigation(backStack) {
        entry<Route.Menu>(metadata = ListPage()) {
            MenuPage(backStack, passwordsViewModel)
        }
        entry<Route.PasswordPage>(metadata = ListDetailPage() + MorphPage()) {
            PasswordPage(backStack, it.id, passwordsViewModel)
        }
        entry<Route.PasswordEditPage>(metadata = ListDetailPage() + MorphPage()) {
            PasswordEditPage(backStack, it.id, passwordsViewModel)
        }
        entry<Route.PasskeyPage>(metadata = ListDetailPage() + MorphPage()) {
            PasskeyPage(backStack, it.id, passwordsViewModel)
        }
        entry<Route.Settings>(metadata = ListDetailPage()) {
            SettingsPage(backStack, passwordsViewModel)
        }
    }
}
