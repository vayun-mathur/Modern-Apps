package com.vayunmathur.email.ui

import com.vayunmathur.email.Route
import com.vayunmathur.email.platform.EmailViewModel
import com.vayunmathur.email.platform.addAccountEntries
import com.vayunmathur.library.util.EntryProviderScope
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MorphPage
import com.vayunmathur.library.util.NavBackStack

/** Every destination of the mail graph, registered on the host's entry scope. */
internal fun EntryProviderScope<Route>.emailEntries(
    viewModel: EmailViewModel,
    backStack: NavBackStack<Route>,
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
private fun EntryProviderScope<Route>.folderEntries(
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
