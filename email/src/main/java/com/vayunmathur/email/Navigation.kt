package com.vayunmathur.email

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.email.platform.EmailViewModel
import com.vayunmathur.email.platform.IntentState
import com.vayunmathur.email.ui.AddAccountFormScreen
import com.vayunmathur.email.ui.AddAccountScreen
import com.vayunmathur.email.ui.ComposerScreen
import com.vayunmathur.email.ui.DraftsScreen
import com.vayunmathur.email.ui.EmailDrawerContent
import com.vayunmathur.email.ui.EmlViewerScreen
import com.vayunmathur.email.ui.MessageListPage
import com.vayunmathur.email.ui.MessageThreadPage
import com.vayunmathur.email.ui.OutboxScreen
import com.vayunmathur.email.ui.SettingsScreen
import com.vayunmathur.library.ui.DrawerState
import com.vayunmathur.library.ui.DrawerValue
import com.vayunmathur.library.util.EntryProviderScope
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.ui.ModalNavigationDrawer
import com.vayunmathur.library.ui.rememberDrawerState
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.MorphPage
import com.vayunmathur.library.util.openSettingsIfRequested
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
    backStack.openSettingsIfRequested(Route.Settings)

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
        EmailNavGraph(viewModel, backStack, scope, drawerState)
    }
}

@Composable
private fun EmailNavGraph(
    viewModel: EmailViewModel,
    backStack: NavBackStack<Route>,
    scope: kotlinx.coroutines.CoroutineScope,
    drawerState: DrawerState,
) {
    MainNavigation(backStack) {
        messageListEntry(viewModel, backStack, scope, drawerState)
        messageThreadEntry(viewModel, backStack)
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
        miscEntries(viewModel, backStack)
    }
}

private fun EntryProviderScope<Route>.messageListEntry(
    viewModel: EmailViewModel,
    backStack: NavBackStack<Route>,
    scope: kotlinx.coroutines.CoroutineScope,
    drawerState: DrawerState,
) {
    entry<Route.MessageList>(metadata = ListPage()) {
        MessageListPage(
            viewModel = viewModel,
            onMessageClick = { msg ->
                val thread = msg.threadId ?: msg.id.toString()
                backStack.add(Route.MessageThread(msg.accountEmail, thread))
            },
            onComposeClick = { backStack.add(Route.Composer()) },
            onOpenDrawer = { scope.launch { drawerState.open() } }
        )
    }
}

private fun EntryProviderScope<Route>.messageThreadEntry(
    viewModel: EmailViewModel,
    backStack: NavBackStack<Route>,
) {
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
            onCompose = { to, sub -> backStack.add(Route.Composer(to = to, subject = sub)) }
        )
    }
}

private fun EntryProviderScope<Route>.miscEntries(
    viewModel: EmailViewModel,
    backStack: NavBackStack<Route>,
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

/**
 * The add-account destinations, shared by the main graph and the
 * first-run gate in `MainContent` (which hosts them on its own stack
 * before any account exists to navigate with).
 *
 * Picking a provider pushes [Route.AddAccountForm] carrying the provider
 * id; Back from the form is the stack pop back to the picker, and
 * [onAccountAdded] decides where a saved account goes next (pop in the
 * main graph, nothing at the gate — the accounts flow swaps the gate for
 * the graph on its own).
 */
internal fun EntryProviderScope<Route>.addAccountEntries(
    backStack: NavBackStack<Route>,
    onPickBack: (() -> Unit)? = { backStack.pop() },
    onAccountAdded: () -> Unit,
) {
    entry<Route.AddAccount>(metadata = ListDetailPage()) {
        AddAccountScreen(
            onBack = onPickBack,
            onPickProvider = { providerId -> backStack.add(Route.AddAccountForm(providerId)) },
        )
    }
    entry<Route.AddAccountForm>(metadata = ListDetailPage()) { route ->
        AddAccountFormScreen(
            providerId = route.providerId,
            onBack = { backStack.pop() },
            onAccountAdded = onAccountAdded,
        )
    }
}
