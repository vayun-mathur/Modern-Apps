package com.vayunmathur.messages.util

import com.vayunmathur.messages.gmessages.GMessagesClient
import com.vayunmathur.messages.gvoice.GVoiceClient
import com.vayunmathur.messages.meta.InstagramClient
import com.vayunmathur.messages.meta.MetaClient
import com.vayunmathur.messages.signal.SignalClient
import com.vayunmathur.messages.telegram.TelegramClient
import com.vayunmathur.messages.whatsapp.WhatsAppClient

/**
 * Source-agnostic connection state.
 *
 * Both [GMessagesClient] and [GVoiceClient] have their own internal state
 * machines that share the same conceptual phases (idle / awaiting-setup /
 * connecting / connected / disconnected). This sealed type collapses
 * them so the UI + notification path can treat both sources uniformly,
 * without `when (source)` casts to the source-specific state class.
 */
sealed interface SourceConnectionState {
    data object Idle : SourceConnectionState
    /** Awaiting user setup. [setupHint] is a tiny inline action label
     *  the inbox uses ("Set up", "Sign in", etc). */
    data class NeedsSetup(val setupHint: String) : SourceConnectionState
    /** Active setup-in-progress with a QR code to render (Messages-for-Web
     *  only). */
    data class Pairing(val qrUrl: String) : SourceConnectionState
    data object Connecting : SourceConnectionState
    data object Connected : SourceConnectionState
    data class Disconnected(val reason: String) : SourceConnectionState
}

fun GMessagesClient.State.toUnified(): SourceConnectionState = when (this) {
    GMessagesClient.State.Idle -> SourceConnectionState.NeedsSetup("Set up")
    is GMessagesClient.State.Pairing -> SourceConnectionState.Pairing(qrUrl)
    GMessagesClient.State.Connected -> SourceConnectionState.Connected
    is GMessagesClient.State.Disconnected -> SourceConnectionState.Disconnected(reason)
}

fun GVoiceClient.State.toUnified(): SourceConnectionState = when (this) {
    GVoiceClient.State.Idle -> SourceConnectionState.Idle
    GVoiceClient.State.NeedsSetup -> SourceConnectionState.NeedsSetup("Sign in")
    GVoiceClient.State.Connecting -> SourceConnectionState.Connecting
    GVoiceClient.State.Connected -> SourceConnectionState.Connected
    is GVoiceClient.State.Disconnected -> SourceConnectionState.Disconnected(reason)
    is GVoiceClient.State.BadCredentials -> SourceConnectionState.Disconnected(reason)
    is GVoiceClient.State.ConnectError -> SourceConnectionState.Disconnected(reason)
}

fun TelegramClient.State.toUnified(): SourceConnectionState = when (this) {
    TelegramClient.State.Idle -> SourceConnectionState.Idle
    TelegramClient.State.NeedsSetup -> SourceConnectionState.NeedsSetup("Sign in")
    is TelegramClient.State.AwaitingCode -> SourceConnectionState.Connecting
    is TelegramClient.State.AwaitingQrScan -> SourceConnectionState.Pairing(qrUrl)
    is TelegramClient.State.AwaitingPassword -> SourceConnectionState.Connecting
    TelegramClient.State.Connecting -> SourceConnectionState.Connecting
    TelegramClient.State.Connected -> SourceConnectionState.Connected
    is TelegramClient.State.Disconnected -> SourceConnectionState.Disconnected(reason)
}

fun SignalClient.State.toUnified(): SourceConnectionState = when (this) {
    SignalClient.State.Idle -> SourceConnectionState.Idle
    SignalClient.State.NeedsSetup -> SourceConnectionState.NeedsSetup("Link device")
    is SignalClient.State.AwaitingQrScan -> SourceConnectionState.Pairing(qrUrl)
    SignalClient.State.Connecting -> SourceConnectionState.Connecting
    SignalClient.State.Connected -> SourceConnectionState.Connected
    is SignalClient.State.Disconnected -> SourceConnectionState.Disconnected(reason)
}

fun WhatsAppClient.State.toUnified(): SourceConnectionState = when (this) {
    WhatsAppClient.State.Idle -> SourceConnectionState.Idle
    WhatsAppClient.State.NeedsSetup -> SourceConnectionState.NeedsSetup("Link device")
    is WhatsAppClient.State.AwaitingQrScan -> SourceConnectionState.Pairing(qrData)
    WhatsAppClient.State.Connecting -> SourceConnectionState.Connecting
    WhatsAppClient.State.Connected -> SourceConnectionState.Connected
    is WhatsAppClient.State.Disconnected -> SourceConnectionState.Disconnected(reason)
}

fun MetaClient.State.toUnified(): SourceConnectionState = when (this) {
    MetaClient.State.Idle -> SourceConnectionState.Idle
    MetaClient.State.NeedsSetup -> SourceConnectionState.NeedsSetup("Sign in")
    MetaClient.State.Connecting -> SourceConnectionState.Connecting
    MetaClient.State.Connected -> SourceConnectionState.Connected
    is MetaClient.State.Disconnected -> SourceConnectionState.Disconnected(reason)
}

fun InstagramClient.State.toUnified(): SourceConnectionState = when (this) {
    InstagramClient.State.Idle -> SourceConnectionState.Idle
    InstagramClient.State.NeedsSetup -> SourceConnectionState.NeedsSetup("Sign in")
    InstagramClient.State.Connecting -> SourceConnectionState.Connecting
    InstagramClient.State.Connected -> SourceConnectionState.Connected
    is InstagramClient.State.Disconnected -> SourceConnectionState.Disconnected(reason)
}

