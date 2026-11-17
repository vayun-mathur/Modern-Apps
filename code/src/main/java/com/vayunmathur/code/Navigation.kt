package com.vayunmathur.code

import android.os.Environment
import androidx.compose.runtime.Composable
import com.vayunmathur.code.platform.EditorViewModel
import com.vayunmathur.code.ui.EditorPage
import com.vayunmathur.code.ui.FolderBrowserPage
import com.vayunmathur.code.ui.GitPage
import com.vayunmathur.code.ui.PreviewPage
import com.vayunmathur.code.ui.SearchPage
import com.vayunmathur.code.ui.SettingsPage
import com.vayunmathur.code.ui.SnippetsPage
import com.vayunmathur.code.ui.TerminalPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.openSettingsIfRequested
import com.vayunmathur.library.util.rememberNavBackStack

@Composable
fun Navigation(viewModel: EditorViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Editor)
    // Land on settings when opened from the system App Info page.
    backStack.openSettingsIfRequested(Route.Settings)
    MainNavigation(backStack) {
        entry<Route.Editor> {
            EditorPage(
                viewModel,
                onOpenSettings = { backStack.add(Route.Settings) },
                onOpenSearch = { backStack.add(Route.Search) },
                onOpenFolder = {
                    backStack.add(Route.FolderBrowser(Environment.getExternalStorageDirectory().absolutePath))
                },
                onOpenGit = { backStack.add(Route.Git) },
                onOpenTerminal = { backStack.add(Route.Terminal) },
                onOpenPreview = { backStack.add(Route.Preview) },
            )
        }
        entry<Route.Settings> { SettingsPage(viewModel, backStack) }
        entry<Route.Search> { SearchPage(viewModel, backStack) }
        entry<Route.FolderBrowser> { route -> FolderBrowserPage(viewModel, backStack, route.path) }
        entry<Route.Git> { GitPage(viewModel, backStack) }
        entry<Route.Terminal> { TerminalPage(viewModel, backStack) }
        entry<Route.Preview> { PreviewPage(viewModel, backStack) }
        entry<Route.Snippets> { SnippetsPage(viewModel, backStack) }
    }
}
