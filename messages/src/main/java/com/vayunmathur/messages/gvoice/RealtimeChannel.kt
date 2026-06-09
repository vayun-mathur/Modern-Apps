package com.vayunmathur.messages.gvoice

import android.util.Log
import com.vayunmathur.messages.gmessages.PbLite
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import webchannel.Webchannel
import kotlin.random.Random

/**
 * Port of `pkg/libgv/channel.go`. Drives the BrowserChannel-based
 * realtime stream Google Voice uses to push new messages and updates.
 *
 * Two phases:
 *   1. [subscribe] — POST a hardcoded magic JSON literal to
 *      `chooseServer`, get back `gSessionID`. Then POST 7 hardcoded
 *      form-encoded event-subscriptions to the channel endpoint, get
 *      back the channel session ID.
 *   2. [run] — blocking loop. GETs the channel endpoint with the
 *      session id, reads framed [Utf16ChunkReader] chunks until EOF or
 *      error. Each chunk is a JSON array of events: noop events bump
 *      the ack counter; reconnect-marker events trigger a re-subscribe;
 *      everything else dispatches a `RealtimeEvent` upstream.
 *
 * Auto-reconnect on Unknown SID (up to 10 attempts with linear backoff),
 * forced re-subscribe every hour as a heartbeat.
 */
class RealtimeChannel(
    private val rpc: GVoiceRpcClient,
    private val onEvent: suspend (RealtimeEvent) -> Unit,
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope): Job {
        if (job?.isActive == true) return job!!
        job = scope.launch { runLoop() }
        return job!!
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun runLoop() {
        var backoffMs = 0L
        while (true) {
            if (backoffMs > 0) {
                Log.i(TAG, "reconnecting in ${backoffMs / 1000}s")
                delay(backoffMs)
            }
            val ok = try {
                run()
            } catch (t: Throwable) {
                Log.w(TAG, "realtime error: ${t.message}")
                false
            }
            if (!kotlin.coroutines.coroutineContext.isActive) return
            backoffMs = if (ok) 0L else (if (backoffMs == 0L) 5_000L else minOf(60_000L, backoffMs * 2))
        }
    }

    private suspend fun run(): Boolean {
        var subscription = subscribe() ?: return false
        var ackId = 0L
        var failedRequests = 0
        var lastResubscribeMs = System.currentTimeMillis()
        var anyEventSeen = false

        while (true) {
            if (System.currentTimeMillis() - lastResubscribeMs > FORCE_RESUBSCRIBE_INTERVAL_MS) {
                Log.i(TAG, "force re-subscribe (interval elapsed)")
                subscription = subscribe() ?: return anyEventSeen
                ackId = 0
                lastResubscribeMs = System.currentTimeMillis()
            }

            val query = mapOf(
                "VER" to "8",
                "gsessionid" to subscription.gSessionId,
                "RID" to "rpc",
                "SID" to subscription.sessionId,
                "AID" to ackId.toString(),
                "CI" to "0",
                "TYPE" to "xmlhttp",
                "t" to "1",
            )
            Log.d(TAG, "open long-poll AID=$ackId SID=${subscription.sessionId}")

            val outcome = rpc.getStreaming(
                VoiceEndpoints.EndpointRealtimeChannel,
                extraQuery = query,
            ) { resp ->
                if (resp.status.value !in 200..299) {
                    val body = runCatching { resp.bodyAsBytes() }.getOrNull()
                        ?.let { String(it, Charsets.UTF_8) } ?: ""
                    if (body.contains("Unknown SID")) {
                        ChannelOutcome.UnknownSid
                    } else {
                        Log.w(TAG, "long-poll HTTP ${resp.status.value}; body=${body.take(200)}")
                        ChannelOutcome.Error(resp.status.value)
                    }
                } else {
                    if (failedRequests > 0 || ackId == 0L) onEvent(RealtimeEvent.Connected)
                    val newAckId = readChunks(resp, ackId).also { anyEventSeen = anyEventSeen || it.eventCount > 0 }
                    ackId = newAckId.lastAckId
                    if (newAckId.needResubscribe) {
                        ChannelOutcome.Reconnect
                    } else {
                        ChannelOutcome.Ok
                    }
                }
            }

            when (outcome) {
                ChannelOutcome.UnknownSid -> {
                    failedRequests++
                    if (failedRequests > 10) {
                        Log.e(TAG, "too many Unknown SID errors")
                        return anyEventSeen
                    }
                    val sleep = ((failedRequests - 1) * 2_000L).coerceAtLeast(0)
                    if (sleep > 0) delay(sleep)
                    subscription = subscribe() ?: return anyEventSeen
                    ackId = 0
                    lastResubscribeMs = System.currentTimeMillis()
                }
                ChannelOutcome.Reconnect -> {
                    subscription = subscribe() ?: return anyEventSeen
                    ackId = 0
                    lastResubscribeMs = System.currentTimeMillis()
                }
                is ChannelOutcome.Error -> return anyEventSeen
                ChannelOutcome.Ok -> {
                    failedRequests = 0
                }
            }
        }
    }

    private sealed interface ChannelOutcome {
        data object Ok : ChannelOutcome
        data object UnknownSid : ChannelOutcome
        data object Reconnect : ChannelOutcome
        data class Error(val status: Int) : ChannelOutcome
    }

    private data class ReadOutcome(val lastAckId: Long, val needResubscribe: Boolean, val eventCount: Int)

    private suspend fun readChunks(resp: HttpResponse, startAckId: Long): ReadOutcome {
        var ackId = startAckId
        var needResubscribe = false
        var eventCount = 0
        val reader = Utf16ChunkReader(resp.bodyAsChannel())
        while (true) {
            val chunk = reader.readChunk() ?: break
            // Each chunk is a top-level JSON array of entries.
            val chunkStr = String(chunk, Charsets.UTF_8)
            val entries = splitTopLevelJsonArray(chunkStr) ?: run {
                Log.w(TAG, "couldn't split chunk into entries: ${chunkStr.take(2000)}")
                continue
            }
            for (entry in entries) {
                eventCount++
                if (entry.endsWith(NOOP_SUFFIX)) {
                    // Pure-noop: just bump ackId.
                    runCatching {
                        PbLite.decode<Webchannel.WebChannelNoopEvent>(
                            entry, Webchannel.WebChannelNoopEvent.newBuilder(),
                        )
                    }.getOrNull()?.let { ackId = it.arrayID }
                    continue
                }
                val parsed = runCatching {
                    PbLite.decode<Webchannel.WebChannelEvent>(
                        entry, Webchannel.WebChannelEvent.newBuilder(),
                    )
                }.getOrNull()
                if (parsed == null) {
                    Log.w(TAG, "couldn't parse channel entry: ${entry.take(2000)}")
                    continue
                }
                // Reconnect-marker check: a single dataWrapper with altData.reconnect == true.
                if (parsed.dataWrapperCount == 1 &&
                    parsed.getDataWrapper(0).hasAltData() &&
                    parsed.getDataWrapper(0).altData.reconnect
                ) {
                    Log.i(TAG, "got reconnect marker; will re-subscribe")
                    needResubscribe = true
                    break
                }
                ackId = parsed.arrayID
                onEvent(RealtimeEvent.Data(parsed))
            }
            if (needResubscribe) break
        }
        return ReadOutcome(ackId, needResubscribe, eventCount)
    }

    /** Splits a top-level JSON array string `[a, b, c]` into its
     *  element substrings without instantiating a full JSON tree. Used
     *  because we want to PbLite-decode each entry individually. */
    private fun splitTopLevelJsonArray(input: String): List<String>? {
        val s = input.trim()
        if (!s.startsWith("[") || !s.endsWith("]")) return null
        val out = mutableListOf<String>()
        var depth = 0
        var inString = false
        var escape = false
        var start = 1
        for (i in 1 until s.length - 1) {
            val c = s[i]
            when {
                escape -> escape = false
                c == '\\' && inString -> escape = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '[' || c == '{' -> depth++
                c == ']' || c == '}' -> depth--
                c == ',' && depth == 0 -> {
                    out += s.substring(start, i).trim()
                    start = i + 1
                }
            }
        }
        val last = s.substring(start, s.length - 1).trim()
        if (last.isNotEmpty()) out += last
        return out
    }

    // ----------------------------------------------------------------
    // Subscribe: chooseServer + createChannel
    // ----------------------------------------------------------------

    private data class Subscription(val gSessionId: String, val sessionId: String)

    private suspend fun subscribe(): Subscription? = try {
        Log.i(TAG, "subscribe: chooseServer")
        // The hardcoded literal libgv uses for chooseServer.
        val chooseResp = rpc.postRawPbLite(
            url = VoiceEndpoints.EndpointRealtimeChooseServer,
            jsonBody = REQ_CHOOSE_SERVER,
            responseTemplate = Webchannel.RespChooseServer.getDefaultInstance(),
        )
        val gSessionId = chooseResp.gSessionID
        Log.i(TAG, "subscribe: gSessionID=$gSessionId")

        val rid = Random.nextInt(0, 100_000)
        val createUrl = with(io.ktor.http.URLBuilder(VoiceEndpoints.EndpointRealtimeChannel)) {
            parameters.append("VER", "8")
            parameters.append("gsessionid", gSessionId)
            parameters.append("RID", rid.toString())
            parameters.append("CVER", "22")
            parameters.append("t", "1")
            buildString()
        }
        val form = mapOf(
            "count" to "7",
            "ofs" to "0",
            "req0___data__" to "[[[\"1\",[null,null,null,[7,5],null,[null,[null,1],[[[\"2\"]]]],null,1,2],null,3]]]",
            "req1___data__" to "[[[\"2\",[null,null,null,[7,5],null,[null,[null,1],[[[\"3\"]]]],null,1,2],null,3]]]",
            "req2___data__" to "[[[\"3\",[null,null,null,[7,5],null,[null,[null,1],[[[\"3\"]]]],null,1,2],null,3]]]",
            "req3___data__" to "[[[\"4\",[null,null,null,[7,5],null,[null,[null,1],[[[\"1\"]]]],null,1,2],null,3]]]",
            "req4___data__" to "[[[\"5\",[null,null,null,[7,5],null,[null,[null,1],[[[\"1\"]]]],null,1,2],null,3]]]",
            "req5___data__" to "[[[\"6\",[null,null,null,[7,5],null,[null,[null,1],[[[\"1\"]]]],null,1,2],null,3]]]",
            "req6___data__" to "[[[\"9\",[null,null,null,[7,5],null,[null,[null,1],[[[\"1\"]]]],null,1,2],null,3]]]",
        )
        val createResp = rpc.postForm(createUrl, form)
        if (createResp.status.value !in 200..299) {
            Log.e(TAG, "createChannel HTTP ${createResp.status.value}")
            return null
        }
        // createChannel's response uses the same utf16chunk safety
        // framing the other punctual endpoints do — the body is
        // `<utf16-length>\n<pblite-json>` rather than just the JSON.
        // Detect via X-Goog-Safety-Content-Type and unwrap when needed.
        val safetyMime = createResp.headers["X-Goog-Safety-Content-Type"]
            ?.substringBefore(';')?.trim().orEmpty().lowercase()
        val plainMime = createResp.headers["Content-Type"]
            ?.substringBefore(';')?.trim().orEmpty().lowercase()
        val realMime = safetyMime.ifEmpty { plainMime }
        val raw: ByteArray = if (realMime == "text/plain") {
            Utf16ChunkReader(createResp.bodyAsChannel()).readChunk()
                ?: error("createChannel: empty utf16chunk body")
        } else {
            createResp.bodyAsBytes()
        }
        val parsed = PbLite.decode<Webchannel.RespCreateChannel>(
            String(raw, Charsets.UTF_8),
            Webchannel.RespCreateChannel.newBuilder(),
        )
        val sessionId = parsed.data.session.sessionID
        Log.i(TAG, "subscribe: sessionID=$sessionId")
        Subscription(gSessionId = gSessionId, sessionId = sessionId)
    } catch (t: Throwable) {
        Log.e(TAG, "subscribe failed: ${t.message}", t)
        null
    }

    companion object {
        private const val TAG = "GVoice/Realtime"
        private const val FORCE_RESUBSCRIBE_INTERVAL_MS = 60L * 60L * 1000L
        private const val NOOP_SUFFIX = ",[\"noop\"]]"
        private const val REQ_CHOOSE_SERVER =
            "[[null,null,null,[7,5],null,[null,[null,1],[[[\"3\"]]]]]]"
    }
}

/** Events the realtime channel surfaces. */
sealed interface RealtimeEvent {
    data object Connected : RealtimeEvent
    data class Data(val event: Webchannel.WebChannelEvent) : RealtimeEvent
}
