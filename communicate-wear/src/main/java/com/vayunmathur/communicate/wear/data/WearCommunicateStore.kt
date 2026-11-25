package com.vayunmathur.communicate.wear.data

import android.content.Context
import com.vayunmathur.communicate.wear.domain.WearCommOp
import com.vayunmathur.communicate.wear.domain.WearCommunicateSnapshot
import com.vayunmathur.communicate.wear.domain.WearIncomingCall
import com.vayunmathur.communicate.wear.domain.WearMessage
import com.vayunmathur.communicate.wear.domain.WearNewMessageAlert
import com.vayunmathur.communicate.wear.domain.WearCommunicateProtocol
import com.vayunmathur.communicate.wear.domain.WearOpKind
import com.vayunmathur.communicate.wear.domain.WearOpResult
import com.vayunmathur.communicate.wear.domain.WearThread
import com.vayunmathur.communicate.wear.domain.WearThreadPage
import com.vayunmathur.communicate.wear.domain.WearVoicemail
import com.vayunmathur.communicate.wear.domain.mergeWearThreadPage
import com.vayunmathur.communicate.wear.domain.pendingAfterResult
import com.vayunmathur.library.log.Log
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** Cached pages for one thread, persisted so threads read fully offline. */
@Serializable
data class WearThreadCache(
    val messages: List<WearMessage> = emptyList(),
    val hasMore: Boolean = false,
    val oldestTimestampMillis: Long = 0L,
)

/**
 * The watch's local truth: the last phone snapshot, paged thread caches,
 * and pending ops, persisted as JSON in app-private storage. An op stays
 * pending (persisted, resent on reconnect) until the phone's op-result
 * says it succeeded — the UI never claims the phone did something before
 * that verdict.
 */
class WearCommunicateStore private constructor(private val context: Context) {

    companion object {
        private const val TAG = "WearCommunicateStore"

        @Volatile
        private var instance: WearCommunicateStore? = null

        fun get(context: Context): WearCommunicateStore = instance ?: synchronized(this) {
            instance ?: WearCommunicateStore(context.applicationContext).also { instance = it }
        }
    }

    private val snapshotFile = File(context.filesDir, "wear-communicate-snapshot.json")
    private val pagesFile = File(context.filesDir, "wear-communicate-pages.json")
    private val pendingFile = File(context.filesDir, "wear-communicate-pending.json")

    private val _snapshot = MutableStateFlow(loadSnapshot())
    val snapshot: StateFlow<WearCommunicateSnapshot> = _snapshot.asStateFlow()

    private val _pages = MutableStateFlow(loadPages())
    val pages: StateFlow<Map<String, WearThreadCache>> = _pages.asStateFlow()

    private val _pendingOps = MutableStateFlow(loadPending())
    val pendingOps: StateFlow<List<WearCommOp>> = _pendingOps.asStateFlow()

    private val _incomingCall = MutableStateFlow<WearIncomingCall?>(null)
    val incomingCall: StateFlow<WearIncomingCall?> = _incomingCall.asStateFlow()

    private val _lastAlert = MutableStateFlow<WearNewMessageAlert?>(null)
    val lastAlert: StateFlow<WearNewMessageAlert?> = _lastAlert.asStateFlow()

    val appliedGeneratedAtMillis: Long get() = _snapshot.value.generatedAtMillis

    fun setSnapshot(snapshot: WearCommunicateSnapshot) {
        _snapshot.value = snapshot
        persist(snapshotFile, WearCommunicateProtocol.json.encodeToString(snapshot))
        com.vayunmathur.communicate.wear.sync.WearLinkBridge.publishWatchState(context)
    }

    /** Merges one arrived page into the thread cache (evicting the least recent threads). */
    fun setThreadPage(page: WearThreadPage) {
        val current = _pages.value.toMutableMap()
        val existing = current[page.threadKey] ?: WearThreadCache()
        current[page.threadKey] = WearThreadCache(
            messages = mergeWearThreadPage(existing.messages, page),
            hasMore = page.hasMore,
            oldestTimestampMillis = page.oldestTimestampMillis,
        )
        // Bound the cache: keep the most recently paged threads.
        val capped = if (current.size <= WearCommunicateProtocol.MAX_CACHED_THREADS) {
            current
        } else {
            current.entries.toList().takeLast(WearCommunicateProtocol.MAX_CACHED_THREADS)
                .associate { it.key to it.value }
        }
        _pages.value = capped
        persist(pagesFile, WearCommunicateProtocol.json.encodeToString(capped))
    }

    fun setIncomingCall(call: WearIncomingCall?) {
        _incomingCall.value = call
    }

    fun setAlert(alert: WearNewMessageAlert) {
        _lastAlert.value = alert
    }

    fun clearAlert() {
        _lastAlert.value = null
    }

    /** Applies a phone verdict: success drops the op, failure keeps it pending. */
    fun applyOpResult(result: WearOpResult) {
        val updated = pendingAfterResult(_pendingOps.value, result)
        if (updated != _pendingOps.value) {
            _pendingOps.value = updated
            persistPending()
        }
        if (result.kind == WearOpKind.DeclineCall && result.success) _incomingCall.value = null
    }

    fun queueReply(thread: WearThread, body: String): WearCommOp? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        return queue(
            WearCommOp(
                opId = UUID.randomUUID().toString(),
                kind = WearOpKind.QuickReply,
                threadKey = thread.key,
                line = thread.line,
                threadId = thread.threadId,
                remoteId = thread.remoteId,
                address = thread.address,
                subscriptionId = thread.subscriptionId,
                participants = thread.participants,
                body = trimmed.take(WearCommunicateProtocol.MAX_REPLY_CHARS),
            ),
        )
    }

    fun queueMarkRead(thread: WearThread): WearCommOp = queue(
        WearCommOp(
            opId = UUID.randomUUID().toString(),
            kind = WearOpKind.MarkRead,
            threadKey = thread.key,
            line = thread.line,
            threadId = thread.threadId,
            remoteId = thread.remoteId,
            address = thread.address,
        ),
    )

    fun queueMarkVoicemailRead(voicemail: WearVoicemail): WearCommOp = queue(
        WearCommOp(
            opId = UUID.randomUUID().toString(),
            kind = WearOpKind.MarkVoicemailRead,
            line = voicemail.line,
            targetKey = voicemail.key,
        ),
    )

    fun queueDeclineCall(call: WearIncomingCall): WearCommOp = queue(
        WearCommOp(
            opId = UUID.randomUUID().toString(),
            kind = WearOpKind.DeclineCall,
            line = call.line,
            targetKey = call.callKey,
        ),
    )

    private fun queue(op: WearCommOp): WearCommOp {
        _pendingOps.value = _pendingOps.value + op
        persistPending()
        com.vayunmathur.communicate.wear.sync.WearLinkBridge.flushPending(context)
        return op
    }

    private fun loadSnapshot(): WearCommunicateSnapshot = runCatching {
        WearCommunicateProtocol.json.decodeFromString<WearCommunicateSnapshot>(snapshotFile.readText())
    }.getOrDefault(WearCommunicateSnapshot())

    private fun loadPages(): Map<String, WearThreadCache> = runCatching {
        WearCommunicateProtocol.json.decodeFromString<Map<String, WearThreadCache>>(pagesFile.readText())
    }.getOrDefault(emptyMap())

    private fun loadPending(): List<WearCommOp> = runCatching {
        WearCommunicateProtocol.json.decodeFromString<List<WearCommOp>>(pendingFile.readText())
    }.getOrDefault(emptyList())

    private fun persistPending() {
        persist(pendingFile, WearCommunicateProtocol.json.encodeToString(_pendingOps.value))
    }

    private fun persist(file: File, text: String) {
        runCatching { file.writeText(text) }
            .onFailure { Log.status(TAG, "persist ${file.name} failed: ${it.message}") }
    }
}
