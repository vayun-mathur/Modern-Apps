package com.vayunmathur.communicate

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Rational
import androidx.compose.foundation.pager.rememberPagerState
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
import com.vayunmathur.library.ui.PagerTab
import com.vayunmathur.library.ui.TabStyle
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.MorphPage
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
import com.vayunmathur.communicate.ui.whatsapp.WhatsAppRegistrationScreen
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
import com.vayunmathur.communicate.ui.signal.SignalRegistrationScreen
import androidx.compose.ui.platform.LocalContext
import kotlinx.serialization.Serializable

sealed interface Route : NavKey {
    @Serializable data object Main : Route
    @Serializable data object Accounts : Route
    @Serializable data object GoogleVoiceSignIn : Route
    @Serializable data object WhatsAppRegistration : Route
    @Serializable data object WhatsAppBackupImport : Route
    @Serializable data object SignalRegistration : Route

    @Serializable data object RcsStatus : Route

    @Serializable
    data class RcsVerify(val peerE164: String) : Route

    @Serializable
    data class RcsGroupMembers(val conversationId: String) : Route

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

    val backStack = rememberNavBackStack<Route>(Route.Main)

    DeepLinkEffect(backStack, initialDeepLink)
    CommunicateNavGraph(backStack)
    InAppCallOverlay()
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

@Composable
private fun CommunicateNavGraph(backStack: NavBackStack<Route>) {
    MainNavigation(backStack) {
        coreEntries(backStack)
        registrationEntries(backStack)
        conversationEntries(backStack)
    }
}

private fun EntryProviderScope<Route>.coreEntries(backStack: NavBackStack<Route>) {
    entry<Route.Main>(metadata = ListPage()) {
        CommunicateTabs(backStack)
    }
    entry<Route.Accounts>(metadata = ListDetailPage()) {
        AccountsScreen(
            onBack = { backStack.pop() },
            onSignIn = { backStack.add(Route.GoogleVoiceSignIn) },
            onRegisterWhatsApp = {
                if (com.vayunmathur.communicate.data.whatsapp.WhatsAppFeature.enabled) {
                    backStack.add(Route.WhatsAppRegistration)
                }
            },
            onImportBackup = {
                if (com.vayunmathur.communicate.data.whatsapp.WhatsAppFeature.enabled) {
                    backStack.add(Route.WhatsAppBackupImport)
                }
            },
            onRegisterSignal = {
                if (SignalFeature.enabled) {
                    backStack.add(Route.SignalRegistration)
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

private fun EntryProviderScope<Route>.registrationEntries(backStack: NavBackStack<Route>) {
    entry<Route.GoogleVoiceSignIn>(metadata = ListDetailPage()) {
        GoogleVoiceSignInScreen(
            onBack = { backStack.pop() },
            onSignedIn = { backStack.pop() },
        )
    }
    entry<Route.WhatsAppRegistration>(metadata = ListDetailPage()) {
        WhatsAppRegistrationScreen(
            onBack = { backStack.pop() },
            onRegistered = { backStack.pop() },
        )
    }
    entry<Route.WhatsAppBackupImport>(metadata = ListDetailPage()) {
        com.vayunmathur.communicate.ui.whatsapp.WhatsAppBackupImportScreen(
            onBack = { backStack.pop() },
        )
    }
    entry<Route.SignalRegistration>(metadata = ListDetailPage()) {
        SignalRegistrationScreen(
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
 * InAppCallRegistry, and the screen renders whatever the active line supports.
 */
@Composable
private fun InAppCallOverlay() {
    val inAppCallState by InAppCallRegistry.state.collectAsState()
    if (inAppCallState.phase != InAppCallPhase.Idle) {
        InAppCallScreen(onClose = { InAppCallRegistry.clearEnded() })
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

@Composable
private fun CommunicateTabs(backStack: NavBackStack<Route>) {
    val pagerState = rememberPagerState(pageCount = { 4 })
    val tabs = listOf(
        PagerTab(stringResource(R.string.messages_title), { IconSms() }) {
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
        },
        PagerTab(stringResource(R.string.dialer_title), { IconCall() }) { DialerScreen() },
        PagerTab(stringResource(R.string.call_logs_title), { IconHistory() }) { CallLogsScreen() },
    )
    TabbedPagerScaffold(tabs = tabs, pagerState = pagerState, tabStyle = TabStyle.BottomNav)
}
