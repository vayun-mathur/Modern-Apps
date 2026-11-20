package com.vayunmathur.communicate.data.whatsapp

import android.content.Context
import com.vayunmathur.communicate.data.whatsapp.registration.WhatsAppDeviceFingerprint
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Session facade for the WhatsApp primary line, mirroring [GoogleVoiceSession]'s shape so the UI and
 * the sync service treat all three lines (SIM / Google Voice / WhatsApp) uniformly.
 *
 * Owns the reactive sign-in flag + phone-number mirror in DataStore; the authoritative credentials
 * live in [WhatsAppAuthData] (private prefs). Bridges the [WhatsAppClient] singleton's [state] +
 * [events] out to callers.
 */
class WhatsAppLineSession private constructor(
    private val store: DataStoreUtils,
) {
    /** Reactive sign-in state for the Accounts screen + line pickers. */
    val signedInFlow: Flow<Boolean>
        get() = store.booleanFlow(KEY_SIGNED_IN)

    suspend fun isSignedIn(): Boolean = store.getBooleanAwait(KEY_SIGNED_IN, default = false)

    val phoneNumberFlow: Flow<String?>
        get() = store.stringFlow(KEY_PHONE_NUMBER).map { it }

    suspend fun phoneNumber(): String? = store.getStringAwait(KEY_PHONE_NUMBER)

    /** True when a completed registration is persisted (primary line is live). */
    fun hasUsableCredentials(context: Context): Boolean =
        WhatsAppAuthData.load(context)?.registered == true

    /** Lifecycle state mapped from the client's internal handshake state machine. */
    val state: Flow<WhatsAppState>
        get() = WhatsAppClient.state.map { it.toPublic() }

    /** The client's event stream. */
    val events: SharedFlow<WhatsAppEvent>
        get() = WhatsAppClient.events

    /**
     * Initialize + connect the primary client if we have usable credentials. Safe to call repeatedly
     * (WhatsAppClient.init is idempotent). Call from the sync service / MainActivity.
     */
    suspend fun init(context: Context) {
        WhatsAppClient.init(context.applicationContext)
        // Suspend until the saved-auth load lands (on IO, never blocking the
        // caller), preserving the old synchronous-init ordering.
        WhatsAppClient.awaitInitAuth()
        if (withContext(Dispatchers.IO) { hasUsableCredentials(context) }) {
            WhatsAppClient.start()
        }
    }

    /** Mark the line signed-in after a successful registration and start the client. */
    suspend fun markRegistered(context: Context, auth: WhatsAppAuthData) {
        store.setString(KEY_PHONE_NUMBER, auth.phoneNumber)
        store.setBoolean(KEY_SIGNED_IN, true)
        WhatsAppClient.init(context.applicationContext)
        // Registration still waits for init's saved-auth load before
        // proceeding (incl. the caller's onVerified), just not on Main:
        // this suspends on the client's IO scope instead of runBlocking.
        WhatsAppClient.awaitInitAuth()
        WhatsAppClient.start()
    }

    suspend fun signOut(context: Context) {
        WhatsAppClient.stop()
        WhatsAppAuthData.clear(context)
        WhatsAppDeviceFingerprint.clear(context)
        store.removeKeys(listOf(KEY_PHONE_NUMBER))
        store.setBoolean(KEY_SIGNED_IN, false)
    }

    /** Preference keys for the WhatsApp line session (signed-in flag, phone number). */
    companion object {
        private const val KEY_SIGNED_IN = "wa_signed_in"
        private const val KEY_PHONE_NUMBER = "wa_phone_number"

        fun get(context: Context): WhatsAppLineSession =
            WhatsAppLineSession(DataStoreUtils.getInstance(context.applicationContext))
    }
}

/** Map the client's internal [WhatsAppClient.State] to the public [WhatsAppState]. */
private fun WhatsAppClient.State.toPublic(): WhatsAppState = when (this) {
    is WhatsAppClient.State.Idle -> WhatsAppState.Disconnected
    is WhatsAppClient.State.NeedsSetup -> WhatsAppState.Disconnected
    is WhatsAppClient.State.Connecting -> WhatsAppState.Connecting
    is WhatsAppClient.State.Connected -> WhatsAppState.Ready
    is WhatsAppClient.State.Disconnected -> WhatsAppState.Disconnected
}
