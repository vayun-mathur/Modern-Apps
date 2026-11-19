package com.vayunmathur.email

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.email.platform.EmailViewModel
import com.vayunmathur.email.platform.IntentState
import com.vayunmathur.email.ui.EmailDrawerContent
import com.vayunmathur.email.ui.emailEntries
import com.vayunmathur.library.ui.DrawerValue
import com.vayunmathur.library.ui.ModalNavigationDrawer
import com.vayunmathur.library.ui.rememberDrawerState
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.OpenSettingsIfRequested
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.coroutines.launch

@Composable
fun Navigation(viewModel: EmailViewModel) {
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val context = LocalContext.current

    val accounts by viewModel.accounts.collectAsStateWithLifecycle(emptyList())
    val selectedAccountEmail by viewModel.selectedAccountEmail.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle(emptyList())
    val selectedFolderName by viewModel.selectedFolderName.collectAsStateWithLifecycle()
    val outbox by viewModel.outbox.collectAsStateWithLifecycle(emptyList())

    val backStack = rememberNavBackStack<Route>(Route.MessageList)
    backStack.OpenSettingsIfRequested(Route.Settings)

    val navigationRoute = IntentState.navigationRoute
    LaunchedEffect(navigationRoute) {
        if (navigationRoute != null) {
            backStack.add(navigationRoute)
            IntentState.navigationRoute = null
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            EmailDrawerContent(
                accounts = accounts,
                selectedAccountEmail = selectedAccountEmail,
                folders = folders,
                selectedFolderName = selectedFolderName,
                outboxCount = outbox.size,
                onSelectAccount = { email ->
                    viewModel.selectAccount(email)
                    backStack.reset(Route.MessageList)
                    scope.launch { drawerState.close() }
                },
                onSelectFolder = { folderName ->
                    viewModel.selectFolder(folderName)
                    backStack.reset(Route.MessageList)
                    scope.launch { drawerState.close() }
                },
                onOpenOutbox = { backStack.add(Route.Outbox); scope.launch { drawerState.close() } },
                onOpenSystem = { key ->
                    backStack.add(if (key == "drafts") Route.Drafts else Route.Settings)
                    scope.launch { drawerState.close() }
                },
                onLogout = { viewModel.logout(context); scope.launch { drawerState.close() } },
                onAddAccount = { backStack.add(Route.AddAccount); scope.launch { drawerState.close() } },
            )
        },
    ) {
        MainNavigation(backStack) {
            emailEntries(viewModel, backStack, onOpenDrawer = { scope.launch { drawerState.open() } })
        }
    }
}
