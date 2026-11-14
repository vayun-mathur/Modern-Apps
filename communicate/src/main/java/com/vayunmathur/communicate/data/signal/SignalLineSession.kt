package com.vayunmathur.communicate.data.signal

import android.content.Context
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.map

/**
 * Session facade for the Signal primary line, mirroring
 * [com.vayunmathur.communicate.data.whatsapp.WhatsAppLineSession]'s
 * shape so the UI and the sync service treat all lines uniformly.
 *
 * Owns the reactive sign-in flag + phone-number mirror in DataStore; the authoritative credentials
 * live in [SignalAuthData] (private prefs). Bridges the [SignalClient] singleton's [state] + [events]
 * out to callers.
 */
class SignalLineSession private constructor(
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
        SignalAuthData.load(context)?.registered == true

    /** Lifecycle state mapped from the client's internal handshake state machine. */
    val state: Flow<SignalState>
        get() = SignalClient.state.map { it.toPublic() }

    /** The client's event stream. */
    val events: SharedFlow<SignalEvent>
        get() = SignalClient.events

    /**
     * Initialize + connect the primary client if we have usable credentials. Safe to call repeatedly
     * (SignalClient.init is idempotent). Call from the sync service / MainActivity.
     */
    fun init(context: Context) {
        SignalClient.init(context.applicationContext)
        if (hasUsableCredentials(context)) {
            SignalClient.start()
        }
    }

    /** Mark the line signed-in after a successful registration and start the client. */
    suspend fun markRegistered(context: Context, auth: SignalAuthData) {
        store.setString(KEY_PHONE_NUMBER, auth.phoneNumber)
        store.setBoolean(KEY_SIGNED_IN, true)
        SignalClient.init(context.applicationContext)
        SignalClient.start()
    }

    suspend fun signOut(context: Context) {
        SignalClient.stop()
        SignalAuthData.clear(context)
        store.removeKeys(listOf(KEY_PHONE_NUMBER))
        store.setBoolean(KEY_SIGNED_IN, false)
    }

    /** Preference keys for the Signal line session (signed-in flag, phone number). */
    companion object {
        private const val KEY_SIGNED_IN = "sig_signed_in"
        private const val KEY_PHONE_NUMBER = "sig_phone_number"

        fun get(context: Context): SignalLineSession =
            SignalLineSession(DataStoreUtils.getInstance(context.applicationContext))
    }
}

/** Map the client's internal [SignalClient.State] to the public [SignalState]. */
private fun SignalClient.State.toPublic(): SignalState = when (this) {
    is SignalClient.State.Idle -> SignalState.Disconnected
    is SignalClient.State.NeedsSetup -> SignalState.Disconnected
    is SignalClient.State.Connecting -> SignalState.Connecting
    is SignalClient.State.Connected -> SignalState.Ready
    is SignalClient.State.Disconnected -> SignalState.Disconnected
}
