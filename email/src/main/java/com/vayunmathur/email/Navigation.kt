package com.vayunmathur.email

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.email.platform.EmailViewModel
import com.vayunmathur.email.platform.IntentState
import com.vayunmathur.email.platform.addAccountEntries
import com.vayunmathur.email.ui.ComposerScreen
import com.vayunmathur.email.ui.DraftsScreen
import com.vayunmathur.email.ui.EmailDrawerContent
import com.vayunmathur.email.ui.EmlViewerScreen
import com.vayunmathur.email.ui.MessageListPage
import com.vayunmathur.email.ui.MessageThreadPage
import com.vayunmathur.email.ui.OutboxScreen
import com.vayunmathur.email.ui.SettingsScreen
import com.vayunmathur.library.ui.DrawerValue
import com.vayunmathur.library.ui.ModalNavigationDrawer
import com.vayunmathur.library.ui.rememberDrawerState
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.MorphPage
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

/** Every destination of the mail graph, registered on the host's entry scope. */
private fun com.vayunmathur.library.util.EntryProviderScope<Route>.emailEntries(
    viewModel: EmailViewModel,
    backStack: com.vayunmathur.library.util.NavBackStack<Route>,
    onOpenDrawer: () -> Unit,
) {
    entry<Route.MessageList>(metadata = ListPage()) {
        MessageListPage(
            viewModel = viewModel,
            onMessageClick = { msg ->
                val thread = msg.threadId ?: msg.id.toString()
                backStack.add(Route.MessageThread(msg.accountEmail, thread))
            },
            onComposeClick = { backStack.add(Route.Composer()) },
            onOpenDrawer = onOpenDrawer,
        )
    }
    entry<Route.MessageThread>(metadata = ListDetailPage() + MorphPage()) { route ->
        MessageThreadPage(
            viewModel = viewModel,
            accountEmail = route.accountEmail,
            threadId = route.threadId,
            onBack = { backStack.pop() },
            onReply = { to, sub, ref ->
                val subject = "Re: $sub"
                backStack.add(
                    Route.Composer(to = to, subject = subject, references = ref, inReplyTo = ref),
                )
            },
            onForward = { sub, body ->
                val subject = "Fwd: $sub"
                val quote = "\n\n---------- Forwarded message ----------\n$body"
                backStack.add(Route.Composer(subject = subject, body = quote))
            },
            onCompose = { to, sub -> backStack.add(Route.Composer(to = to, subject = sub)) },
        )
    }
    // Morph: a draft's subject line travels out of the drafts list into the subject field.
    // The other five ways in carry no draft id, so their subject key is null and the screen
    // simply crossfades.
    entry<Route.Composer>(metadata = ListDetailPage() + MorphPage()) { route ->
        ComposerScreen(
            viewModel = viewModel,
            initialTo = route.to,
            initialSubject = route.subject,
            initialBody = route.body,
            inReplyTo = route.inReplyTo,
            references = route.references,
            draftId = route.draftId,
            onBack = { backStack.pop() },
        )
    }
    folderEntries(viewModel, backStack)
}

/** Outbox, settings, drafts, the EML viewer and the add-account graph. */
private fun com.vayunmathur.library.util.EntryProviderScope<Route>.folderEntries(
    viewModel: EmailViewModel,
    backStack: com.vayunmathur.library.util.NavBackStack<Route>,
) {
    entry<Route.Outbox>(metadata = ListDetailPage()) {
        OutboxScreen(viewModel = viewModel, onBack = { backStack.pop() })
    }
    addAccountEntries(backStack, onAccountAdded = { backStack.pop() })
    entry<Route.Settings>(metadata = ListDetailPage()) {
        SettingsScreen(viewModel = viewModel, onBack = { backStack.pop() })
    }
    entry<Route.Drafts>(metadata = ListDetailPage()) {
        DraftsScreen(
            viewModel = viewModel,
            onBack = { backStack.pop() },
            onOpenDraft = { id -> backStack.add(Route.Composer(draftId = id)) },
        )
    }
    entry<Route.EmlViewer>(metadata = ListDetailPage()) { route ->
        EmlViewerScreen(
            uriString = route.uriString,
            onBack = { backStack.pop() },
            onComposeForward = { sub, body ->
                backStack.add(Route.Composer(subject = sub, body = body))
            },
        )
    }
}
