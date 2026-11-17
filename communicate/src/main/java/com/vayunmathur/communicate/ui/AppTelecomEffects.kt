package com.vayunmathur.communicate.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.signal.SignalFeature
import com.vayunmathur.communicate.data.whatsapp.call.WhatsAppCallBridge
import com.vayunmathur.communicate.telephony.InAppCallTelecom

/** Telecom registration effects for all lines. */
@Composable
fun AppTelecomEffects(
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
