package com.vayunmathur.messages

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.PermissionsChecker
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.messages.data.buildMessagesDatabase
import com.vayunmathur.messages.data.MessageSource
import com.vayunmathur.messages.ui.ComposeScreen
import com.vayunmathur.messages.ui.ConversationScreen
import com.vayunmathur.messages.ui.InboxScreen
import com.vayunmathur.messages.ui.SettingsScreen
import com.vayunmathur.messages.ui.setup.MessagesPairingScreen
import com.vayunmathur.messages.ui.setup.SignalLoginScreen
import com.vayunmathur.messages.ui.setup.TelegramLoginScreen
import com.vayunmathur.messages.ui.setup.VoiceLoginScreen
import com.vayunmathur.messages.util.IncomingIntent
import com.vayunmathur.messages.util.MessagesViewModel
import com.vayunmathur.messages.util.ShareIntentParser
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {

    /** Bridges Activity-side intent updates into the Compose backstack.
     *  Each newly delivered intent goes through this state; Compose
     *  observes and routes via [applyIntentRoute]. */
    private val pendingIntent: MutableState<Intent?> = mutableStateOf(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingIntent.value = intent
        setContent {
            DynamicTheme {
                // POST_NOTIFICATIONS is the only runtime perm we strictly
                // need — without it the foreground service runs but the
                // user sees no incoming-message alerts. READ_CONTACTS is
                // requested on-demand when the inbox actually tries to
                // resolve a phone number to a contact name.
                val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    arrayOf(
                        Manifest.permission.POST_NOTIFICATIONS,
                        // Used by ContactResolver to look up display names
                        // and photos for the conversation peers.
                        Manifest.permission.READ_CONTACTS,
                    )
                } else {
                    arrayOf(Manifest.permission.READ_CONTACTS)
                }
                PermissionsChecker(perms, getString(R.string.permissions_post_notifications)) {
                    val db = remember { buildMessagesDatabase(this@MainActivity) }
                    Navigation(db, pendingIntent)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Re-delivering an intent while the activity is already alive
        // (singleTop). Hand it off to Compose for backstack routing.
        pendingIntent.value = intent
    }
}

/** Navigation graph for the messages module. */
@Serializable
sealed interface Route : NavKey {
    @Serializable data object Inbox : Route
    @Serializable data class Conversation(val conversationId: String) : Route
    @Serializable data object Settings : Route
    @Serializable data object PairMessages : Route
    @Serializable data object LoginVoice : Route
    @Serializable data object LoginTelegram : Route
    @Serializable data object LoginSignal : Route
    @Serializable data object LoginWhatsApp : Route
    @Serializable data object LoginMessenger : Route
    @Serializable data object LoginInstagram : Route

    /**
     * "Compose new" screen — recipient picker + body + media preview.
     * Used both for the inbox FAB ("New chat") and as the landing
     * surface for share-sheet intents / smsto: deep links.
     */
    @Serializable data class Compose(
        /** Pre-filled recipient (E.164 or any free-form string). null = empty field. */
        val initialNumber: String? = null,
        /** Pre-filled body. null = empty. */
        val initialBody: String? = null,
        /** content:// URIs of media to attach. Encoded as strings so the
         *  route remains Serializable. */
        val initialMediaUris: List<String> = emptyList(),
        /** Best-effort mime hint from the share intent. */
        val initialMime: String? = null,
        /** Pre-selected source (MESSAGES_WEB or VOICE). When set the source
         *  picker in ComposeScreen is hidden and this source is used. */
        val initialSource: String? = null,
    ) : Route
}

@Composable
private fun Navigation(
    db: com.vayunmathur.messages.data.MessagesDatabase,
    pendingIntent: MutableState<Intent?>,
    vm: MessagesViewModel = viewModel(),
) {
    val backStack = rememberNavBackStack<Route>(Route.Inbox)

    // Consume each newly-delivered intent exactly once. snapshotFlow
    // turns the State into a Flow so we get re-triggered every time
    // onNewIntent updates the value; the `null` reset is the "ack".
    LaunchedEffect(Unit) {
        snapshotFlow { pendingIntent.value }.collect { intent ->
            if (intent == null) return@collect
            applyIntentRoute(backStack, ShareIntentParser.parse(intent))
            pendingIntent.value = null
        }
    }

    MainNavigation(backStack) {
        entry<Route.Inbox>(metadata = ListPage { }) {
            InboxScreen(backStack, vm, db)
        }
        entry<Route.Conversation> {
            ConversationScreen(backStack, vm, db, it.conversationId)
        }
        entry<Route.Settings> {
            SettingsScreen(backStack, vm)
        }
        entry<Route.PairMessages> {
            MessagesPairingScreen(backStack, vm)
        }
        entry<Route.LoginVoice> {
            VoiceLoginScreen(backStack)
        }
        entry<Route.LoginTelegram> {
            TelegramLoginScreen(backStack)
        }
        entry<Route.LoginSignal> {
            SignalLoginScreen(backStack)
        }
        entry<Route.LoginWhatsApp> {
            com.vayunmathur.messages.ui.setup.WhatsAppLoginScreen(backStack)
        }
        entry<Route.LoginMessenger> {
            com.vayunmathur.messages.ui.setup.MetaLoginScreen(backStack)
        }
        entry<Route.LoginInstagram> {
            com.vayunmathur.messages.ui.setup.InstagramLoginScreen(backStack)
        }
        entry<Route.Compose> { route ->
            ComposeScreen(
                backStack = backStack,
                vm = vm,
                db = db,
                initialNumber = route.initialNumber,
                initialBody = route.initialBody,
                initialMediaUris = route.initialMediaUris.map(Uri::parse),
                initialMime = route.initialMime,
                initialSource = route.initialSource?.let { name ->
                    runCatching { MessageSource.valueOf(name) }.getOrNull()
                },
            )
        }
    }
}

/**
 * Apply a parsed [IncomingIntent] to the navigation back-stack.
 *
 * Strategy:
 *  - OpenConversation → reset to `[Inbox, Conversation(id)]` so the
 *    user can press back to the inbox once they're done.
 *  - OpenNumber / Share → reset to `[Inbox, Compose(...)]` for the
 *    same reason.
 *  - null → no-op (activity was launched without an actionable intent).
 */
private fun applyIntentRoute(
    backStack: NavBackStack<Route>,
    incoming: IncomingIntent?,
) {
    when (incoming) {
        null -> Unit
        is IncomingIntent.OpenConversation -> {
            backStack.reset(Route.Inbox, Route.Conversation(incoming.conversationId))
        }
        is IncomingIntent.OpenNumber -> {
            backStack.reset(
                Route.Inbox,
                Route.Compose(
                    initialNumber = incoming.phone,
                    initialBody = incoming.prefilledBody,
                ),
            )
        }
        is IncomingIntent.Share -> {
            backStack.reset(
                Route.Inbox,
                Route.Compose(
                    initialBody = incoming.text,
                    initialMediaUris = incoming.mediaUris.map(Uri::toString),
                    initialMime = incoming.mime,
                ),
            )
        }
    }
}
