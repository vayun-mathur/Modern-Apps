package com.vayunmathur.communicate.ui

import androidx.compose.runtime.Composable
import com.vayunmathur.communicate.Route
import com.vayunmathur.communicate.data.call.InAppCallRegistry
import com.vayunmathur.communicate.data.rcs.RcsFeature
import com.vayunmathur.communicate.data.signal.SignalFeature
import com.vayunmathur.communicate.ui.call.InAppCallScreen
import com.vayunmathur.communicate.ui.googlevoice.GoogleVoiceSignInScreen
import com.vayunmathur.communicate.ui.rcs.RcsRegistrationScreen
import com.vayunmathur.communicate.ui.signal.SignalRegistrationCaptchaScreen
import com.vayunmathur.communicate.ui.signal.SignalRegistrationCodeScreen
import com.vayunmathur.communicate.ui.signal.SignalRegistrationDoneScreen
import com.vayunmathur.communicate.ui.signal.SignalRegistrationNumberScreen
import com.vayunmathur.communicate.ui.whatsapp.WhatsAppRegistrationCodeScreen
import com.vayunmathur.communicate.ui.whatsapp.WhatsAppRegistrationDoneScreen
import com.vayunmathur.communicate.ui.whatsapp.WhatsAppRegistrationNumberScreen
import com.vayunmathur.communicate.ui.whatsapp.WhatsAppRegistrationPinScreen
import com.vayunmathur.library.util.EntryProviderScope
import com.vayunmathur.library.util.FullscreenPage
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.MorphPage
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.SiblingPage

@Composable
fun CommunicateNavGraph(backStack: NavBackStack<Route>) {
    MainNavigation(backStack) {
        coreEntries(backStack)
        registrationEntries(backStack)
        conversationEntries(backStack)
        callEntries(backStack)
    }
}

fun EntryProviderScope<Route>.coreEntries(backStack: NavBackStack<Route>) {
    entry<Route.Messages>(metadata = ListPage() + SiblingPage()) {
        MessagesScreen(
            onOpenThread = { thread ->
                // A picker share (regular share target, no conversation chosen)
                // seeds the first thread opened after it; consume so later opens are plain.
                val pendingShare = com.vayunmathur.communicate.intents.PendingShare.consume()
                val route = Route.Conversation(
                    threadId = thread.threadId,
                    address = thread.address,
                    line = thread.line,
                    remoteId = thread.remoteId,
                    subscriptionId = thread.subscriptionId,
                    isGroup = thread.isGroup,
                    participants = thread.participants,
                    groupTitle = thread.groupTitle,
                    sharedText = pendingShare?.body,
                    sharedAttachmentUris = pendingShare?.sharedAttachmentUris.orEmpty(),
                )
                // Re-tapping another thread replaces the detail instead of stacking
                // Conversation on Conversation.
                if (backStack.last() is Route.Conversation) backStack.setLast(route)
                else backStack.add(route)
            },
            onOpenAccounts = { backStack.add(Route.Accounts) },
        )
    }
    entry<Route.Dialer>(metadata = SiblingPage()) {
        DialerScreen()
    }
    entry<Route.CallLogs>(metadata = SiblingPage()) {
        CallLogsScreen()
    }
    entry<Route.Accounts>(metadata = ListDetailPage()) {
        AccountsScreen(
            onBack = { backStack.pop() },
            onSignIn = { backStack.add(Route.GoogleVoiceSignIn) },
            onRegisterWhatsApp = {
                if (com.vayunmathur.communicate.data.whatsapp.WhatsAppFeature.enabled) {
                    backStack.add(Route.WhatsAppRegistration.Number)
                }
            },
            onImportBackup = {
                if (com.vayunmathur.communicate.data.whatsapp.WhatsAppFeature.enabled) {
                    backStack.add(Route.WhatsAppBackupImport)
                }
            },
            onRegisterSignal = {
                if (SignalFeature.enabled) {
                    backStack.add(Route.SignalRegistration.Number)
                }
            },
            onShowRcsStatus = {
                if (RcsFeature.enabled) {
                    backStack.add(Route.RcsStatus)
                }
            },
        )
    }
}

/**
 * Replaces the WhatsApp registration steps on the stack with Done.
 *
 * Done is the terminal step: leaving it must exit registration (as the
 * single-screen flow's Done/Back buttons did), not step back into the
 * code/PIN steps for an already-registered number.
 */
internal fun NavBackStack<Route>.showWhatsAppRegistrationDone(cc: String, number: String) {
    while (backStack.lastOrNull() is Route.WhatsAppRegistration) pop()
    add(Route.WhatsAppRegistration.Done(cc, number))
}

/** Replaces the Signal registration steps on the stack with Done; see [showWhatsAppRegistrationDone]. */
internal fun NavBackStack<Route>.showSignalRegistrationDone(cc: String, number: String) {
    while (backStack.lastOrNull() is Route.SignalRegistration) pop()
    add(Route.SignalRegistration.Done(cc, number))
}

fun EntryProviderScope<Route>.registrationEntries(backStack: NavBackStack<Route>) {
    entry<Route.GoogleVoiceSignIn>(metadata = ListDetailPage()) {
        GoogleVoiceSignInScreen(
            onBack = { backStack.pop() },
            onSignedIn = { backStack.pop() },
        )
    }
    entry<Route.WhatsAppRegistration.Number>(metadata = ListDetailPage()) {
        WhatsAppRegistrationNumberScreen(
            onBack = { backStack.pop() },
            onCodeRequested = { cc, number ->
                backStack.add(Route.WhatsAppRegistration.Code(cc, number))
            },
        )
    }
    entry<Route.WhatsAppRegistration.Code>(metadata = ListDetailPage()) { route ->
        WhatsAppRegistrationCodeScreen(
            cc = route.cc,
            number = route.number,
            onBack = { backStack.pop() },
            onPinRequired = { cc, number ->
                backStack.add(Route.WhatsAppRegistration.Pin(cc, number))
            },
            onVerified = { cc, number -> backStack.showWhatsAppRegistrationDone(cc, number) },
        )
    }
    entry<Route.WhatsAppRegistration.Pin>(metadata = ListDetailPage()) { route ->
        WhatsAppRegistrationPinScreen(
            cc = route.cc,
            number = route.number,
            onBack = { backStack.pop() },
            onVerified = { cc, number -> backStack.showWhatsAppRegistrationDone(cc, number) },
        )
    }
    entry<Route.WhatsAppRegistration.Done>(metadata = ListDetailPage()) {
        WhatsAppRegistrationDoneScreen(
            onBack = { backStack.pop() },
            onRegistered = { backStack.pop() },
        )
    }
    entry<Route.WhatsAppBackupImport>(metadata = ListDetailPage()) {
        com.vayunmathur.communicate.ui.whatsapp.WhatsAppBackupImportScreen(
            onBack = { backStack.pop() },
        )
    }
    entry<Route.SignalRegistration.Number>(metadata = ListDetailPage()) {
        SignalRegistrationNumberScreen(
            onBack = { backStack.pop() },
            onCaptchaRequired = { cc, number, transport ->
                backStack.add(Route.SignalRegistration.Captcha(cc, number, transport))
            },
            onCodeRequested = { cc, number ->
                backStack.add(Route.SignalRegistration.Code(cc, number))
            },
        )
    }
    entry<Route.SignalRegistration.Captcha>(metadata = ListDetailPage()) { route ->
        SignalRegistrationCaptchaScreen(
            cc = route.cc,
            number = route.number,
            transport = route.transport,
            onBack = { backStack.pop() },
            // Replace (not push) the captcha, so Code's Back/Change-number
            // lands on Number as the single-screen flow's buttons did.
            onCodeRequested = { cc, number ->
                backStack.setLast(Route.SignalRegistration.Code(cc, number))
            },
            onCaptchaRejected = { backStack.pop() },
        )
    }
    entry<Route.SignalRegistration.Code>(metadata = ListDetailPage()) { route ->
        SignalRegistrationCodeScreen(
            cc = route.cc,
            number = route.number,
            onBack = { backStack.pop() },
            onVerified = { cc, number -> backStack.showSignalRegistrationDone(cc, number) },
        )
    }
    entry<Route.SignalRegistration.Done>(metadata = ListDetailPage()) {
        SignalRegistrationDoneScreen(
            onBack = { backStack.pop() },
            onRegistered = { backStack.pop() },
        )
    }
    entry<Route.RcsStatus>(metadata = ListDetailPage()) {
        RcsRegistrationScreen(
            onBack = { backStack.pop() },
        )
    }
}

fun EntryProviderScope<Route>.conversationEntries(backStack: NavBackStack<Route>) {
    entry<Route.RcsVerify>(metadata = ListDetailPage()) { route ->
        com.vayunmathur.communicate.ui.rcs.RcsVerifyScreen(
            peerE164 = route.peerE164,
            onBack = { backStack.pop() },
        )
    }
    entry<Route.RcsGroupMembers>(metadata = ListDetailPage()) { route ->
        com.vayunmathur.communicate.ui.rcs.RcsGroupMembersScreen(
            conversationId = route.conversationId,
            onBack = { backStack.pop() },
        )
    }
    entry<Route.Conversation>(metadata = ListDetailPage() + MorphPage()) { route ->
        ConversationScreen(
            threadId = route.threadId,
            address = route.address,
            line = route.line,
            remoteId = route.remoteId,
            subscriptionId = route.subscriptionId,
            isGroup = route.isGroup,
            participants = route.participants,
            groupTitle = route.groupTitle,
            initialDraft = route.sharedText.orEmpty(),
            initialAttachmentUris = route.sharedAttachmentUris,
            onBack = { backStack.pop() },
            onOpenRcsVerify = { peer -> backStack.add(Route.RcsVerify(peer)) },
            onOpenRcsGroupMembers = { cid -> backStack.add(Route.RcsGroupMembers(cid)) },
        )
    }
}

/**
 * One call screen for every in-app line. Google Voice, WhatsApp and Signal all publish into
 * InAppCallRegistry, and the screen renders whatever the active line supports. The phase-to-controls
 * choice stays inside [InAppCallScreen]; only the screen's presence is navigation, synced by
 * [CallRouteEffect]. Closing is the stack pop back to Idle already performs, so the screen's
 * [InAppCallScreen.onClose] only clears the terminal registry state.
 */
fun EntryProviderScope<Route>.callEntries(backStack: NavBackStack<Route>) {
    entry<Route.Call>(metadata = FullscreenPage()) {
        InAppCallScreen(
            onClose = {
                InAppCallRegistry.clearEnded()
                // If the registry is already Idle the sync pop may have run;
                // only pop while the call destination is actually on top.
                if (backStack.backStack.lastOrNull() is Route.Call) backStack.pop()
            },
        )
    }
}
