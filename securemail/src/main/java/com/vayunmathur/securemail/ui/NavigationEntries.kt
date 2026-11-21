package com.vayunmathur.securemail.ui
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.library.util.EntryProviderScope
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.SiblingPage
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.securemail.data.MailFolder
import com.vayunmathur.securemail.platform.MailUiState
import com.vayunmathur.securemail.domain.mail.AddressValidator
import com.vayunmathur.securemail.platform.Draft
import com.vayunmathur.securemail.platform.MailViewModel
import com.vayunmathur.securemail.platform.deleteForever
import com.vayunmathur.securemail.platform.markRead
import com.vayunmathur.securemail.platform.moveTo
import com.vayunmathur.securemail.platform.moveToTrash
import com.vayunmathur.securemail.platform.restoreFromTrash
import com.vayunmathur.securemail.platform.setRead
import com.vayunmathur.securemail.R
import com.vayunmathur.securemail.Route

/** Onboarding, lock, inbox, reader and settings destinations. */
internal fun EntryProviderScope<Route>.mainEntries(
    viewModel: MailViewModel,
    backStack: NavBackStack<Route>,
    state: MailUiState,
    folderActions: FolderActions,
    context: android.content.Context,
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
    entry<Route.Inbox>(metadata = ListPage() + SiblingPage()) { route ->
        val folderFlow = remember(route.folder) { viewModel.folderMessages(route.folder) }
        val folderMessages by folderFlow.collectAsState(emptyList())
        InboxScreen(
            state = state,
            folder = route.folder,
            actions = folderActions,
            messages = folderMessages,
        )
    }
    readerEntry(viewModel, backStack, state)
    entry<Route.Settings>(metadata = SiblingPage()) {
        val identities by viewModel.identities.collectAsState(emptyList())
        SettingsScreen(
            state = state,
            identities = identities,
            onCreateAlias = viewModel::createAlias,
            onSetAliasDisabled = viewModel::setAliasDisabled,
            onExportPublicKey = viewModel::exportPublicKey,
            onForget = viewModel::forgetIdentity,
            onDismissMessages = viewModel::dismissMessages,
        )
    }
}


/** Reader destination: decrypts and marks read on entry. */
internal fun EntryProviderScope<Route>.readerEntry(
    viewModel: MailViewModel,
    backStack: NavBackStack<Route>,
    state: MailUiState,
) {
entry<Route.Reader>(metadata = ListDetailPage()) { route ->
    val context = LocalContext.current
    val msgFlow = remember(route.messageId) { viewModel.messageFlow(route.messageId) }
    val msg by msgFlow.collectAsState(null)
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
                    subject = context.getString(R.string.reply_subject_format, m.decryptedSubject ?: ""),
                    inReplyTo = m.id,
                    from = m.deliveredTo,
                ),
            )
        },
        onBack = { backStack.pop() },
        message = msg,
    )
}
}
/**
 * Composer. Leaving with Back saves a draft (or updates the reopened one);
 * Discard drops it; a successful send deletes it.
 */
internal fun EntryProviderScope<Route>.composerEntry(viewModel: MailViewModel, backStack: NavBackStack<Route>) {
    entry<Route.Composer> { route ->
        // Stable for this composition so repeated saves update one draft row.
        val draftId = remember(route) { route.draftId ?: Draft().id }
        LaunchedEffect(route) {
            viewModel.seedDraft(
                to = AddressValidator.parseRecipients(route.to),
                cc = AddressValidator.parseRecipients(route.cc),
                bcc = AddressValidator.parseRecipients(route.bcc),
                from = route.from,
            )
        }
        val draftSaved = stringResource(R.string.draft_saved)
        fun close() {
            viewModel.clearDraft()
            backStack.pop()
        }
        val identities by viewModel.identities.collectAsState(emptyList())
        ComposeScreen(
            state = viewModel.state,
            identities = identities.filter { !it.disabled },
            draftFrom = viewModel.draftFrom,
            onSelectFrom = { viewModel.draftFrom = it },
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
                    from = viewModel.draftFrom,
                    onDone = { sent ->
                        if (sent) {
                            viewModel.drafts.delete(draftId)
                            close()
                        }
                    },
                )
            },
            onLeave = { subject, body ->
                saveOrDropDraft(viewModel, draftId, subject, body, route.inReplyTo, draftSaved)
                close()
            },
            onAutosave = { subject, body ->
                saveOrDropDraft(viewModel, draftId, subject, body, route.inReplyTo, draftSaved, notify = false)
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
internal fun saveOrDropDraft(
    viewModel: MailViewModel,
    draftId: String,
    subject: String,
    body: String,
    inReplyTo: String?,
    draftSaved: String,
    notify: Boolean = true,
) {
    val draft = Draft(
        id = draftId,
        to = viewModel.draftTo,
        cc = viewModel.draftCc,
        bcc = viewModel.draftBcc,
        subject = subject,
        body = body,
        inReplyTo = inReplyTo,
        from = viewModel.draftFrom,
    )
    if (draft.isEmpty) {
        viewModel.drafts.delete(draftId)
    } else {
        viewModel.drafts.save(draft)
        if (notify) AppMessages.show(draftSaved)
    }
}

/** The Out tab: Drafts, Outbox and Sent on one page. */
internal fun EntryProviderScope<Route>.outEntry(viewModel: MailViewModel, backStack: NavBackStack<Route>) {
    entry<Route.Out>(metadata = SiblingPage()) {
        val drafts by viewModel.drafts.drafts.collectAsState(emptyList())
        val sent by viewModel.drafts.sent.collectAsState(emptyList())
        val outbox by viewModel.outboxMessages.collectAsState(emptyList())
        val actions = remember(viewModel, backStack) {
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
                            from = d.from,
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
internal fun currentTab(top: Route): MailTab? = when (top) {
    is Route.Inbox -> top.folder.tab()
    Route.Out -> MailTab.OUT
    Route.Settings -> MailTab.SETTINGS
    else -> null
}

internal fun selectTab(tab: MailTab, backStack: NavBackStack<Route>) {
    val target: Route = when (tab) {
        MailTab.OUT -> Route.Out
        MailTab.SETTINGS -> Route.Settings
        MailTab.INBOX -> Route.Inbox(MailFolder.INBOX)
        MailTab.SPAM -> Route.Inbox(MailFolder.SPAM)
        MailTab.TRASH -> Route.Inbox(MailFolder.TRASH)
    }
    if (backStack.last() != target) backStack.reset(target)
}
