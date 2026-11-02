package com.vayunmathur.securemail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import com.vayunmathur.library.ui.DrawerValue
import com.vayunmathur.library.ui.ModalNavigationDrawer
import com.vayunmathur.library.ui.rememberDrawerState
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.securemail.data.MailFolder
import com.vayunmathur.securemail.domain.mail.AddressValidator
import com.vayunmathur.securemail.platform.MailViewModel
import com.vayunmathur.securemail.ui.ComposeScreen
import com.vayunmathur.securemail.ui.InboxScreen
import com.vayunmathur.securemail.ui.LockScreen
import com.vayunmathur.securemail.ui.MailDrawerContent
import com.vayunmathur.securemail.ui.OnboardingScreen
import com.vayunmathur.securemail.ui.OutboxScreen
import com.vayunmathur.securemail.ui.ReaderScreen
import com.vayunmathur.securemail.ui.SettingsScreen
import kotlinx.coroutines.launch

@Composable
fun Navigation(viewModel: MailViewModel, initialRoute: Route? = null) {
    val state = viewModel.state
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val folderMessages by viewModel.folderMessages.collectAsState(emptyList())
    val allMessages by viewModel.messages.collectAsState(emptyList())
    val outbox by viewModel.outboxCount.collectAsState(0)
    val outboxMessages by viewModel.outboxMessages.collectAsState(emptyList())
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

    fun closeDrawerThen(action: () -> Unit) {
        scope.launch { drawerState.close() }
        action()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            MailDrawerContent(
                selectedFolder = viewModel.selectedFolder,
                showingOutbox = backStack.last() == Route.Outbox,
                outboxCount = outbox,
                address = state.address,
                onSelectFolder = { folder ->
                    closeDrawerThen {
                        viewModel.selectFolder(folder)
                        backStack.reset(Route.Inbox)
                    }
                },
                onSelectOutbox = {
                    closeDrawerThen { backStack.add(Route.Outbox) }
                },
            )
        },
    ) {
        MainNavigation(backStack) {
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
            entry<Route.Inbox> {
                InboxScreen(
                    state = state,
                    folder = viewModel.selectedFolder,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onOpen = { backStack.add(Route.Reader(it)) },
                    onCompose = { backStack.add(Route.Composer()) },
                    onSettings = { backStack.add(Route.Settings) },
                    onRefresh = viewModel::refresh,
                    onConsumeMessage = viewModel::dismissMessages,
                    onTrash = viewModel::moveToTrash,
                    onRestore = viewModel::restoreFromTrash,
                    onEmptyTrash = viewModel::emptyTrash,
                    messages = folderMessages,
                )
            }
            entry<Route.Composer> { route ->
                LaunchedEffect(route.to, route.subject, route.body) {
                    viewModel.seedDraft(
                        to = AddressValidator.parseRecipients(route.to),
                    )
                }
                ComposeScreen(
                    state = state,
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
                                    viewModel.clearDraft()
                                    backStack.pop()
                                }
                            },
                        )
                    },
                    onBack = { backStack.pop() },
                    onConsumeMessage = viewModel::dismissMessages,
                )
            }
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
            entry<Route.Outbox> {
                OutboxScreen(
                    state = state,
                    onRetry = viewModel::retryOutbox,
                    onBack = { backStack.pop() },
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onCompose = { backStack.add(Route.Composer()) },
                    onSettings = { backStack.add(Route.Settings) },
                    pending = outboxMessages,
                )
            }
            entry<Route.Settings> {
                SettingsScreen(
                    state = state,
                    onExportPublicKey = viewModel::exportPublicKey,
                    onForget = viewModel::forgetIdentity,
                    onBack = { backStack.pop() },
                    onDismissMessages = viewModel::dismissMessages,
                )
            }
        }
    }
}
