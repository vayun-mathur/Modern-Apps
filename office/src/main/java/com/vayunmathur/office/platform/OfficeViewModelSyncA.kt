package com.vayunmathur.office.platform

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.office.R
import kotlin.io.encoding.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

import com.vayunmathur.office.domain.DocumentTreeCrdt
import com.vayunmathur.office.sync.OfficeSync
import com.vayunmathur.office.sync.decrypt
import com.vayunmathur.office.sync.sign
import com.vayunmathur.office.sync.verify
// --- Sync A: identity + presence + live channel (split from OfficeViewModel.kt for file length) ---

/** True if this device's PQC keys and id are already stored (i.e. online sharing was enabled before). */
internal fun OfficeViewModel.hasOnlineIdentity(): Boolean {
    val ds = DataStoreUtils.getInstance(getApplication())
    return ds.getByteArray("officeKemPub") != null &&
        ds.getByteArray("officeDsaPub") != null &&
        ds.getString("officeDeviceId") != null
}

/** User opted into online sharing: generates keys + device id and registers the device. */
fun OfficeViewModel.enableOnlineSharing() {
    if (onlineEnabled.value) return
    onlineEnabledMutable.value = true // lets the gated initSync() below actually generate keys + register
    initSync()
}

/** Called by the editor when the local caret/selection moves, to broadcast presence. */
fun OfficeViewModel.setLocalCaret(offset: Int) {
    localCaret = offset
    broadcastPresence(typing = false)
}

/** Called by sheet/slide views when the focused cell/slide changes (location awareness). */
fun OfficeViewModel.setLocalLocation(loc: String?) {
    localLoc = loc
    broadcastPresence(typing = false)
}

internal fun OfficeViewModel.broadcastPresence(typing: Boolean) {
    val docId = currentDocId ?: return
    val key = currentDocKey ?: return
    caretPresenceJob?.cancel()
    caretPresenceJob = viewModelScope.launch(Dispatchers.IO) {
        delay(PRESENCE_DEBOUNCE_MS)
        runCatching {
            OfficeSync.sendPresence(docId, key, syncJson.encodeToString(
                OfficePresence(
                    OfficeSync.deviceId,
                    myName(),
                    typing = typing,
                    ts = System.currentTimeMillis(),
                    caret = localCaret,
                    loc = localLoc)
            ))
        }
    }
}

private const val PRESENCE_DEBOUNCE_MS = 120L
private const val LIVE_PUSH_DEBOUNCE_MS = 150L
private const val LIVE_POLL_MS = 1200L
private const val PRESENCE_TICK_MS = 1000L
private const val DEVICE_SUFFIX_LENGTH = 4

/** Display name broadcast to collaborators (device-derived; not sensitive). */
internal fun OfficeViewModel.myName(): String = "User " + OfficeSync.deviceId.takeLast(DEVICE_SUFFIX_LENGTH)

/** Called after a local edit: debounced live push + a "typing" presence ping (online docs only). */
internal fun OfficeViewModel.onLocalEdit() {
    val docId = currentDocId ?: return
    val key = currentDocKey ?: return
    if (applyingRemote) return
    viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            OfficeSync.sendPresence(docId, key, syncJson.encodeToString(
                OfficePresence(
                    OfficeSync.deviceId,
                    myName(),
                    typing = true,
                    ts = System.currentTimeMillis(),
                    caret = localCaret,
                    loc = localLoc)
            ))
        }
    }
    livePushJob?.cancel()
    livePushJob = viewModelScope.launch(Dispatchers.IO) {
        delay(LIVE_PUSH_DEBOUNCE_MS)
        runCatching { OfficeSync.init(getApplication()); syncDoc(docId, key) }
    }
}

/** Subscribes to the live channel for [docId], applying incoming ops/presence in real time. */
internal fun OfficeViewModel.startLive(docId: String, key: ByteArray) {
    OfficeSync.startLive(
        viewModelScope,
        docId,
        onConnected = { runCatching { syncDoc(docId, key) } }, // catch up on (re)connect
        onMessage = { raw -> handleLive(raw, docId, key) },
    )
    // Fallback poll: guarantees remote edits arrive even if the server doesn't push them over
    // the WebSocket. syncDoc is a no-op push when there are no local changes, then pulls+merges.
    livePollJob?.cancel()
    livePollJob = viewModelScope.launch(Dispatchers.IO) {
        while (isActive) {
            kotlinx.coroutines.delay(LIVE_POLL_MS)
            runCatching { syncDoc(docId, key) }
            runCatching { pollTitle(docId, key) } // pick up an owner rename ~live
            runCatching { pollDocDelete(docId, key) } // pick up an owner delete ~live
        }
    }
    // Presence upkeep: clear a peer's "typing…" a few seconds after they stop, and drop the whole
    // presence entry after 5 minutes of no cursor movement / typing.
    presenceTickJob?.cancel()
    presenceTickJob = viewModelScope.launch(Dispatchers.IO) {
        while (isActive) {
            kotlinx.coroutines.delay(PRESENCE_TICK_MS)
            val now = System.currentTimeMillis()
            val cur = remotePresenceMutable.value
            val next = cur.filter { now - it.ts < PRESENCE_TTL_MS }
                .map { if (it.typing && now - it.typingTs > TYPING_TTL_MS) it.copy(typing = false) else it }
            if (next != cur) remotePresenceMutable.value = next
        }
    }
}

/** Verifies an incoming signed op (author signature + editor/owner role) and applies it. */
internal suspend fun OfficeViewModel.applySignedOp(tree: DocumentTreeCrdt, plain: String): Boolean {
    val so = runCatching { syncJson.decodeFromString<SignedOp>(plain) }.getOrNull() ?: return false
    // Role gate: our own ops are always ours; others must be owner/editor per the signed roster.
    val allowed = so.author == OfficeSync.deviceId ||
        OfficeRoles.canEdit(currentMembers[so.author] ?: OfficeRoles.VIEWER)
    if (!allowed) return false
    val authorKey = memberKey(so.author) ?: return false
    // Collaborator-supplied sig may not be Base64; a bad sig is a reject, not a crash.
    val sigBytes = runCatching { Base64.decode(so.sig) }.getOrNull() ?: return false
    if (!OfficeSync.verify(authorKey, so.ops.encodeToByteArray(), sigBytes)) return false
    tree.applyJson(so.ops)
    return true
}

internal fun OfficeViewModel.handleLive(raw: String, docId: String, key: ByteArray) {
    val msg = OfficeSync.parseLive(raw) ?: return
    when (msg.t) {
        "actions" -> viewModelScope.launch(Dispatchers.IO) {
            syncMutex.withLock { applyLiveActions(msg, docId, key) }
        }
        "presence" -> applyLivePresence(msg, key)
    }
}

/** Merges locally-pending edits plus incoming live ops into the CRDT (caller holds [syncMutex]). */
internal suspend fun OfficeViewModel.applyLiveActions(msg: OfficeSync.LiveMsg, docId: String, key: ByteArray) {
    // A tombstoned doc ignores live ops: a peer that has not seen the
    // delete yet must not resurrect it through the live channel either.
    if (isDocTombstoned(DataStoreUtils.getInstance(getApplication()), docId)) return
    val tree = currentTree ?: return
    val startVersion = editVersion
    val localXml = exportFlat()
    // Fold pending local edits into the tree FIRST (and push them), so an incoming or
    // echoed op can't render a stale state and resurrect a char we just deleted.
    val opsJson = tree.updateJson(localXml)
    val hasLocal = opsJson != "[]"
    if (hasLocal && OfficeRoles.canEdit(currentRole)) pushLiveOps(docId, key, opsJson)
    var changed = hasLocal
    for (blob in msg.actions) {
        val plain = OfficeSync.decrypt(key, blob) ?: continue
        if (applySignedOp(tree, plain)) changed = true
    }
    if (changed) mergeLiveTree(docId, msg.seq, tree, localXml, startVersion)
}

/** Pushes local ops over the live channel, falling back to HTTP when the socket is down. */
internal suspend fun OfficeViewModel.pushLiveOps(docId: String, key: ByteArray, opsJson: String) {
    val sig = Base64.encode(OfficeSync.sign(opsJson.encodeToByteArray()))
    val signed = syncJson.encodeToString(SignedOp(OfficeSync.deviceId, sig, opsJson))
    val pushed = OfficeSync.liveAppend(docId, key, listOf(signed)) ||
        OfficeSync.appendDocActions(docId, key, listOf(signed)) != null
    if (!pushed) {
        // Same lost-ops guard as syncDoc: the tree has already absorbed
        // these ops, so stash the signed batch in the persisted pending
        // list for the next syncDoc cycle to retry verbatim.
        val ds = DataStoreUtils.getInstance(getApplication())
        val pending = loadPendingOps(ds, docId)
        if (signed !in pending) savePendingOps(ds, docId, pending + signed)
    }
}

/** Persists the merged tree and re-renders it into the editor when it changed. */
internal suspend fun OfficeViewModel.mergeLiveTree(
    docId: String,
    seq: Int,
    tree: DocumentTreeCrdt,
    localXml: String,
    startVersion: Int,
) {
    val ds = DataStoreUtils.getInstance(getApplication())
    ds.setLong("crdtCursor:$docId", seq.toLong())
    saveTree(ds, docId, tree)
    val merged = tree.render()
    if (merged == localXml) return
    val doc = parseFlat(merged) ?: return
    withContext(Dispatchers.Main) {
        // Don't clobber a keystroke the user made while we were merging.
        if (editVersion == startVersion) {
            applyingRemote = true
            updateDocument(doc)
            applyingRemote = false
        }
    }
}

internal fun OfficeViewModel.applyLivePresence(msg: OfficeSync.LiveMsg, key: ByteArray) {
    val plain = OfficeSync.decrypt(key, msg.data) ?: return
    val p = runCatching { syncJson.decodeFromString<OfficePresence>(plain) }.getOrNull() ?: return
    if (p.id == OfficeSync.deviceId) return
    val now = System.currentTimeMillis()
    val prev = remotePresenceMutable.value.firstOrNull { it.id == p.id }
    val typingTs = if (p.typing) now else (prev?.typingTs ?: 0L)
    // Keep peers for up to 5 minutes; the ticker prunes/clears typing over time.
    val others = remotePresenceMutable.value.filter { it.id != p.id && now - it.ts < PRESENCE_TTL_MS }
    remotePresenceMutable.value = others + p.copy(ts = now, typingTs = typingTs)
}
