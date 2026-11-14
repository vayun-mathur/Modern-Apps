package com.vayunmathur.games.kart.platform

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.core.content.edit
import com.vayunmathur.library.log.Log
import com.vayunmathur.sdk.controller.ControllerAdvertiser
import com.vayunmathur.sdk.controller.ControllerHost
import com.vayunmathur.sdk.controller.ControllerInput
import com.vayunmathur.sdk.controller.ControllerSocket
import com.vayunmathur.sdk.controller.LobbyState
import java.io.IOException
import java.net.ServerSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private const val TAG = "KartHost"
private const val PREFS = "kart_host"
private const val KEY_HOST_ID = "host_id"

/**
 * The TV-side lobby owner: advertises `_gamepad._tcp`, accepts phones, and exposes the
 * [ControllerHost] fan-in the race loop reads.
 *
 * Key material is injectable: [publicBundle] is the ML-KEM+ML-DSA bundle from `:library:e2ee-p2p`
 * (`PqcIdentity.publicBundle`), [unsealSecret] opens a sealed session secret with the KEM private
 * half. When [pairingStore] is present, device keys and the host id live in the same non-backed-up
 * file as the identity, so a restored backup can neither impersonate this TV nor silently inherit
 * its paired phones; without it (tests) they fall back to private prefs. Either way the host id
 * is a stable id minted once, so re-pairing survives restarts.
 *
 * PQC stays behind the lambdas deliberately: this module never touches the native library
 * directly, and tests construct a `KartHost` with fake keys.
 *
 * Construction is synchronous (the lobby/idle flows exist from `init`), but the [ControllerHost]
 * itself is built in [start] once the suspend host id resolves — before that the lobby reads
 * empty and handshakes cannot arrive because nothing is advertised yet.
 */
class KartHost(
    private val context: Context,
    private val publicBundle: () -> ByteArray?,
    private val unsealSecret: (ByteArray) -> ByteArray?,
    private val pairingStore: KartPairingStore? = null,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private var inner: ControllerHost? = null

    private val _lobby = MutableStateFlow(LobbyState(gameId = "kart"))
    /** Mirrors the inner host's lobby once [start] builds it; empty before that. */
    val lobby: StateFlow<LobbyState> = _lobby.asStateFlow()

    private val _inputs = MutableStateFlow<Map<String, ControllerInput>>(emptyMap())
    /** Mirrors the inner host's inputs once [start] builds it; empty before that. */
    val inputs: StateFlow<Map<String, ControllerInput>> = _inputs.asStateFlow()

    /** The inner host, for the race loop. Null until [start]. */
    val host: ControllerHost?
        get() = inner

    private val advertiser = ControllerAdvertiser(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null
    private var started = false

    /** The pair code on screen, if a phone is mid-handshake. Null until [start]. */
    val pairingCode: String? get() = inner?.pairingCode

    /**
     * Resolve the host id, build the [ControllerHost], and start advertising + accepting. Safe to
     * call repeatedly; the second call is a no-op rather than a second lobby.
     */
    suspend fun start() {
        if (started) return
        started = true
        val hostId = pairingStore?.deviceId() ?: legacyHostId()
        val host = ControllerHost(
            gameId = "kart",
            hostId = hostId,
            providePublicBundle = publicBundle,
            unsealSecret = unsealSecret,
            loadDeviceKey = ::loadDeviceKey,
            saveDeviceKey = ::saveDeviceKey,
        )
        inner = host
        scope.launch { host.lobby.collect { _lobby.value = it } }
        scope.launch { host.inputs.collect { _inputs.value = it } }
        scope.launch { acceptLoop(hostId, host) }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        advertiser.unadvertise()
        inner?.close()
        inner = null
        scope.cancel()
    }

    private fun acceptLoop(hostId: String, host: ControllerHost) {
        val server = try {
            ServerSocket(0).also { this.server = it }
        } catch (e: IOException) {
            Log.status(TAG, "cannot bind a lobby port", e)
            return
        }
        advertiser.advertise(
            friendlyName = android.os.Build.MODEL ?: "TV",
            deviceId = hostId,
            port = server.localPort,
            gameId = "kart",
        )
        Log.status(TAG, "lobby open on port ${server.localPort}")
        while (true) {
            val accepted = try {
                server.accept()
            } catch (_: Exception) {
                return
            }
            val socket = ControllerSocket()
            socket.attach(accepted)
            host.accept(socket)
        }
    }

    private fun legacyHostId(): String = prefs.getString(KEY_HOST_ID, null) ?: run {
        val fresh = java.util.UUID.randomUUID().toString()
        prefs.edit { putString(KEY_HOST_ID, fresh) }
        fresh
    }

    private fun loadDeviceKey(senderId: String): ByteArray? {
        pairingStore?.let {
            return runBlocking(Dispatchers.IO) { it.deviceKey(senderId) }
        }
        return prefs.getString(deviceKeyOf(senderId), null)?.let {
            runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull()
        }
    }

    private fun saveDeviceKey(senderId: String, key: ByteArray) {
        pairingStore?.let {
            runBlocking(Dispatchers.IO) { it.remember(senderId, key) }
            return
        }
        prefs.edit { putString(deviceKeyOf(senderId), Base64.encodeToString(key, Base64.NO_WRAP)) }
    }

    private fun deviceKeyOf(senderId: String): String = "device_$senderId"
}
