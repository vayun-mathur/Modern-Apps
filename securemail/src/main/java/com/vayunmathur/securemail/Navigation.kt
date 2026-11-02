package com.vayunmathur.securemail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.library.util.EntryProviderScope
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.SiblingPage
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.securemail.data.MailFolder
import com.vayunmathur.securemail.domain.mail.AddressValidator
import com.vayunmathur.securemail.platform.Draft
import com.vayunmathur.securemail.platform.MailViewModel
import com.vayunmathur.securemail.ui.ComposeScreen
import com.vayunmathur.securemail.ui.FolderActions
import com.vayunmathur.securemail.ui.InboxScreen
import com.vayunmathur.securemail.ui.LockScreen
import com.vayunmathur.securemail.ui.MailBottomBar
import com.vayunmathur.securemail.ui.MailTab
import com.vayunmathur.securemail.ui.OnboardingScreen
import com.vayunmathur.securemail.ui.OutActions
import com.vayunmathur.securemail.ui.OutScreen
import com.vayunmathur.securemail.ui.ReaderScreen
import com.vayunmathur.securemail.ui.SettingsScreen
import com.vayunmathur.securemail.ui.tab

@Composable
fun Navigation(viewModel: MailViewModel, initialRoute: Route? = null) {
    val state = viewModel.state
    val context = LocalContext.current
    val folderMessages by viewModel.folderMessages.collectAsState(emptyList())
    val allMessages by viewModel.messages.collectAsState(emptyList())
    val outbox by viewModel.outboxCount.collectAsState(0)
    // The stack follows the account flag, not navigation events: once an address
    // is claimed there is no going back to onboarding, and a locked key gates
    // everything. reset() rather than add() for the same reason as backup/.
    val start = when {
        !state.onboarded -> Route.Onboarding
        state.locked -> Route.Lock
        else -> Route.Inbox
    }
    val backStack = rememberNavBackStack<Route>(start)

    LaunchedEffect(state.onboarded, state.locked) {
        val target = when {
            !state.onboarded -> Route.Onboarding
            state.locked -> Route.Lock
            backStack.last() == Route.Onboarding || backStack.last() == Route.Lock -> Route.Inbox
            else -> return@LaunchedEffect
        }
        if (backStack.last() != target) backStack.reset(target)
    }

    LaunchedEffect(initialRoute) {
        if (initialRoute != null) backStack.add(initialRoute)
    }

    val folderActions = remember(viewModel) {
        FolderActions(
            onOpen = { backStack.add(Route.Reader(it)) },
            onCompose = { backStack.add(Route.Composer()) },
            onSetRead = viewModel::setRead,
            onTrash = viewModel::moveToTrash,
            onMoveTo = viewModel::moveTo,
            onRestore = viewModel::restoreFromTrash,
            onDeleteForever = viewModel::deleteForever,
            onEmptyTrash = viewModel::emptyTrash,
            onConsumeMessage = viewModel::dismissMessages,
        )
    }

    MainNavigation(
        backStack,
        bottomBar = {
            currentTab(backStack.last(), viewModel.selectedFolder)?.let { tab ->
                MailBottomBar(selected = tab, outboxCount = outbox) { selectTab(it, backStack, viewModel) }
            }
        },
    ) {
        entry<Route.Onboarding> {
            OnboardingScreen(
                state = state,
                onClaim = { localPart, passphrase ->
                    viewModel.claimAddress(localPart, passphrase) {
                        // Protection enrolls immediately after claim: run the
                        // biometric gate as soon as the Lock screen lands.
                        (context as? FragmentActivity)?.let { viewModel.unlock(it) }
                    }
                },
                onDismissMessages = viewModel::dismissMessages,
            )
        }
        entry<Route.Lock> {
            LockScreen(
                state = state,
                onUnlock = {
                    (context as? FragmentActivity)?.let { viewModel.unlock(it) }
                },
                onDismissMessages = viewModel::dismissMessages,
            )
        }
        entry<Route.Inbox>(metadata = SiblingPage()) {
            InboxScreen(
                state = state,
                folder = viewModel.selectedFolder,
                actions = folderActions,
                messages = folderMessages,
            )
        }
        composerEntry(viewModel, backStack)
        entry<Route.Reader> { route ->
            val msg = remember(route.messageId, allMessages, folderMessages) {
                folderMessages.firstOrNull { it.id == route.messageId }
                    ?: allMessages.firstOrNull { it.id == route.messageId }
            }
            LaunchedEffect(route.messageId) {
                viewModel.decryptStored(route.messageId)
                viewModel.markRead(route.messageId)
            }
            ReaderScreen(
                state = state,
                onReply = { m ->
                    backStack.add(
                        Route.Composer(
                            to = m.from,
                            subject = "Re: ${m.decryptedSubject ?: ""}",
                            inReplyTo = m.id,
                        ),
                    )
                },
                onBack = { backStack.pop() },
                message = msg,
            )
        }
        outEntry(viewModel, backStack)
        entry<Route.Settings>(metadata = SiblingPage()) {
            SettingsScreen(
                state = state,
                onExportPublicKey = viewModel::exportPublicKey,
                onForget = viewModel::forgetIdentity,
                onDismissMessages = viewModel::dismissMessages,
            )
        }
    }
}

/**
 * Composer. Leaving with Back saves a draft (or updates the reopened one);
 * Discard drops it; a successful send deletes it.
 */
private fun EntryProviderScope<Route>.composerEntry(viewModel: MailViewModel, backStack: NavBackStack<Route>) {
    entry<Route.Composer> { route ->
        // Stable for this composition so repeated saves update one draft row.
        val draftId = remember(route) { route.draftId ?: Draft().id }
        LaunchedEffect(route) {
            viewModel.seedDraft(
                to = AddressValidator.parseRecipients(route.to),
                cc = AddressValidator.parseRecipients(route.cc),
                bcc = AddressValidator.parseRecipients(route.bcc),
            )
        }
        fun close() {
            viewModel.clearDraft()
            backStack.pop()
        }
        ComposeScreen(
            state = viewModel.state,
            draftTo = viewModel.draftTo,
            draftCc = viewModel.draftCc,
            draftBcc = viewModel.draftBcc,
            initialSubject = route.subject,
            initialBody = route.body,
            onAddRecipient = viewModel::addDraftRecipient,
            onRemoveRecipient = viewModel::removeDraftRecipient,
            onSend = { to, cc, bcc, subject, body, inReplyTo ->
                viewModel.send(
                    to = to,
                    cc = cc,
                    bcc = bcc,
                    subject = subject,
                    body = body,
                    inReplyTo = inReplyTo,
                    onDone = { sent ->
                        if (sent) {
                            viewModel.drafts.delete(draftId)
                            close()
                        }
                    },
                )
            },
            onLeave = { subject, body ->
                saveOrDropDraft(viewModel, draftId, subject, body, route.inReplyTo)
                close()
            },
            onDiscard = {
                viewModel.drafts.delete(draftId)
                close()
            },
            onConsumeMessage = viewModel::dismissMessages,
        )
    }
}

/** Saves the composition as a draft, or removes the draft when nothing was written. */
private fun saveOrDropDraft(
    viewModel: MailViewModel,
    draftId: String,
    subject: String,
    body: String,
    inReplyTo: String?,
) {
    val draft = Draft(
        id = draftId,
        to = viewModel.draftTo,
        cc = viewModel.draftCc,
        bcc = viewModel.draftBcc,
        subject = subject,
        body = body,
        inReplyTo = inReplyTo,
    )
    if (draft.isEmpty) {
        viewModel.drafts.delete(draftId)
    } else {
        viewModel.drafts.save(draft)
        AppMessages.show("Draft saved")
    }
}

/** The Out tab: Drafts, Outbox and Sent on one page. */
private fun EntryProviderScope<Route>.outEntry(viewModel: MailViewModel, backStack: NavBackStack<Route>) {
    entry<Route.Out>(metadata = SiblingPage()) {
        val drafts by viewModel.drafts.drafts.collectAsState(emptyList())
        val sent by viewModel.drafts.sent.collectAsState(emptyList())
        val outbox by viewModel.outboxMessages.collectAsState(emptyList())
        val actions = remember(viewModel) {
            OutActions(
                onCompose = { backStack.add(Route.Composer()) },
                onOpenDraft = { d ->
                    backStack.add(
                        Route.Composer(
                            to = d.to.joinToString(", "),
                            cc = d.cc.joinToString(", "),
                            bcc = d.bcc.joinToString(", "),
                            subject = d.subject,
                            body = d.body,
                            inReplyTo = d.inReplyTo,
                            draftId = d.id,
                        ),
                    )
                },
                onDeleteDraft = viewModel.drafts::delete,
                onRestoreDraft = viewModel.drafts::save,
                onRetryOutbox = viewModel::retryOutbox,
                onOpenSent = { backStack.add(Route.Reader(it)) },
                onTrashSent = viewModel::moveToTrash,
                onUntrashSent = { viewModel.moveTo(it, MailFolder.SENT) },
                onSetRead = viewModel::setRead,
            )
        }
        OutScreen(drafts = drafts, outbox = outbox, sent = sent, actions = actions)
    }
}

/** The bottom-bar tab for the top route, or null when the bar should hide (reader, composer, gates). */
private fun currentTab(top: Route, folder: MailFolder): MailTab? = when (top) {
    Route.Inbox -> folder.tab()
    Route.Out -> MailTab.OUT
    Route.Settings -> MailTab.SETTINGS
    else -> null
}

private fun selectTab(tab: MailTab, backStack: NavBackStack<Route>, viewModel: MailViewModel) {
    val target = when (tab) {
        MailTab.OUT -> Route.Out
        MailTab.SETTINGS -> Route.Settings
        MailTab.INBOX, MailTab.SPAM, MailTab.TRASH -> {
            viewModel.selectFolder(
                when (tab) {
                    MailTab.SPAM -> MailFolder.SPAM
                    MailTab.TRASH -> MailFolder.TRASH
                    else -> MailFolder.INBOX
                },
            )
            Route.Inbox
        }
    }
    if (backStack.last() != target) backStack.reset(target)
}
