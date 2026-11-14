package com.vayunmathur.cast.platform

import android.app.Application
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.vayunmathur.library.log.Log
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.cast.R
import com.vayunmathur.cast.domain.CastDevice
import com.vayunmathur.cast.domain.ClientFailure
import com.vayunmathur.cast.domain.ClientPhase
import com.vayunmathur.cast.platform.mirror.MirrorConsentActivity
import com.vayunmathur.library.ui.ExternalIntents
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "CastVM"

/**
 * How long the toolbar reports "searching".
 *
 * mDNS keeps answering for as long as it is asked, so there is no point at which discovery is genuinely
 * finished; this is just how long a receiver on the network takes to answer, after which a still-empty
 * list means something is wrong rather than slow.
 */
private const val SCAN_INDICATOR_MS = 6_000L

/**
 * ViewModel for the Cast app.
 *
 * Owns no session state: [CastController] does, because the session outlives this ViewModel and is
 * shared with `CastService`. What lives here is what belongs to the screen - whether a scan is running.
 */
class CastViewModel(application: Application) : AndroidViewModel(application), CastActions {

    private val appContext: Context get() = getApplication()
    private val discovery get() = CastController.discovery(appContext)

    private val _isScanning = MutableStateFlow(false)

    private var scanJob: Job? = null

    private val discoveryState = combine(
        discovery.devices,
        _isScanning,
        discovery.localNetworkBlocked,
    ) { devices, scanning, blocked ->
        // Sorted so the list does not reshuffle every time a device re-announces itself.
        Triple(devices.sortedBy { it.friendlyName.lowercase() }, scanning, blocked)
    }

    /** Combined here because `combine` takes at most five flows. */
    private val mirrorState = combine(
        CastController.mirrorPhase,
        CastController.degradation,
        CastController.mirrorFailure,
    ) { phase, degradation, failure -> Triple(phase, degradation, failure) }

    val uiState: StateFlow<CastUiState> = combine(
        discoveryState,
        CastController.device,
        CastController.isConnecting,
        CastController.sessionState,
        mirrorState,
    ) { (devices, scanning, blocked), device, connecting, session, mirror ->
        val (phase, degradation, mirrorFailure) = mirror
        CastUiState(
            devices = devices,
            isScanning = scanning,
            localNetworkBlocked = blocked,
            connectedDevice = device,
            connection = when {
                session.phase == ClientPhase.Failed -> CastConnection.Failed
                session.phase == ClientPhase.AwaitingCode -> CastConnection.AwaitingCode
                connecting -> CastConnection.Connecting
                device != null &&
                    (session.phase == ClientPhase.Paired || session.phase == ClientPhase.Streaming) ->
                    CastConnection.Connected
                else -> CastConnection.Disconnected
            },
            mirrorPhase = phase,
            pairAttemptsLeft = session.attemptsLeft,
            pairCodeChanged = session.codeChanged,
            videoDegraded = degradation.videoUnavailable,
            audioDegraded = degradation.audioUnavailable,
            // The pipeline's own message wins: it is more specific than a handshake failure.
            failure = mirrorFailure ?: failureMessage(session.failure),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CastUiState())

    override fun startScan() {
        // Restart rather than ignore: the refresh button has to do something when the browse is already
        // running, and a second collector on the same manager would double every device.
        scanJob?.cancel()
        _isScanning.value = true
        discovery.clear()
        scanJob = viewModelScope.launch {
            // The browse itself keeps running for as long as the screen is open, so devices appear and
            // disappear as they are switched on and off. Only the "searching" indicator is time-boxed -
            // left on it would spin forever and the refresh button would never come back.
            launch {
                delay(SCAN_INDICATOR_MS)
                _isScanning.value = false
            }
            try {
                discovery.discover().collect { }
            } catch (e: SecurityException) {
                Log.status(TAG, "discovery ended", e)
            } catch (e: IllegalStateException) {
                Log.status(TAG, "discovery ended", e)
            } catch (e: IllegalArgumentException) {
                Log.status(TAG, "discovery ended", e)
            }
            _isScanning.value = false
        }
    }

    override fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _isScanning.value = false
    }

    override fun connect(device: CastDevice) = CastController.connect(appContext, device)

    override fun disconnect() = CastController.disconnect(appContext)

    override fun submitPairCode(code: String) = CastController.submitPairCode(appContext, code)

    /**
     * Mirroring cannot be started from here directly: the screen-capture consent dialog needs an
     * Activity to host it, and the token it returns is single-use, so the trampoline runs afresh every
     * session.
     */
    override fun startMirroring() {
        ExternalIntents.launch(appContext, MirrorConsentActivity.intent(appContext))
    }

    override fun stopMirroring() = CastController.stopMirroring(appContext)

    /**
     * Turn a handshake failure into something worth reading.
     *
     * Shorter than it used to be, and that is the point: the Cast version had to translate `NOT_FOUND`
     * and `SYSTEM_ERROR` from a receiver refusing an app id we had to guess at. Both ends are ours now,
     * so the only failures left are real ones.
     */
    private fun failureMessage(reason: ClientFailure?): String? = when (reason) {
        null -> null
        ClientFailure.Unreachable -> appContext.getString(R.string.cast_connect_lost)
        ClientFailure.VersionMismatch -> appContext.getString(R.string.cast_version_mismatch)
        ClientFailure.CodeRejected -> appContext.getString(R.string.cast_pair_rejected)
        ClientFailure.StreamRefused -> appContext.getString(R.string.cast_mirror_negotiation_failed)
        ClientFailure.Protocol -> appContext.getString(R.string.cast_protocol_error)
    }

    /**
     * There is no intent for the local-network toggle itself, so this opens the app's own settings page,
     * where the permission lives.
     */
    override fun openLocalNetworkSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:${appContext.packageName}".toUri(),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ExternalIntents.launch(appContext, intent)
    }
}

/** Factory that creates [CastViewModel] with its application context. */
class CastViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(CastViewModel::class.java)) {
            "Unknown ViewModel class $modelClass"
        }
        return CastViewModel(application) as T
    }
}
