package com.vayunmathur.youpipe.util.sabr

import android.util.Base64
import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrMediaHeader
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrMediaProtocol
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrContextSendingPolicy
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrContextUpdate
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrFormatInitializationMetadata
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrLiveMetadata
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrNextRequestPolicy
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrProtocolException
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrResponseStatePatch
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrScriptPolicy
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSessionPolicy

class QuickJsSabrSessionPolicy @Throws(SabrProtocolException::class) constructor(
    script: SabrScriptPolicy,
) : SabrSessionPolicy {
    private var sessionId = -1
    private val mediaProtocol: SabrMediaProtocol
    private val scriptedDemand: Boolean
    private var closed = false

    init {
        try {
            sessionId = QuickJsSabrRuntime.createSession(script)
            val description = invokeObject("describe", JsonObject())
            scriptedDemand = description.getBoolean("demand", false)
            val media = description.getObject("media")
                ?: throw SabrProtocolException("QuickJS policy has no media protocol")
            mediaProtocol = ScriptMediaProtocol(
                media.getInt("headerType"),
                media.getInt("mediaType"),
                media.getInt("endType"),
                media.getString("headerDecoder") == "builtin",
            )
        } catch (error: SabrProtocolException) {
            closeCreatedSession()
            throw error
        } catch (error: Exception) {
            closeCreatedSession()
            throw SabrProtocolException("Could not initialize SABR QuickJS policy", error)
        }
    }

    override fun getMediaProtocol(): SabrMediaProtocol = mediaProtocol

    @Synchronized
    override fun evaluateDemandRoute(
        event: SabrSessionPolicy.DemandRouteEvent,
    ): SabrSessionPolicy.DemandRoute {
        if (!scriptedDemand) return super<SabrSessionPolicy>.evaluateDemandRoute(event)
        val output = invokeObject("demandRoute", demandInput(event))
        return try {
            SabrSessionPolicy.DemandRoute.valueOf(
                output.getString("route")
                    ?: throw SabrProtocolException("QuickJS demand policy returned no route"),
            )
        } catch (error: IllegalArgumentException) {
            throw SabrProtocolException("QuickJS demand policy returned unknown route", error)
        }
    }

    @Synchronized
    override fun evaluateDemandResponse(
        event: SabrSessionPolicy.DemandResponseEvent,
    ): SabrSessionPolicy.DemandResponseDecision {
        if (!scriptedDemand) return super<SabrSessionPolicy>.evaluateDemandResponse(event)
        val input = demandInput(event)
        input["segmentCount"] = event.segmentCount
        input["targetTrackSegmentCount"] = event.targetTrackSegmentCount
        input["returnedSegmentsTruncated"] = event.areReturnedSegmentsTruncated()
        val returned = JsonArray()
        for (segment in event.returnedSegments) {
            returned.add(JsonObject().apply {
                this["itag"] = segment.itag
                this["sequenceNumber"] = segment.sequenceNumber
                this["startMs"] = segment.startMs
                this["durationMs"] = segment.durationMs
            })
        }
        input["returnedSegments"] = returned
        val output = invokeObject("demandResponse", input)
        val outcome = try {
            SabrSessionPolicy.DemandOutcome.valueOf(
                output.getString("outcome")
                    ?: throw SabrProtocolException("QuickJS demand policy returned no outcome"),
            )
        } catch (error: IllegalArgumentException) {
            throw SabrProtocolException("QuickJS demand policy returned unknown outcome", error)
        }
        val retryDelayMs = output.getInt("retryDelayMs", 0)
        if (retryDelayMs !in 0..SabrSessionPolicy.MAX_DEMAND_RETRY_DELAY_MS ||
            outcome != SabrSessionPolicy.DemandOutcome.CONTINUE && retryDelayMs != 0
        ) {
            throw SabrProtocolException("QuickJS demand policy returned invalid retry delay")
        }
        return SabrSessionPolicy.DemandResponseDecision(
            outcome,
            retryDelayMs,
        )
    }

    @Synchronized
    override fun evaluate(
        state: SabrSessionPolicy.State,
        event: SabrSessionPolicy.Event,
    ): SabrSessionPolicy.Result {
        ensureOpen()
        if (event is SabrSessionPolicy.RequestEvent) {
            val input = stateJson(state)
            input["playerTimeMs"] = event.playerTimeMs
            input["bufferedEdgeMs"] = event.bufferedEdgeMs
            input["poTokenBytes"] = event.poTokenBytes
            input["bufferedRangeCount"] = event.bufferedRangeCount
            input["fallbackBody"] = Base64.encodeToString(event.proposedBody, Base64.NO_WRAP)
            val output = invokeObject(
                if (state.requestNumber == 0) "initialRequest" else "followUpRequest",
                input,
            )
            val body = output.getString("body")
                ?: throw SabrProtocolException("QuickJS policy returned no request body")
            val bytes = try {
                Base64.decode(body, Base64.DEFAULT)
            } catch (error: IllegalArgumentException) {
                throw SabrProtocolException("Invalid QuickJS request body", error)
            }
            return SabrSessionPolicy.Result.request(
                state,
                if (state.requestNumber == 0) {
                    SabrSessionPolicy.ActionType.SEND_INITIAL_REQUEST
                } else {
                    SabrSessionPolicy.ActionType.SEND_FOLLOW_UP_REQUEST
                },
                bytes,
            )
        }
        return control(state, event as SabrSessionPolicy.ControlResponseEvent)
    }

    private fun control(
        state: SabrSessionPolicy.State,
        event: SabrSessionPolicy.ControlResponseEvent,
    ): SabrSessionPolicy.Result {
        val input = stateJson(state)
        input["segmentCount"] = event.segmentCount
        input["honorBackoff"] = event.shouldHonorBackoff()
        input["mode"] = event.mode.name
        val parts = JsonArray()
        for (part in event.response.parts) {
            if (part.type == mediaProtocol.mediaPartType) continue
            val item = JsonObject()
            item["type"] = part.type
            item["data"] = Base64.encodeToString(part.data, Base64.NO_WRAP)
            parts.add(item)
        }
        input["parts"] = parts
        val builtin = JsonObject()
        builtin["error"] = event.response.sabrErrorDetails != null
        builtin["reload"] = event.response.isReloadRequested
        builtin["protection"] = event.response.isProtectionBoundaryNoMediaResponse
        builtin["redirectUrl"] = event.response.redirectUrl
        builtin["backoffMs"] = maxOf(0, event.response.backoffTimeMs)
        input["builtin"] = builtin

        val output = invokeObject("response", input)
        val outputActions = output.getArray("actions")
        if (outputActions == null || outputActions.isEmpty()) {
            throw SabrProtocolException("QuickJS policy returned no actions")
        }
        val actions = ArrayList<SabrSessionPolicy.Action>(outputActions.size)
        for (index in outputActions.indices) {
            try {
                actions.add(
                    SabrSessionPolicy.Action(
                        SabrSessionPolicy.ActionType.valueOf(outputActions.getString(index)),
                    ),
                )
            } catch (error: RuntimeException) {
                throw SabrProtocolException("QuickJS policy returned unknown action", error)
            }
        }
        val next = output.getObject("state")
        val nextState = if (next == null) state else SabrSessionPolicy.State(
            state.requestNumber,
            next.getInt("redirectCount", state.redirectCount),
            next.getInt("poTokenRefreshes", state.poTokenRefreshes),
            state.reloads,
        )
        return SabrSessionPolicy.Result.control(
            nextState,
            actions,
            SabrSessionPolicy.ControlDecision(
                output.getInt("backoffMs", 0),
                output.getString("redirectUrl"),
                output.getString("errorDetails"),
            ),
            if (output.has("statePatch")) {
                parseStatePatch(output.getObject("statePatch"), event)
            } else {
                null
            },
        )
    }

    private fun parseStatePatch(
        value: JsonObject?,
        event: SabrSessionPolicy.ControlResponseEvent,
    ): SabrResponseStatePatch? {
        if (value == null) return null
        val builder = SabrResponseStatePatch.builder()
        val next = value.getObject("nextRequest")
        if (next != null) {
            builder.setNextRequestPolicy(
                SabrNextRequestPolicy.normalized(
                    next.getInt("targetAudioReadaheadMs", -1),
                    next.getInt("targetVideoReadaheadMs", -1),
                    next.getInt("maxTimeSinceLastRequestMs", -1),
                    next.getInt("backoffTimeMs", -1),
                    next.getInt("minAudioReadaheadMs", -1),
                    next.getInt("minVideoReadaheadMs", -1),
                    decodeOptional(next.getString("playbackCookie")),
                    next.getString("videoId"),
                ),
            )
        }
        val live = value.getArray("live")
        if (live != null) {
            for (index in live.indices) {
                val item = live.getObject(index)
                builder.addLiveMetadata(
                    SabrLiveMetadata.normalized(
                        item.getString("broadcastId"),
                        item.getLong("headSequenceNumber", -1),
                        item.getLong("headTimeMs", -1),
                        item.getLong("wallTimeMs", -1),
                        item.getString("videoId"),
                        item.getBoolean("postLiveDvr", false),
                        item.getLong("headm", -1),
                        item.getLong("minSeekableTimeTicks", -1),
                        item.getInt("minSeekableTimescale", -1),
                        item.getLong("maxSeekableTimeTicks", -1),
                        item.getInt("maxSeekableTimescale", -1),
                    ),
                )
            }
        }
        val formats = value.getArray("formats")
        if (formats != null) {
            for (index in formats.indices) {
                val item = formats.getObject(index)
                builder.addFormatMetadata(
                    SabrFormatInitializationMetadata.normalized(
                        item.getString("videoId"),
                        item.getInt("itag", -1),
                        item.getLong("lastModified", -1),
                        item.getString("xtags"),
                        item.getLong("endTimeMs", -1),
                        item.getLong("endSegmentNumber", -1),
                        item.getString("mimeType"),
                        item.getLong("initRangeStart", -1),
                        item.getLong("initRangeEnd", -1),
                        item.getLong("indexRangeStart", -1),
                        item.getLong("indexRangeEnd", -1),
                        item.getLong("field8", -1),
                        item.getLong("durationUnits", -1),
                        item.getLong("durationTimescale", -1),
                    ),
                )
            }
        }
        val contexts = value.getArray("contexts")
        if (contexts != null) {
            for (index in contexts.indices) {
                val item = contexts.getObject(index)
                builder.addContextUpdate(
                    SabrContextUpdate.normalized(
                        item.getInt("type", -1),
                        item.getInt("scope", -1),
                        decodeRequired(item.getString("value"), "context value"),
                        item.getBoolean("sendByDefault", false),
                        item.getInt("writePolicy", -1),
                    ),
                )
            }
        }
        val contextPolicy = value.getObject("contextPolicy")
        if (contextPolicy != null) {
            builder.setContextSendingPolicy(
                SabrContextSendingPolicy.normalized(
                    intList(contextPolicy.getArray("start")),
                    intList(contextPolicy.getArray("stop")),
                    intList(contextPolicy.getArray("discard")),
                ),
            )
        }
        for (header in event.response.mediaHeaders) builder.addMediaHeader(header)
        return builder.build()
    }

    private fun intList(values: JsonArray?): List<Int> {
        if (values == null) return emptyList()
        return values.indices.map { values.getInt(it) }
    }

    private fun decodeOptional(value: String?): ByteArray? =
        value?.let { decodeRequired(it, "optional bytes") }

    private fun decodeRequired(value: String?, name: String): ByteArray {
        if (value == null) throw SabrProtocolException("QuickJS policy returned no $name")
        return try {
            Base64.decode(value, Base64.DEFAULT)
        } catch (error: IllegalArgumentException) {
            throw SabrProtocolException("QuickJS policy returned invalid $name", error)
        }
    }

    @Synchronized
    private fun invokeObject(method: String, input: JsonObject): JsonObject {
        ensureOpen()
        return QuickJsSabrRuntime.invoke(sessionId, method, input)
    }

    private fun stateJson(state: SabrSessionPolicy.State) = JsonObject().apply {
        this["requestNumber"] = state.requestNumber
        this["redirectCount"] = state.redirectCount
        this["poTokenRefreshes"] = state.poTokenRefreshes
        this["reloads"] = state.reloads
    }

    private fun demandInput(event: SabrSessionPolicy.DemandEvent) = JsonObject().apply {
        this["targetItag"] = event.targetItag
        this["targetSequenceNumber"] = event.targetSequenceNumber
        this["targetStartMs"] = event.targetStartMs
        this["bufferedEdgeMs"] = event.bufferedEdgeMs
        this["createdAtMs"] = event.state.createdAtMs
        this["nowMs"] = event.state.nowMs
        this["elapsedMs"] = event.state.elapsedMs
        this["responsesWithoutDemandedSegment"] =
            event.state.responsesWithoutDemandedSegment
        this["recoveryCount"] = event.state.recoveryCount
    }

    private fun ensureOpen() {
        if (closed) throw SabrProtocolException("SABR QuickJS policy is closed")
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            closeCreatedSession()
        }
    }

    private fun closeCreatedSession() {
        if (sessionId >= 0) {
            QuickJsSabrRuntime.closeSession(sessionId)
            sessionId = -1
        }
    }

    private inner class ScriptMediaProtocol(
        private val headerType: Int,
        private val mediaType: Int,
        private val endType: Int,
        private val builtinHeaderDecoder: Boolean,
    ) : SabrMediaProtocol {
        init {
            if (headerType < 0 || mediaType < 0 || endType < 0 ||
                headerType == mediaType || headerType == endType || mediaType == endType
            ) {
                throw IllegalArgumentException("Invalid QuickJS media protocol types")
            }
        }

        override fun getHeaderPartType() = headerType
        override fun getMediaPartType() = mediaType
        override fun getEndPartType() = endType

        override fun decodeHeader(payload: ByteArray): SabrMediaHeader {
            if (builtinHeaderDecoder) return SabrMediaProtocol.builtin().decodeHeader(payload)
            val input = JsonObject()
            input["data"] = Base64.encodeToString(payload, Base64.NO_WRAP)
            val value = invokeObject("mediaHeader", input)
            val headerId = value.getInt("headerId", -1)
            val itag = value.getInt("itag", -1)
            if (headerId !in 0..255 || itag <= 0) {
                throw SabrProtocolException("QuickJS policy returned invalid media header identity")
            }
            return SabrMediaHeader.normalized(
                headerId,
                value.getString("videoId"),
                itag,
                value.getLong("lastModified", -1),
                value.getString("xtags"),
                value.getLong("startRange", -1),
                value.getInt("compressionAlgorithm", -1),
                value.getBoolean("initSegment", false),
                value.getInt("sequenceNumber", -1),
                value.getLong("bitrateBps", -1),
                value.getLong("startMs", -1),
                value.getLong("durationMs", -1),
                value.getLong("contentLength", -1),
                value.getLong("timeRangeStartTicks", -1),
                value.getLong("timeRangeDurationTicks", -1),
                value.getInt("timeRangeTimescale", -1),
                value.getLong("sequenceLastModified", -1),
            )
        }
    }

}
