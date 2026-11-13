@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package com.vayunmathur.games.voxels.network

import android.content.Context
import com.vayunmathur.e2ee.Pqc
import com.vayunmathur.games.voxels.domain.NetFailure
import com.vayunmathur.games.voxels.domain.NetStatus
import com.vayunmathur.games.voxels.domain.netStatusOf
import com.vayunmathur.games.voxels.util.VoxelsNative
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.encoding.Base64

/**
 * Online multiplayer pump: bridges the Rust net queues to the encrypted relay for one session.
 *
 * World-log ops are signed (SignedOp{author,sig,ops}) so peers can authenticate them; player
 * transforms ride ephemeral, unsigned presence. Confidentiality comes from the world AES key (only
 * members hold it); the relay only ever sees ciphertext.
 *
 * Moved out of MainActivity unchanged in its security logic; it now also reports connection state
 * ([NetStatus], including why a session can't start) and the peer list for the HUD.
 */
class MultiplayerPump private constructor(
    private val p: Params,
    private val myId: String,
    private val key: ByteArray,
    private val ownerBundle: ByteArray?,
) {
    /** What MainActivity knows about the session from its launch intent. */
    data class Params(
        val worldId: String,
        val worldKeyB64: String,
        val ownerDevice: String,
        val role: Int,
        val worldName: String,
    )

    private val channel = "world:${p.worldId}"
    private val isHost get() = p.role == ROLE_HOST

    // Host: the authorized member set (all editors — no viewers) + each member's public bundle, so
    // every client op is signature-verified, not just role-gated by id.
    private val roster: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val bundles = ConcurrentHashMap<String, ByteArray>()

    /** Entry point and session setup. */
    companion object {
        private const val ROLE_HOST = 1
        private const val ROLE_CLIENT = 2
        private const val PUMP_MS = 80L
        // Re-read the roster about every 10 s so newly invited editors are accepted mid-session.
        private const val ROSTER_EVERY = 125

        /** Runs the pump until the calling coroutine is cancelled. */
        suspend fun run(context: Context, params: Params, onStatus: (NetStatus) -> Unit, onPeers: (String) -> Unit) {
            onStatus(NetStatus.Connecting)
            if (!runCatching { VoxelsSync.init(context) }.getOrDefault(false)) {
                onStatus(NetStatus.Failed(NetFailure.SERVER_UNREACHABLE)); return
            }
            val myId = VoxelsSync.deviceId
            try { VoxelsNative.nativeSetDevice(myId) } catch (_: Exception) {}
            val key = runCatching { Base64.decode(params.worldKeyB64) }.getOrNull()
            if (key == null) { onStatus(NetStatus.Failed(NetFailure.BAD_INVITE)); return }
            // Owner bundle: the host is the owner; a client fetches it once to verify host ops.
            val ownerBundle = if (params.role == ROLE_HOST) VoxelsSync.publicBundle
            else runCatching { VoxelsSync.getKey(params.ownerDevice) }.getOrNull()
            if (ownerBundle == null) { onStatus(NetStatus.Failed(NetFailure.HOST_UNVERIFIED)); return }
            MultiplayerPump(params, myId, key, ownerBundle).loop(onStatus, onPeers)
        }
    }

    private suspend fun refreshRoster() {
        val members = runCatching { VoxelsSync.fetchRoster(p.worldId, key, VoxelsSync.publicBundle) }.getOrNull()
        members?.forEach { (id, _) ->
            roster.add(id)
            if (!bundles.containsKey(id)) VoxelsSync.getKey(id)?.let { bundles[id] = it }
        }
    }

    // Verify + gate an incoming signed op, then hand the inner NetMsg to the engine. Each early
    // return rejects one distinct failure (bad author, bad roster, bad signature); splitting would
    // scatter a single security check.
    @Suppress("ReturnCount")
    private fun handleSignedOp(scope: CoroutineScope, plain: String) {
        try {
            val so = JSONObject(plain)
            val author = so.optString("author")
            val sig = so.optString("sig")
            val ops = so.optString("ops")
            if (author.isEmpty() || ops.isEmpty() || author == myId) return
            if (p.role == ROLE_CLIENT) {
                // Client applies only owner-signed authoritative state.
                if (author != p.ownerDevice) return
                val ob = ownerBundle ?: return
                if (!verify(ob, ops, sig)) return
            } else {
                // Host: author must be a rostered member AND the op must carry a valid signature from
                // that member's bundle. On a cache miss, fetch it for next time and drop this op (the
                // client resends as chunks stay dirty).
                if (!roster.contains(author)) return
                val ab = bundles[author]
                if (ab == null) { fetchBundleLater(scope, author); return }
                if (!verify(ab, ops, sig)) return
            }
            VoxelsNative.netPushInbound(ops)
        } catch (_: Exception) {}
    }

    private fun fetchBundleLater(scope: CoroutineScope, author: String) {
        scope.launch(Dispatchers.IO) {
            runCatching { VoxelsSync.getKey(author) }.getOrNull()?.let { bundles[author] = it }
        }
    }

    private fun verify(bundle: ByteArray, ops: String, sig: String): Boolean =
        runCatching { Pqc.verify(bundle, ops.encodeToByteArray(), Base64.decode(sig)) }.getOrDefault(false)

    private fun handlePresence(plain: String) {
        try {
            val e = JSONObject(plain)
            if (e.optString("from") == myId) return
            VoxelsNative.netPushInbound(e.getJSONObject("m").toString())
        } catch (_: Exception) {}
    }

    private suspend fun signed(opsJson: String): String {
        val sig = Base64.encode(VoxelsSync.sign(opsJson.encodeToByteArray()))
        return JSONObject().put("author", myId).put("sig", sig).put("ops", opsJson).toString()
    }

    private fun startLive(scope: CoroutineScope, onConnected: () -> Unit) {
        VoxelsSync.startLive(
            scope = scope,
            channel = channel,
            onConnected = {
                onConnected()
                // A client announces itself (signed) so the host streams the world + snapshot.
                if (!isHost) {
                    val joinOps = JSONObject().put("type", "Join").put("device", myId)
                        .put("name", p.worldName).toString()
                    VoxelsSync.liveAppend(channel, key, listOf(signed(joinOps)))
                }
            },
            onMessage = { raw ->
                val msg = VoxelsSync.parseLive(raw)
                if (msg != null) when (msg.t) {
                    "actions" -> for (b in msg.actions) VoxelsSync.decrypt(key, b)?.let { handleSignedOp(scope, it) }
                    "presence" -> VoxelsSync.decrypt(key, msg.data)?.let { handlePresence(it) }
                }
            },
        )
    }

    // Ships everything the engine queued: transforms as presence, the rest as signed world ops.
    private suspend fun flushOutbound() {
        val out = try { VoxelsNative.netDrainOutbound() } catch (_: Exception) { "[]" }
        try {
            val arr = JSONArray(out)
            val ops = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("type") == "PlayerTransform") {
                    VoxelsSync.sendPresence(channel, key, JSONObject().put("from", myId).put("m", o).toString())
                } else {
                    ops.add(signed(o.toString()))
                }
            }
            if (ops.isNotEmpty()) VoxelsSync.liveAppend(channel, key, ops)
        } catch (_: Exception) {}
    }

    private suspend fun loop(onStatus: (NetStatus) -> Unit, onPeers: (String) -> Unit) = coroutineScope {
        roster.add(p.ownerDevice); roster.add(myId)
        VoxelsSync.publicBundle.let { bundles[myId] = it; bundles[p.ownerDevice] = it }
        if (isHost) refreshRoster()
        val everConnected = AtomicBoolean(false)
        startLive(this) { everConnected.set(true) }
        var tick = 0
        while (isActive) {
            tick++
            if (isHost && tick % ROSTER_EVERY == 0) refreshRoster()
            flushOutbound()
            onStatus(netStatusOf(VoxelsSync.isLive, everConnected.get()))
            onPeers(try { VoxelsNative.getPeersJson() } catch (_: Exception) { "[]" })
            delay(PUMP_MS)
        }
    }
}
