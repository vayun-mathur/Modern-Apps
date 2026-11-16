package com.vayunmathur.code

import android.content.Intent
import android.os.Environment
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.code.ui.EditorPage
import com.vayunmathur.code.ui.FolderBrowserPage
import com.vayunmathur.code.ui.GitPage
import com.vayunmathur.code.ui.PreviewPage
import com.vayunmathur.code.ui.SearchPage
import com.vayunmathur.code.ui.SettingsPage
import com.vayunmathur.code.ui.SnippetsPage
import com.vayunmathur.code.ui.TerminalPage
import com.vayunmathur.code.ui.TerminalPage
import com.vayunmathur.code.util.EditorPrefs
import com.vayunmathur.code.util.EditorViewModel
import com.vayunmathur.code.util.checkExternalChanges
import com.vayunmathur.code.util.openExternal
import com.vayunmathur.library.ui.AppPermissionsGate
import com.vayunmathur.library.ui.AppPermissionsSpec
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.IconFolderOpen
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.ui.PermissionRequirement
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.openSettingsIfRequested
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.serialization.Serializable

class MainActivity : MaActivity() {
    private val viewModel: EditorViewModel by viewModels()

    override fun onCreateBeforeContent() {
        handleIntent(intent)
    }

    @Composable
    override fun AppTheme(content: @Composable () -> Unit) {
        val darkTheme = when (viewModel.themeMode) {
            EditorPrefs.THEME_LIGHT -> false
            EditorPrefs.THEME_DARK -> true
            else -> null
        }
        DynamicTheme(darkTheme = darkTheme, content = content)
    }

    @Composable
    override fun Content() {
        AppPermissionsGate(
            spec = AppPermissionsSpec(
                title = stringResource(R.string.storage_permission_title),
                subtitle = stringResource(R.string.storage_permission_rationale),
                icon = { IconFolderOpen() },
                requirements = listOf(PermissionRequirement.AllFiles)
            )
        ) {
            Navigation(viewModel)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkExternalChanges()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** VIEW/EDIT opens from other apps carry the file in [Intent.getData]; open it in a tab. */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        if (intent.action == Intent.ACTION_VIEW || intent.action == Intent.ACTION_EDIT) {
            intent.data?.let { viewModel.openExternal(it) }
        }
    }
}

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Editor : Route

    @Serializable
    data object Settings : Route

    @Serializable
    data object Search : Route

    /** One directory level of the folder picker; Back pops one level. */
    @Serializable
    data class FolderBrowser(val path: String) : Route

    @Serializable
    data object Git : Route

    @Serializable
    data object Terminal : Route

    @Serializable
    data object Preview : Route

    @Serializable
    data object Snippets : Route
}

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
