package com.vayunmathur.communicate

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Rational
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.ui.IconCall
import com.vayunmathur.library.ui.IconHistory
import com.vayunmathur.library.ui.IconSms
import com.vayunmathur.library.ui.RouteTab
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.library.util.FullscreenPage
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.MorphPage
import com.vayunmathur.library.util.SiblingPage
import com.vayunmathur.library.util.EntryProviderScope
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceSession
import com.vayunmathur.communicate.data.googlevoice.call.CallPhase
import com.vayunmathur.communicate.data.googlevoice.call.GoogleVoiceCallBridge
import com.vayunmathur.communicate.data.googlevoice.call.GoogleVoiceCallManager
import com.vayunmathur.communicate.telephony.GoogleVoiceSyncService
import com.vayunmathur.communicate.telephony.GoogleVoiceTelecom
import com.vayunmathur.communicate.ui.AccountsScreen
import com.vayunmathur.communicate.ui.CallLogsScreen
import com.vayunmathur.communicate.ui.ConversationScreen
import com.vayunmathur.communicate.ui.DialerScreen
import com.vayunmathur.communicate.ui.MessagesScreen
import com.vayunmathur.communicate.ui.VoicemailScreen
import com.vayunmathur.communicate.data.call.InAppCallPhase
import com.vayunmathur.communicate.data.call.InAppCallRegistry
import com.vayunmathur.communicate.data.whatsapp.call.WhatsAppCallBridge
import com.vayunmathur.communicate.telephony.InAppCallTelecom
import com.vayunmathur.communicate.ui.call.InAppCallScreen
import com.vayunmathur.communicate.ui.googlevoice.GoogleVoiceSignInScreen
import com.vayunmathur.communicate.ui.whatsapp.WhatsAppRegistrationCodeScreen
import com.vayunmathur.communicate.ui.whatsapp.WhatsAppRegistrationDoneScreen
import com.vayunmathur.communicate.ui.whatsapp.WhatsAppRegistrationNumberScreen
import com.vayunmathur.communicate.ui.whatsapp.WhatsAppRegistrationPinScreen
import com.vayunmathur.communicate.telephony.WhatsAppSyncService
import com.vayunmathur.communicate.data.whatsapp.WhatsAppLineSession
import com.vayunmathur.communicate.data.signal.SignalFeature
import com.vayunmathur.communicate.data.signal.SignalLineSession
import com.vayunmathur.communicate.telephony.SignalSyncService
import com.vayunmathur.communicate.data.rcs.RcsFeature
import com.vayunmathur.communicate.data.rcs.RcsRegistrationState
import com.vayunmathur.communicate.data.rcs.RcsSipTransport
import com.vayunmathur.communicate.telephony.RcsSyncService
import com.vayunmathur.communicate.ui.rcs.RcsRegistrationScreen
import com.vayunmathur.communicate.ui.signal.SignalRegistrationCaptchaScreen
import com.vayunmathur.communicate.ui.signal.SignalRegistrationCodeScreen
import com.vayunmathur.communicate.ui.signal.SignalRegistrationDoneScreen
import com.vayunmathur.communicate.ui.signal.SignalRegistrationNumberScreen
import androidx.compose.ui.platform.LocalContext
import kotlinx.serialization.Serializable

/** Navigation routes for the communicate app (type-safe back stack keys). */
sealed interface Route : NavKey {
    /** The conversation list tab (home). Sibling of [Dialer] and [CallLogs]. */
    @Serializable data object Messages : Route
    /** The dialer tab. Sibling of [Messages] and [CallLogs]. */
    @Serializable data object Dialer : Route
    /** The call-logs tab. Sibling of [Messages] and [Dialer]. */
    @Serializable data object CallLogs : Route
    /** The accounts / line management route. */
    @Serializable data object Accounts : Route
    /** The Google Voice sign-in route. */
    @Serializable data object GoogleVoiceSignIn : Route
    /**
     * The WhatsApp phone-number registration wizard: one sub-destination
     * per step, pushed in flow order. [Pin] is conditional (only numbers
     * with two-step verification reach it), and reaching [Done] replaces
     * the whole sub-stack so leaving Done exits registration. The entered
     * country code and number travel in the step arguments.
     */
    @Serializable
    sealed interface WhatsAppRegistration : Route {
        /** Enter the number and request an OTP. */
        @Serializable data object Number : WhatsAppRegistration
        /** Enter the OTP for [number]. */
        @Serializable data class Code(val cc: String, val number: String) : WhatsAppRegistration
        /** Enter the two-step PIN for [number] (conditional on the Code result). */
        @Serializable data class Pin(val cc: String, val number: String) : WhatsAppRegistration
        /** Registration succeeded for [number]. */
        @Serializable data class Done(val cc: String, val number: String) : WhatsAppRegistration
    }
    /** The WhatsApp backup import route. */
    @Serializable data object WhatsAppBackupImport : Route
    /**
     * The Signal phone-number registration wizard: one sub-destination
     * per step, pushed in flow order. [Captcha] is conditional (only
     * challenged numbers reach it, carrying the transport to resume),
     * and reaching [Done] replaces the whole sub-stack so leaving Done
     * exits registration. The entered country code and number travel in
     * the step arguments.
     */
    @Serializable
    sealed interface SignalRegistration : Route {
        /** Enter the number and request an OTP. */
        @Serializable data object Number : SignalRegistration
        /** Solve the captcha for [number] and resume requesting via [transport]. */
        @Serializable data class Captcha(
            val cc: String,
            val number: String,
            val transport: String,
        ) : SignalRegistration
        /** Enter the OTP for [number]. */
        @Serializable data class Code(val cc: String, val number: String) : SignalRegistration
        /** Registration succeeded for [number]. */
        @Serializable data class Done(val cc: String, val number: String) : SignalRegistration
    }

    /** The RCS status / provisioning route. */
    @Serializable data object RcsStatus : Route

    /** The RCS safety-number verification route for one peer. */
    @Serializable
    data class RcsVerify(val peerE164: String) : Route

    /** The RCS group member list route. */
    @Serializable
    data class RcsGroupMembers(val conversationId: String) : Route

    /**
     * The full-screen in-app call. Pushed by the call-route sync while
     * [InAppCallRegistry] reports a phase other than Idle, and popped when
     * the call ends: the registry is the session engine, this destination
     * is only its screen. It carries no arguments because the live call
     * state (peer, capabilities, media) changes under it and is read from
     * the registry inside the call screen.
     */
    @Serializable data object Call : Route

    /** One open conversation: its thread, peer address and line. */
    @Serializable
    data class Conversation(
        val threadId: Long,
        val address: String,
        // Serialized as the enum name; defaults keep older back-stack entries valid.
        val line: CommunicateLine = CommunicateLine.Sim,
        val remoteId: String? = null,
        val subscriptionId: Int? = null,
        val isGroup: Boolean = false,
        val participants: List<String> = emptyList(),
        val groupTitle: String? = null,
    ) : Route
}

/** The single activity hosting the communicate UI: navigation, deep links and line setup. */
class MainActivity : MaActivity() {
    /** The deep link the app was opened with, or the most recent one delivered to a running instance. */
    private var pendingDeepLink by mutableStateOf<DeepLink?>(null)

    override fun onCreateBeforeContent() {
        pendingDeepLink = DeepLinks.parse(intent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS)
        }
    }

    @Composable
    override fun Content() {
        CommunicateApp(initialDeepLink = pendingDeepLink)
    }

    /**
     * A deep link that arrived while the activity was already running. Held in state so the composition picks
     * it up, rather than being lost — the app declared `sms:`/`tel:` filters for a long time and ignored them.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDeepLink = DeepLinks.parse(intent)
    }

    /**
     * Shrink a video call into picture-in-picture when leaving the app, so the far end stays visible.
     *
     * Only for video: an audio call has nothing to show, and the ongoing-call notification already covers it.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val call = InAppCallRegistry.state.value
        val videoActive = call.remoteVideoEnabled || call.localVideoEnabled
        if (!videoActive || call.phase == InAppCallPhase.Idle || call.phase == InAppCallPhase.Ended) return
        runCatching {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(PORTRAIT_WIDTH, PORTRAIT_HEIGHT))
                    .build(),
            )
        }
    }

    /** Notification-permission request code and splash aspect-ratio constants. */
    companion object {
        private const val REQUEST_POST_NOTIFICATIONS = 1001
        private const val PORTRAIT_WIDTH = 9
        private const val PORTRAIT_HEIGHT = 16
    }
}

@Composable
private fun CommunicateApp(initialDeepLink: DeepLink? = null) {
    val context = LocalContext.current
    val session = remember { GoogleVoiceSession.get(context) }
    val gvSignedIn by session.signedInFlow.collectAsState(initial = false)
    val waSession = remember { WhatsAppLineSession.get(context) }
    val waSignedIn by waSession.signedInFlow.collectAsState(initial = false)
    val sigSession = remember { SignalLineSession.get(context) }
    val sigSignedIn by sigSession.signedInFlow.collectAsState(initial = false)

    AppSyncEffects(context, waSignedIn, sigSignedIn, gvSignedIn, session)
    AppTelecomEffects(context, waSignedIn, sigSignedIn)

    val backStack = rememberNavBackStack<Route>(Route.Messages)

    DeepLinkEffect(backStack, initialDeepLink)
    CallRouteEffect(backStack)
    CommunicateTabs(backStack)
}

/** Deep links open the conversation they name, including links arriving at a running instance. */
@Composable
private fun DeepLinkEffect(
    backStack: NavBackStack<Route>,
    initialDeepLink: DeepLink?,
) {
    val context = LocalContext.current
    // Keyed on the link so a second link to a running instance navigates again rather than ignored.
    LaunchedEffect(initialDeepLink) {
        when (val link = initialDeepLink) {
            is DeepLink.Conversation -> backStack.add(
                Route.Conversation(
                    threadId = link.threadId,
                    address = link.address,
                    line = link.line,
                    remoteId = link.remoteId,
                    subscriptionId = link.subscriptionId,
                    isGroup = link.isGroup,
                    groupTitle = link.groupTitle,
                ),
            )
            is DeepLink.UnsupportedGroupInvite ->
                com.vayunmathur.library.util.AppMessages.show(
                    context.getString(R.string.deep_link_group_unsupported),
                )
            null -> Unit
        }
    }
}

/**
 * Keeps [Route.Call] on top of the stack exactly while a call is live.
 *
 * [InAppCallRegistry] stays the session engine: lines bind, ring, connect
 * and end without touching navigation, and this effect projects its phase
 * onto the stack. Leaving Idle pushes the call screen over whatever the
 * user was doing; returning to Idle pops it and reveals that screen again.
 * A back press that pops the call screen mid-call is undone by the same
 * projection (the phase is still live), so the call UI cannot be lost
 * while the call itself continues.
 */
@Composable
private fun CallRouteEffect(backStack: NavBackStack<Route>) {
    val inAppCallState by InAppCallRegistry.state.collectAsState()
    val top = backStack.backStack.lastOrNull()
    LaunchedEffect(inAppCallState.phase, top) {
        syncCallRoute(backStack, inAppCallState.phase)
    }
}

/**
 * The phase-to-stack projection behind [CallRouteEffect], kept free of
 * composition so unit tests can drive it with a plain [NavBackStack].
 *
 * Idempotent: a live phase with the call already on top, and Idle with no
 * call on top, both leave the stack untouched.
 */
internal fun syncCallRoute(backStack: NavBackStack<Route>, phase: InAppCallPhase) {
    val top = backStack.backStack.lastOrNull()
    if (phase != InAppCallPhase.Idle) {
        when {
            top is Route.Call -> Unit
            // A deep link pushed over the call: bring the existing call
            // destination back on top instead of stacking a second one.
            backStack.backStack.any { it is Route.Call } -> backStack.reset(
                *backStack.backStack.filterNot { it is Route.Call }.toTypedArray(),
                Route.Call,
            )
            else -> backStack.add(Route.Call)
        }
    } else if (top is Route.Call) {
        backStack.pop()
    }
}

@Composable
private fun CommunicateNavGraph(backStack: NavBackStack<Route>) {
    MainNavigation(backStack) {
        coreEntries(backStack)
        registrationEntries(backStack)
        conversationEntries(backStack)
        callEntries(backStack)
    }
}

private fun EntryProviderScope<Route>.coreEntries(backStack: NavBackStack<Route>) {
    entry<Route.Messages>(metadata = ListPage() + SiblingPage()) {
        MessagesScreen(
            onOpenThread = { thread ->
                val route = Route.Conversation(
                    threadId = thread.threadId,
                    address = thread.address,
                    line = thread.line,
                    remoteId = thread.remoteId,
                    subscriptionId = thread.subscriptionId,
                    isGroup = thread.isGroup,
                    participants = thread.participants,
                    groupTitle = thread.groupTitle,
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

internal fun EntryProviderScope<Route>.registrationEntries(backStack: NavBackStack<Route>) {
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

private fun EntryProviderScope<Route>.conversationEntries(backStack: NavBackStack<Route>) {
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
private fun EntryProviderScope<Route>.callEntries(backStack: NavBackStack<Route>) {
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

/** Sync-service effects for all lines. */
@Composable
private fun AppSyncEffects(
    context: android.content.Context,
    waSignedIn: Boolean,
    sigSignedIn: Boolean,
    gvSignedIn: Boolean,
    session: GoogleVoiceSession,
) {
    // Own the WhatsApp always-on receive state via its foreground sync service (dev-only).
    LaunchedEffect(waSignedIn) {
        if (!com.vayunmathur.communicate.data.whatsapp.WhatsAppFeature.enabled) return@LaunchedEffect
        if (waSignedIn) WhatsAppSyncService.start(context) else WhatsAppSyncService.stop(context)
    }

    // Own the Signal always-on receive state via its foreground sync service (dev-only).
    LaunchedEffect(sigSignedIn) {
        if (!SignalFeature.enabled) return@LaunchedEffect
        if (sigSignedIn) SignalSyncService.start(context) else SignalSyncService.stop(context)
    }

    // Own the RCS always-on receive state via its foreground sync service (dev-only).
    // Provisioning IS the session: Available owns the service, anything else stops it.
    val rcsState by RcsSipTransport.state.collectAsState(initial = RcsRegistrationState.Unknown)
    LaunchedEffect(rcsState) {
        if (!RcsFeature.enabled) return@LaunchedEffect
        if (rcsState is RcsRegistrationState.Available) RcsSyncService.start(context)
        else RcsSyncService.stop(context)
    }

    // Keep the Telecom account fresh, but let the foreground service own always-on receive state.
    LaunchedEffect(gvSignedIn) {
        GoogleVoiceCallManager.init(context)
        GoogleVoiceCallManager.onIncomingCall = { from -> GoogleVoiceTelecom.addIncoming(context, from) }
        // Projects Google Voice call state onto the shared call screen.
        GoogleVoiceCallBridge.ensureStarted()
        if (gvSignedIn) {
            val number = session.phoneNumber() ?: context.getString(R.string.account_google_voice)
            GoogleVoiceTelecom.registerPhoneAccount(context, number)
            GoogleVoiceSyncService.start(context)
        } else {
            GoogleVoiceSyncService.stop(context)
        }
    }
}

/** Telecom registration effects for all lines. */
@Composable
private fun AppTelecomEffects(
    context: android.content.Context,
    waSignedIn: Boolean,
    sigSignedIn: Boolean,
) {
    // WhatsApp and Signal calls share one Telecom account and one call screen. Registered whenever either
    // line is usable, so the system owns ringing and audio routing rather than the app approximating it.
    LaunchedEffect(waSignedIn, sigSignedIn) {
        val anyLine = (com.vayunmathur.communicate.data.whatsapp.WhatsAppFeature.enabled && waSignedIn) ||
            (SignalFeature.enabled && sigSignedIn)
        if (anyLine) {
            InAppCallTelecom.registerPhoneAccount(context, context.getString(R.string.app_name))
            if (com.vayunmathur.communicate.data.whatsapp.WhatsAppFeature.enabled) {
                WhatsAppCallBridge.ensureStarted(context)
            }
        } else {
            InAppCallTelecom.unregisterPhoneAccount(context)
        }
    }
}

/**
 * Route-backed tab host for the three top-level tabs ([Route.Messages],
 * [Route.Dialer], [Route.CallLogs]).
 *
 * The tabs are sibling routes: [TabbedPagerScaffold] selects one by
 * `backStack.reset(tab.route)` (see its [RouteTab] overload), and each tab's
 * screen is the `SiblingPage()` entry for that route in [CommunicateNavGraph],
 * hosted inside the scaffold's content slot. A destination pushed over a tab
 * (a conversation, accounts, a registration flow) renders in the same slot
 * with no tab selected, and picking a tab resets back to that tab.
 * There is no pager: the old host declared `pageCount = { 4 }` for only
 * three tabs, and tab selection lived in pager state instead of the stack.
 */
@Composable
private fun CommunicateTabs(backStack: NavBackStack<Route>) {
    val tabs = listOf<RouteTab<Route>>(
        RouteTab(Route.Messages, stringResource(R.string.messages_title), { IconSms() }),
        RouteTab(Route.Dialer, stringResource(R.string.dialer_title), { IconCall() }),
        RouteTab(Route.CallLogs, stringResource(R.string.call_logs_title), { IconHistory() }),
    )
    TabbedPagerScaffold(backStack = backStack, tabs = tabs) {
        CommunicateNavGraph(backStack)
    }
}
