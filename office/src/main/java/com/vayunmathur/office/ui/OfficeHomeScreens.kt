package com.vayunmathur.office.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.IconHome
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.RouteTab
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.SiblingPage
import com.vayunmathur.office.R
import com.vayunmathur.office.Route
import com.vayunmathur.office.platform.OfficeDocMeta
import com.vayunmathur.office.platform.OfficeViewModel
import com.vayunmathur.office.platform.createNewPresentation
import com.vayunmathur.office.platform.createNewSpreadsheet
import com.vayunmathur.office.platform.createNewTextDocument

/**
 * The two bottom-nav home tabs as sibling [Route] destinations:
 * route-backed [TabbedPagerScaffold] chrome with [MainNavigation] hosted
 * in its content slot, so the editor routes pushed over a home tab render
 * with no tab selected. Switching tabs resets the stack.
 */
@Composable
internal fun OfficeTabs(
    backStack: NavBackStack<Route>,
    viewModel: OfficeViewModel,
    onOpenDocument: () -> Unit,
    onNavigateEditor: () -> Unit,
    onOpenOnlineDoc: (com.vayunmathur.office.platform.OfficeDocMeta) -> Unit,
    editorContent: @Composable () -> Unit = {},
) {
    TabbedPagerScaffold(
        backStack = backStack,
        tabs = listOf<RouteTab<Route>>(
            RouteTab(Route.OfflineHome, "Offline") { IconHome() },
            RouteTab(Route.OnlineHome, "Online") { IconDownload() },
        ),
    ) {
        MainNavigation(backStack) {
            entry<Route.OfflineHome>(metadata = SiblingPage()) {
                InitialScreen(
                    viewModel = viewModel,
                    onOpenDocument = onOpenDocument,
                    onNavigateEditor = onNavigateEditor
                )
            }
            entry<Route.OnlineHome>(metadata = SiblingPage()) {
                OnlineTab(viewModel = viewModel, onOpenDoc = onOpenOnlineDoc)
            }
            entry<Route.OfflineEditor> { editorContent() }
            entry<Route.OnlineEditor> { editorContent() }
        }
    }
}

@Composable
fun InitialScreen(viewModel: OfficeViewModel, onOpenDocument: () -> Unit, onNavigateEditor: () -> Unit) {
    HomeScreen(
        onOpenDocument = onOpenDocument,
        onNewTextDocument = { viewModel.createNewTextDocument(); onNavigateEditor() },
        onNewSpreadsheet = { viewModel.createNewSpreadsheet(); onNavigateEditor() },
        onNewPresentation = { viewModel.createNewPresentation(); onNavigateEditor() },
    )
}

