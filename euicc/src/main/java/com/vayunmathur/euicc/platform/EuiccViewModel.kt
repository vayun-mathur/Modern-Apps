package com.vayunmathur.euicc.platform

import android.app.Application
import com.vayunmathur.library.log.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.euicc.EuiccNative
import com.vayunmathur.euicc.data.EuiccInfo
import com.vayunmathur.euicc.data.Notification
import com.vayunmathur.euicc.data.Profile
import com.vayunmathur.euicc.service.DownloadService
import com.vayunmathur.euicc.telephony.EuiccChannelManager
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Aggregate UI state for the LPA screen. */
data class EuiccScreenState(
    val loading: Boolean = true,
    val error: String? = null,
    /** Transient success/info notice (e.g. after a download). */
    val message: String? = null,
    val eid: String? = null,
    val info: EuiccInfo? = null,
    val profiles: List<Profile> = emptyList(),
    val notifications: List<Notification> = emptyList(),
)

/** Native download outcome (`{success, message, iccid}`). */
@kotlinx.serialization.Serializable
private data class DownloadResult(
    val success: Boolean = false,
    val message: String = "",
    val iccid: String = "",
)

/** Native authenticate-session outcome (see `EuiccNative.nativeAuthenticate`). */
@kotlinx.serialization.Serializable
private data class AuthSession(
    val error: String? = null,
    val transactionId: String? = null,
    val carrier: String? = null,
    val profileName: String? = null,
    val iccid: String? = null,
    val ccRequired: Boolean = false,
)

/**
 * Nickname seeded at install from the carrier preview: profile name first,
 * carrier second, pending carrier as the fallback outside the preview states.
 * Must be read from the pre-install [state] — once the flow moves to
 * [DownloadState.Installing] the preview fields are gone.
 */
internal fun installNickname(state: DownloadState, pendingCarrier: String?): String =
    when (state) {
        is DownloadState.Confirm ->
            state.profileName?.ifBlank { null } ?: state.carrier
        is DownloadState.AwaitingConfirmationCode -> state.carrier
        else -> pendingCarrier
    }.orEmpty()

private const val TAG = "EuiccViewModel"

/** Switch focus to the confirmation-code screen after a `8.2.7` refusal. */
private fun isConfirmationCodeRefusal(message: String?): Boolean =
    message != null && (
        "Confirmation Code is refused" in message ||
            "maximum number of retries for the Confirmation Code" in message ||
            "Confirmation Code is missing" in message
        )

class EuiccViewModel(app: Application) : AndroidViewModel(app) {
    private val channelManager = EuiccChannelManager(app)
    private val json = Json { ignoreUnknownKeys = true }

    var state by mutableStateOf(EuiccScreenState())
        private set

    /**
     * The activation flow's own state, kept separate from [state] because the two have
     * different lifetimes: [state] mirrors the eUICC and is replaced on every reload,
     * while a download survives across those reloads until the user leaves the screen.
     */
    var download by mutableStateOf<DownloadState>(DownloadState.Idle)
        private set

    /**
     * Authenticated-but-unfinished SM-DP+ session (opaque transaction handle
     * from `nativeAuthenticate`), plus the inputs that produced it. Kept so
     * Confirm → finish, confirmation-code resume, and cancel can all address
     * the same server session.
     */
    private var pendingTransaction: String? = null
    private var pendingActivation: String? = null
    private var pendingImei: String? = null
    private var pendingConfirmationCode: String? = null
    /** Carrier preview from AuthenticateClient, for Complete after install. */
    private var pendingCarrier: String? = null

    init {
        // SM-DP+ servers authenticate TLS under the GSMA CI PKI as well as public
        // CAs, so downloads need the ESIM bundle (system roots + GSMA CI root).
        // Both entry activities create this ViewModel before any download runs.
        NetworkClient.init(app, TrustBundle.ESIM)
        reload()
    }

    /**
     * Runs the download for [activationCode] in split phases, mirroring the
     * lpac-jni `downloadProfile` state split:
     * authenticate → [DownloadState.Confirm] → optional
     * [DownloadState.AwaitingConfirmationCode] → [DownloadState.Installing]
     * (with progress) → [DownloadState.Complete]/[DownloadState.Failed].
     *
     * Nothing is written to the eUICC's profile store before the user confirms;
     * cancelling from Confirm (or a confirm timeout) frees the server session
     * via `nativeCancelDownload` instead of orphaning it.
     *
     * @param imei device IMEI for ctxParams1 (user-editable, optional).
     * @param confirmationCode already-known confirmation code, or null.
     */
    fun startDownload(activationCode: String, imei: String? = null, confirmationCode: String? = null) {
        // A session is already driving the flow in every non-terminal state;
        // Failed/Complete/Idle are the only re-entry points (retry / fresh start).
        if (download !is DownloadState.Idle &&
            download !is DownloadState.Failed &&
            download !is DownloadState.Complete
        ) {
            return
        }
        download = DownloadState.Preparing
        viewModelScope.launch {
            val session = withContext(Dispatchers.IO) {
                runCatching {
                    channelManager.withIsdrChannel {
                        EuiccNative.nativeAuthenticate(
                            activationCode,
                            confirmationCode.orEmpty(),
                            tacHex = "",
                            imei = imei.orEmpty(),
                        )
                    }
                }
            }
            val auth = session.fold(
                onSuccess = { raw ->
                    runCatching { json.decodeFromString<AuthSession>(raw) }.getOrNull()
                },
                onFailure = {
                    Log.error(TAG, "download authenticate failed: ${it.message}", it)
                    null
                },
            )
            if (auth == null || auth.transactionId == null) {
                val message = auth?.error
                Log.error(TAG, "download failed: ${message.ifNullOrBlank()}")
                download = DownloadState.Failed(message?.ifBlank { null })
                reload()
                return@launch
            }
            // Stash the handle for confirm / finish / cancel.
            pendingTransaction = auth.transactionId
            pendingActivation = activationCode
            pendingImei = imei
            pendingCarrier = auth.carrier
            if (auth.ccRequired && confirmationCode.isNullOrEmpty()) {
                download = DownloadState.AwaitingConfirmationCode(auth.carrier)
            } else {
                download = DownloadState.Confirm(auth.carrier, auth.profileName)
            }
        }
    }

    /** The user accepted the carrier preview: run PrepareDownload → BPP → install. */
    fun confirmDownload(confirmationCode: String? = null) {
        val transactionId = pendingTransaction ?: return
        val code = confirmationCode ?: pendingConfirmationCode
        if (download !is DownloadState.Confirm && download !is DownloadState.AwaitingConfirmationCode) return
        // Capture the preview nickname before leaving the Confirm state:
        // reading `download` after the Installing transition always yields
        // the Installing branch and loses the profile name.
        val nickname = installNickname(download, pendingCarrier)
        download = DownloadState.Installing(0f)
        viewModelScope.launch {
            val callback = object : EuiccNative.DownloadProgressCallback {
                override fun onProgress(done: Int, total: Int): Boolean {
                    // Progress 0.5 → 0.95 across the segment stream; the
                    // authenticate half already accounts for the first 0.5.
                    val fraction = if (total <= 0) 0f else done.toFloat() / total.toFloat()
                    download = DownloadState.Installing(0.5f + 0.45f * fraction)
                    return true
                }
            }
            // Foreground guard for the eUICC write: keeps process importance up
            // (and the CPU awake) until the install settles either way.
            // The nickname seeds from the carrier preview (profile name first,
            // carrier second); native applies it via SetNickname while the
            // channel is still open.
            val app = getApplication<Application>()
            DownloadService.start(app)
            val outcome = try {
                withContext(Dispatchers.IO) {
                    runCatching {
                        channelManager.withIsdrChannel {
                            EuiccNative.nativeFinishDownload(transactionId, code.orEmpty(), nickname, callback)
                        }
                    }
                }
            } finally {
                DownloadService.stop(app)
            }
            pendingTransaction = null
            download = outcome.fold(
                onSuccess = { raw ->
                    val result = runCatching { json.decodeFromString<DownloadResult>(raw) }.getOrNull()
                    when {
                        result == null -> {
                            // Error strings only — no activation data or crypto material.
                            Log.error(TAG, "download failed: unreadable native result")
                            DownloadState.Failed(null)
                        }
                        result.success -> DownloadState.Complete(pendingCarrier)
                        isConfirmationCodeRefusal(result.message) -> {
                            // A wrong code is retryable without re-authenticating
                            // only when the session survives; ours is consumed,
                            // so re-authenticate and land back on the code screen.
                            Log.error(TAG, "download failed: ${result.message}")
                            relaunchForConfirmationCode(result.message)
                        }
                        else -> {
                            Log.error(TAG, "download failed: ${result.message.ifBlank { "<empty>" }}")
                            DownloadState.Failed(result.message.ifBlank { null })
                        }
                    }
                },
                onFailure = {
                    Log.error(TAG, "download failed: ${it.message}", it)
                    DownloadState.Failed(it.message)
                },
            )
            // The eUICC changed either way: a success installed a profile, and a failure may
            // still have left a notification behind.
            reload()
        }
    }

    /** The user submitted a confirmation code: resume the install with it. */
    fun submitConfirmationCode(code: String) {
        pendingConfirmationCode = code
        confirmDownload(code)
    }

    /**
     * Cancels an in-flight download: frees the server session (when one is
     * authenticated but unfinished) and returns to idle. Safe to call from any
     * state; a no-op without a pending transaction.
     */
    fun cancelDownload(reason: Int = 0) {
        val transactionId = pendingTransaction
        pendingTransaction = null
        pendingConfirmationCode = null
        if (transactionId != null) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    channelManager.withIsdrChannel {
                        EuiccNative.nativeCancelDownload(transactionId, reason)
                    }
                }
            }
        }
        download = DownloadState.Idle
    }

    /** Clears the activation flow, so leaving and re-entering it starts clean. */
    fun clearDownload() {
        // Leaving mid-authentication orphans the server session unless freed.
        if (pendingTransaction != null) cancelDownload()
        pendingActivation = null
        pendingImei = null
        pendingConfirmationCode = null
        pendingCarrier = null
        download = DownloadState.Idle
    }

    /**
     * Re-authenticates after a confirmation-code refusal and lands back on the
     * code screen with the server's wording. The refused session is consumed,
     * so a fresh AuthenticateClient round trip is required before retrying —
     * retrying finish on the dead handle would only report "session expired".
     */
    private suspend fun relaunchForConfirmationCode(serverMessage: String): DownloadState {
        val activation = pendingActivation
        pendingTransaction = null
        if (activation == null) return DownloadState.Failed(serverMessage)
        val raw = withContext(Dispatchers.IO) {
            runCatching {
                channelManager.withIsdrChannel {
                    EuiccNative.nativeAuthenticate(
                        activation,
                        pendingConfirmationCode.orEmpty(),
                        tacHex = "",
                        imei = pendingImei.orEmpty(),
                    )
                }
            }.getOrNull()
        }
        val auth = raw?.let { runCatching { json.decodeFromString<AuthSession>(it) }.getOrNull() }
        if (auth?.transactionId == null) {
            return DownloadState.Failed(auth?.error ?: serverMessage)
        }
        pendingTransaction = auth.transactionId
        pendingConfirmationCode = null
        pendingCarrier = auth.carrier ?: pendingCarrier
        return DownloadState.AwaitingConfirmationCode(pendingCarrier, error = true)
    }

    private fun String?.ifNullOrBlank(): String = if (this.isNullOrBlank()) "<empty>" else this

    /** Reads EID, eUICC info, profiles, and notifications in one channel session. */
    fun reload() {
        val carryMessage = state.message
        state = state.copy(loading = true, error = null)
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    channelManager.withIsdrChannel {
                        EuiccScreenState(
                            loading = false,
                            message = carryMessage,
                            eid = EuiccNative.nativeGetEid(),
                            info = EuiccNative.nativeGetEuiccInfo()?.let { json.decodeFromString<EuiccInfo>(it) },
                            profiles = EuiccNative.nativeGetProfiles()
                                ?.let { json.decodeFromString<List<Profile>>(it) } ?: emptyList(),
                            notifications = EuiccNative.nativeListNotifications()
                                ?.let { json.decodeFromString<List<Notification>>(it) } ?: emptyList(),
                        )
                    }
                }
            }
            state = outcome.getOrElse {
                state.copy(loading = false, error = it.message ?: "eUICC unavailable")
            }
        }
    }

    fun enable(iccid: String) = action { EuiccNative.nativeEnableProfile(iccid) }
    fun disable(iccid: String) = action { EuiccNative.nativeDisableProfile(iccid) }
    fun delete(iccid: String) = action { EuiccNative.nativeDeleteProfile(iccid) }
    fun rename(iccid: String, nickname: String) = action { EuiccNative.nativeSetNickname(iccid, nickname) }
    fun removeNotification(seq: Int) = action { EuiccNative.nativeRemoveNotification(seq) }

    /** Runs a single ES10 mutation in its own channel session, then reloads. */
    private fun action(op: () -> Int) {
        state = state.copy(loading = true, error = null)
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { channelManager.withIsdrChannel { op() } }
            }
            val error = outcome.fold(
                onSuccess = { code -> if (code == 0) null else "Operation failed (code $code)" },
                onFailure = { it.message ?: "Operation failed" },
            )
            if (error == null) {
                reload()
            } else {
                state = state.copy(loading = false, error = error)
            }
        }
    }
}
