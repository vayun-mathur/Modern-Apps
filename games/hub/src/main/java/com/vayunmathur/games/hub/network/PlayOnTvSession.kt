package com.vayunmathur.games.hub.network

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Base64
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.vayunmathur.e2ee.Pqc
import com.vayunmathur.library.log.Log
import com.vayunmathur.games.hub.ui.screens.ControllerPadState
import com.vayunmathur.sdk.controller.ClientState
import com.vayunmathur.sdk.controller.ControllerClient
import com.vayunmathur.sdk.controller.ControllerDiscovery
import com.vayunmathur.sdk.controller.ControllerHostInfo
import com.vayunmathur.sdk.controller.RumbleFeedback
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private const val TAG = "PlayOnTvSession"

private val Context.controllerStore by preferencesDataStore(name = "hub_controller")

/**
 * The hub's "Play on TV" session: the phone side of the generic controller protocol.
 *
 * Owns a [ControllerDiscovery] (browse `_gamepad._tcp`) and a [ControllerClient] (connect, pair,
 * join, emit), and reduces their flows into one [ControllerPadState] the screen renders. Rumble
 * from the TV plays on the vibrator; the default rumble display in `ControllerPad` shows the
 * duration alongside.
 *
 * Secrets follow the same rule as the kart TV host: the per-TV device keys live in their own
 * `hub_controller` DataStore file, NOT in the shared `datastore_default` that [com.vayunmathur.games.hub.util.AppBackupAgent]
 * ships to cloud. A restored backup must not silently inherit this phone's pairings.
 * Sealing to the TV's bundle is plain `Pqc.encryptTo` (the cast `SecretSealing` shape, without
 * depending on `:cast:protocol`); the phone holds no long-term PQC identity of its own — the
 * session secret is fresh per connection and the device key is the only thing persisted.
 */
class PlayOnTvSession(context: Context) {

    private val appContext = context.applicationContext

    private val discovery = ControllerDiscovery(appContext)

    /**
     * This phone's stable sender id. The TV keys remembered devices by it, so a fresh id every
     * screen-open would force the pair-code path every time. Read synchronously: a single
     * DataStore file read, once per screen open, off the composition hot path (the screen
     * `remember`s the session, which blocks composition for ~ms only on a cold file read).
     */
    private val senderId: String = runBlocking(Dispatchers.IO) {
        val stored = appContext.controllerStore.data
            .map { it[byteArrayPreferencesKey(KEY_SENDER)] }
            .first()?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
        if (stored != null) return@runBlocking stored
        val fresh = UUID.randomUUID().toString()
        runCatching {
            appContext.controllerStore.edit {
                it[byteArrayPreferencesKey(KEY_SENDER)] = fresh.toByteArray(Charsets.UTF_8)
            }
        }
        fresh
    }

    private val client = ControllerClient(
        senderId = senderId,
        senderName = Build.MODEL ?: "Phone",
        sealToBundle = { bundle, secret ->
            runCatching { Pqc.encryptTo(bundle, secret) }.getOrNull()
        },
        loadDeviceKey = { hostId -> loadDeviceKey(hostId) },
        saveDeviceKey = { hostId, key -> saveDeviceKey(hostId, key) },
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _lastRumble = MutableStateFlow<RumbleFeedback?>(null)

    /** The pad state the screen renders. Collect with the lifecycle. */
    val state: StateFlow<ControllerPadState> = combine(
        client.state,
        discovery.hosts,
        discovery.localNetworkBlocked,
        client.lobby,
        _lastRumble,
    ) { clientState, hosts, blocked, lobby, rumble ->
        ControllerPadState(
            clientState = clientState,
            hosts = hosts,
            localNetworkBlocked = blocked,
            lobby = lobby,
            lastRumble = rumble,
        )
    }.stateIn(scope, SharingStarted.Eagerly, ControllerPadState())

    private var discoverJob: Job? = null

    init {
        client.onRumble = { rumble ->
            _lastRumble.value = rumble
            playRumble(rumble)
        }
        startDiscovery()
    }

    /** Connect to [host]. The client's handshake moves IDLE → CONNECTING → AWAITING_CODE/PAIRED. */
    fun connect(host: ControllerHostInfo) {
        client.connect(host)
    }

    /** Prove the six digits on the TV screen. Only valid in [ClientState.AWAITING_CODE]. */
    fun proveCode(code: String) {
        client.proveCode(code)
    }

    /** Take a seat. Only valid once paired. */
    fun join(displayName: String) {
        client.join(displayName)
    }

    /** One input sample at the pad's emit cadence. Drops when not joined. */
    fun sendInput(buttons: Long, axes: List<Float>, hatX: Int, hatY: Int) {
        client.sendInput(buttons = buttons, axes = axes, hatX = hatX, hatY = hatY)
    }

    /** Leave the lobby. The client returns to IDLE. */
    fun leave() {
        client.leave()
    }

    /**
     * Re-run discovery. Cancels the in-flight browse first (a second concurrent `discover()`
     * would register a second NSD listener and double-report every TV), clears the list so gone
     * TVs disappear, and starts over.
     */
    fun rescan() {
        discovery.clear()
        startDiscovery()
    }

    /** Tear down: close the client and stop the browse. Called when the screen leaves. */
    fun close() {
        discoverJob?.cancel()
        discoverJob = null
        runCatching { client.close() }
        scope.cancel()
    }

    private fun startDiscovery() {
        discoverJob?.cancel()
        discoverJob = scope.launch(Dispatchers.IO) {
            discovery.discover().collectLatest { /* hosts accumulate in discovery.hosts */ }
        }
    }

    private fun playRumble(rumble: RumbleFeedback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val manager = runCatching {
            appContext.getSystemService(VibratorManager::class.java)
        }.getOrNull() ?: return
        val vibrator = runCatching { manager.defaultVibrator }.getOrNull() ?: return
        val duration = rumble.durationMs.coerceIn(1, 1_000).toLong()
        val amplitude = (rumble.highFreq.coerceIn(0f, 1f) * 255).toInt().coerceIn(1, 255)
        runCatching {
            vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
        }.onFailure { e -> Log.status(TAG, "rumble failed", e) }
    }

    private fun loadDeviceKey(hostId: String): ByteArray? =
        runCatching {
            runBlocking(Dispatchers.IO) {
                appContext.controllerStore.data
                    .map { it[byteArrayPreferencesKey(deviceKeyName(hostId))] }
                    .first()
            }
        }.getOrNull()

    private fun saveDeviceKey(hostId: String, key: ByteArray) {
        // Fire-and-forget: pairing already succeeded; persistence only affects the NEXT session.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                // Refuse to persist a degenerate key: an empty key proves nothing and would
                // wedge the next session into the no-code path against a TV that forgot us.
                if (key.isEmpty()) return@launch
                appContext.controllerStore.edit {
                    it[byteArrayPreferencesKey(deviceKeyName(hostId))] = key
                }
            }
        }
    }

    private fun deviceKeyName(hostId: String): String = "hub_device_$hostId"

    private companion object {
        const val KEY_SENDER = "hub_sender_id"
    }
}
