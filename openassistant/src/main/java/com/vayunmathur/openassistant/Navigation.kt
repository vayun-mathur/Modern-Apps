package com.vayunmathur.openassistant

import androidx.compose.runtime.Composable
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.openassistant.platform.AssistantViewModel
import com.vayunmathur.openassistant.ui.LiteRTChatUi
import com.vayunmathur.openassistant.ui.SettingsPage

@Composable
fun Navigation(assistantViewModel: AssistantViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.ConversationPage(0))
    MainNavigation(backStack) {
        // The chat column is the list pane: its NavigationSuite drawer is permanent on
        // Expanded, and settings renders beside it as the detail pane.
        entry<Route.ConversationPage>(metadata = ListPage()) {
            LiteRTChatUi(backStack, it.id, assistantViewModel)
        }
        entry<Route.SettingsPage>(metadata = ListDetailPage()) {
            SettingsPage(backStack, assistantViewModel)
        }
    }
}
