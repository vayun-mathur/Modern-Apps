package com.vayunmathur.communicate.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceSession
import com.vayunmathur.communicate.data.googlevoice.call.GoogleVoiceCallBridge
import com.vayunmathur.communicate.data.googlevoice.call.GoogleVoiceCallManager
import com.vayunmathur.communicate.data.rcs.RcsFeature
import com.vayunmathur.communicate.data.rcs.RcsRegistrationState
import com.vayunmathur.communicate.data.rcs.RcsSipTransport
import com.vayunmathur.communicate.data.signal.SignalFeature
import com.vayunmathur.communicate.telephony.GoogleVoiceSyncService
import com.vayunmathur.communicate.telephony.GoogleVoiceTelecom
import com.vayunmathur.communicate.telephony.RcsSyncService
import com.vayunmathur.communicate.telephony.SignalSyncService
import com.vayunmathur.communicate.telephony.WhatsAppSyncService

/** Sync-service effects for all lines. */
@Composable
fun AppSyncEffects(
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
