package com.vayunmathur.communicate

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceSession
import com.vayunmathur.communicate.data.signal.SignalLineSession
import com.vayunmathur.communicate.data.whatsapp.WhatsAppLineSession
import com.vayunmathur.communicate.intents.DeepLink
import com.vayunmathur.communicate.ui.AppSyncEffects
import com.vayunmathur.communicate.ui.AppTelecomEffects
import com.vayunmathur.communicate.ui.CallRouteEffect
import com.vayunmathur.communicate.ui.CommunicateTabs
import com.vayunmathur.communicate.ui.DeepLinkEffect
import com.vayunmathur.library.util.rememberNavBackStack

@Composable
fun Navigation(initialDeepLink: DeepLink? = null) {
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
