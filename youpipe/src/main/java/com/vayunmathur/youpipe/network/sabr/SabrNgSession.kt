package com.vayunmathur.youpipe.network.sabr

import com.vayunmathur.library.log.Log
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrInfo
import org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrRequest
import org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrSession
import org.schabi.newpipe.extractor.services.youtube.sabrng.media.SabrMediaSegment
import org.schabi.newpipe.extractor.services.youtube.sabrng.protocol.SabrStreamingResponseReader
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Drives a session-based ([YoutubeSabrSession]) SABR stream for media3 playback: a background pump
 * repeatedly issues requests (through [SabrRequestCoordinator], which retries the attestation
 * handshake by re-minting the PO token via [tokenMinter] and honors server backoff) around the
 * current playhead, caches emitted [SabrMediaSegment]s by (itag, sequence), and serves them to the
 * media3 data source through the blocking [getMediaSegment].
 */
class SabrNgSession(
    private val spec: SabrNgSourceSpec,
    spoolDirectory: File?,
    tokenMinter: ((Boolean) -> ByteArray?)? = null,
    // Nullable result: a missing/dead player read must NOT coalesce to 0 and shadow the
    // segment-tracked fallback. `() -> Long` lambdas remain source-compatible (covariant).
    private val playheadMsProvider: (() -> Long?)? = null,
) {
    private val session: YoutubeSabrSession = newSession(spec.info, spoolDirectory, spec.poToken)
    private val coordinator = SabrRequestCoordinator(
        session, SabrAttestationRetryHandler(spec.videoId, tokenMinter), null
    )

    private val mediaCache = ConcurrentHashMap<Long, SabrMediaSegment>()
    private val initCache = ConcurrentHashMap<Int, SabrMediaSegment>()
    private val lock = Object()

    @Volatile
    private var audioBufferedThrough = 0

    @Volatile
    private var videoBufferedThrough = 0

    @Volatile
    private var playerTimeMs = 0L

    @Volatile
    private var playbackRate = 1.0f

    @Volatile
    private var running = false

    @Volatile
    private var terminal: IOException? = null

    private var pumpThread: Thread? = null

    @Volatile
    private var lastStateLogMs = 0L

    @Volatile
    private var lastDeadSupplierLogMs = 0L

    private fun newSession(
        info: YoutubeSabrInfo,
        spoolDirectory: File?,
        poToken: ByteArray?
    ): YoutubeSabrSession {
        val created = YoutubeSabrSession(info, spoolDirectory)
        if (poToken != null && poToken.isNotEmpty()) {
            created.setPoToken(poToken)
        }
        return created
    }

    @Synchronized
    fun start() {
        if (running) {
            return
        }
        running = true
        val thread = Thread({ pumpLoop() }, "SabrNgSession-${spec.videoId}")
        thread.isDaemon = true
        pumpThread = thread
        thread.start()
    }

    @Synchronized
    fun stop() {
        running = false
        pumpThread?.interrupt()
        pumpThread = null
        synchronized(lock) { lock.notifyAll() }
        for (segment in mediaCache.values) {
            segment.delete()
        }
        mediaCache.clear()
        initCache.clear()
    }

    fun setPlayerTimeMs(positionMs: Long) {
        playerTimeMs = maxOf(0, positionMs)
    }

    /**
     * The playhead hint sent with each SABR request. Prefers the live player position (a
     * supplier wired from the service's ExoPlayer) and falls back to the last position observed
     * from segment opens/seeks (used by downloads, which have no player).
     *
     * This must track real consumption: the server meters media delivery against the playhead
     * and answers a frozen playhead with backoff-only responses until the 30s budget dies
     * (#565). A dead live read (null, or a stale 0 from a torn-down player) must never shadow
     * the segment-open fallback that provably advanced, so the max of the two wins; explicit
     * seeks go through [requestSeek], which resets [playerTimeMs] to the target first.
     */
    internal fun currentPlayheadMs(): Long {
        val supplied = try {
            playheadMsProvider?.invoke()
        } catch (_: Exception) {
            null
        }?.coerceAtLeast(0L) ?: 0L
        maybeLogDeadSupplier(supplied)
        return maxOf(playerTimeMs, supplied)
    }

    /**
     * Advances the playhead hint sent with each SABR request to the start of the segment media3
     * just opened. The pump otherwise reports `playerTimeMs=0` for the whole session while
     * `bufferedThrough` advances minutes ahead, and the server answers playhead-stale requests
     * with backoff-only responses until the 30s budget dies (#565 follow-up: `SABR continuous
     * backoff exceeded 30000ms` ~40s into playback). Mirrors upstream `SabrMediaBridge`,
     * which derives `playerTimeMs` from the demanded segment's timeline position.
     * Monotonic: out-of-order segment opens never move the playhead backwards; explicit seeks
     * go through [requestSeek].
     */
    fun updatePlayheadForSegment(itag: Int, sequenceNumber: Int) {
        try {
            val startMs = spec.timelineFor(itag)?.getStartMs(sequenceNumber) ?: return
            if (startMs > playerTimeMs) {
                playerTimeMs = startMs
            }
        } catch (_: Exception) {
            // Unknown itag/sequence: leave the last known playhead alone.
        }
    }

    fun setPlaybackRate(rate: Float) {
        if (rate > 0) {
            playbackRate = rate
        }
    }

    /** Repositions both tracks so the pump re-requests media from [positionMs]. */
    fun requestSeek(positionMs: Long) {
        val clamped = maxOf(0, positionMs)
        playerTimeMs = clamped
        val audioSeq = spec.audioTimeline.getSequenceAt(clamped)
        val videoSeq = spec.videoTimeline.getSequenceAt(clamped)
        audioBufferedThrough = maxOf(0, audioSeq - 1)
        videoBufferedThrough = maxOf(0, videoSeq - 1)
        synchronized(lock) { lock.notifyAll() }
    }

    fun getInitialization(itag: Int): ByteArray? {
        initCache[itag]?.let { return it.getData() }
        return spec.getInitializationData(itag)
    }

    /** Blocks until the requested media segment is available, the session fails, or timeout. */
    @Throws(IOException::class)
    fun getMediaSegment(itag: Int, sequenceNumber: Int, timeoutMs: Long): SabrMediaSegment? {
        val key = key(itag, sequenceNumber)
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (true) {
                mediaCache[key]?.let { return it }
                terminal?.let { throw it }
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) {
                    return null
                }
                try {
                    lock.wait(remaining)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Interrupted awaiting SABR segment", e)
                }
            }
        }
    }

    /**
     * Returns true when the pump should wait rather than issue a request this round:
     * the prefetch window is full, or buffered far ahead of the playhead.
     *
     * Pace prefetch against consumption: burst-fetching the whole video trips YouTube's
     * SABR throttling (backoff-only responses until the 30s budget dies, #565). Only
     * fetch while the buffered window ahead of the playhead is thin, and pause briefly
     * after every request round so steady-state demand tracks consumption.
     */
    private fun awaitPrefetchWindow(): Boolean {
        if (isFullyBuffered()) {
            sleepQuietly(FULL_BUFFER_POLL_MS)
            return true
        }
        if (isBufferedFarAheadOfPlayhead()) {
            sleepQuietly(PREFETCH_PAUSE_MS)
            return true
        }
        return false
    }

    private fun pumpLoop() {
        val consumer = SabrStreamingResponseReader.SegmentConsumer { segment -> onSegment(segment) }
        while (running) {
            logPumpState()
            if (awaitPrefetchWindow()) {
                continue
            }
            val request = YoutubeSabrRequest.playback(
                currentPlayheadMs(),
                playbackRate,
                listOf(
                    YoutubeSabrRequest.Track.of(
                        spec.audioFormat, spec.audioTimeline, audioBufferedThrough
                    ),
                    YoutubeSabrRequest.Track.of(
                        spec.videoFormat, spec.videoTimeline, videoBufferedThrough
                    )
                )
            )
            try {
                // Progress is delivered media OR a moving playhead: the server legitimately
                // paces delivery while the buffer drains, and a moving playhead proves the
                // session is healthy even when a response carries no new segments.
                var freshSegments = false
                val countingConsumer =
                    SabrStreamingResponseReader.SegmentConsumer { segment ->
                        freshSegments = true
                        consumer.accept(segment)
                    }
                val playheadAtRequestStart = currentPlayheadMs()
                coordinator.request(request, countingConsumer) {
                    freshSegments || currentPlayheadMs() != playheadAtRequestStart
                }
            } catch (e: IOException) {
                fail(e)
                return
            } catch (e: ExtractionException) {
                fail(IOException("SABR request failed", e))
                return
            }
            sleepQuietly(REQUEST_PACING_MS)
        }
    }

    private fun onSegment(segment: SabrMediaSegment) {
        val header = segment.getHeader()
        val itag = header.getItag()
        if (header.isInitSegment()) {
            initCache[itag] = segment
        } else {
            mediaCache[key(itag, header.getSequenceNumber())] = segment
            advanceBufferedThrough(itag)
        }
        synchronized(lock) { lock.notifyAll() }
    }

    private fun advanceBufferedThrough(itag: Int) {
        when (itag) {
            spec.audioFormat.getItag() -> audioBufferedThrough = contiguousEnd(itag, audioBufferedThrough)
            spec.videoFormat.getItag() -> videoBufferedThrough = contiguousEnd(itag, videoBufferedThrough)
        }
    }

    private fun contiguousEnd(itag: Int, from: Int): Int {
        var next = from
        while (mediaCache.containsKey(key(itag, next + 1))) {
            next++
        }
        return next
    }

    private fun isFullyBuffered(): Boolean =
        audioBufferedThrough >= spec.audioTimeline.getEndSequence() &&
            videoBufferedThrough >= spec.videoTimeline.getEndSequence()

    /**
     * True once both tracks are buffered at least [PREFETCH_AHEAD_MS] past the playhead, so the
     * pump idles instead of burst-fetching the rest of the video (which YouTube throttles with
     * backoff-only responses, #565). Lags one segment behind by construction: the playhead only
     * advances when media3 opens a segment, so the window refills as content is consumed.
     */
    internal fun isBufferedFarAheadOfPlayhead(): Boolean {
        val playheadMs = currentPlayheadMs()
        val audioEndMs = spec.audioTimeline.getEndMs(audioBufferedThrough.coerceAtLeast(0))
        val videoEndMs = spec.videoTimeline.getEndMs(videoBufferedThrough.coerceAtLeast(0))
        return minOf(audioEndMs, videoEndMs) - playheadMs >= PREFETCH_AHEAD_MS
    }

    /** Heartbeat for logcat diagnosis: playhead source, window state, and pump progress. */
    private fun logPumpState() {
        val now = System.currentTimeMillis()
        if (now - lastStateLogMs < PUMP_STATE_LOG_INTERVAL_MS) return
        lastStateLogMs = now
        val supplied = try {
            playheadMsProvider?.invoke()
        } catch (_: Exception) {
            null
        }
        Log.dev(
            TAG,
            "pump video=${spec.videoId} playhead=${currentPlayheadMs()} " +
                "supplier=$supplied fallback=$playerTimeMs " +
                "audioThrough=$audioBufferedThrough videoThrough=$videoBufferedThrough " +
                "windowIdle=${isBufferedFarAheadOfPlayhead()}"
        )
    }

    /**
     * Flags a dead live playhead read (supplier stuck at/below the known segment progress) so a
     * torn-down or stale player reference shows up in logcat instead of silently metering the
     * session to zero. Rate-limited; the fallback keeps the session alive in the meantime.
     */
    private fun maybeLogDeadSupplier(supplied: Long) {
        if (supplied != 0L || playerTimeMs <= DEAD_SUPPLIER_FALLBACK_MS) return
        val now = System.currentTimeMillis()
        if (now - lastDeadSupplierLogMs < DEAD_SUPPLIER_LOG_INTERVAL_MS) return
        lastDeadSupplierLogMs = now
        Log.status(
            TAG,
            "SABR live playhead supplier reads 0 while segment progress is at " +
                "$playerTimeMs ms for ${spec.videoId}; using segment fallback " +
                "(player reference may be stale)"
        )
    }

    private fun fail(error: IOException) {
        terminal = error
        running = false
        // The session keeps a bounded diagnostic transcript of every SABR request/response;
        // dump it in chunks (logcat truncates single messages past ~4KB) so a terminal stall
        // can be diagnosed from logcat without a debugger.
        try {
            Log.status(TAG, "SABR session failed for ${spec.videoId}: ${error.message}")
            sessionTrace().chunked(TRACE_CHUNK_CHARS).forEachIndexed { index, chunk ->
                Log.status(TAG, "SABR trace ${spec.videoId} [$index]: $chunk")
            }
        } catch (_: Exception) {
        }
        synchronized(lock) { lock.notifyAll() }
    }

    private fun sessionTrace(): String {
        return try {
            session.getDiagnosticTrace()
        } catch (_: Exception) {
            "<trace unavailable>"
        }
    }

    private companion object {
        private const val TAG = "SabrNgSession"

        /**
         * Stop prefetching once this much content is buffered ahead of the playhead; the pump
         * resumes as consumption drains the window.
         */
        private const val PREFETCH_AHEAD_MS = 60_000L

        /** Breather between request rounds so steady-state demand tracks consumption. */
        private const val REQUEST_PACING_MS = 1_000L

        /** Pump heartbeat interval for logcat diagnosis. */
        private const val PUMP_STATE_LOG_INTERVAL_MS = 5_000L

        /** Only warn about a dead supplier once segments prove real progress past startup. */
        private const val DEAD_SUPPLIER_FALLBACK_MS = 2_000L

        /** Rate-limit for the dead-supplier warning (the per-pump heartbeat stays at 5s). */
        private const val DEAD_SUPPLIER_LOG_INTERVAL_MS = 30_000L

        /** Logcat truncates single messages past ~4KB; chunk the trace so none is lost. */
        private const val TRACE_CHUNK_CHARS = 3_000

        /** Poll interval while the prefetch window is full. */
        private const val FULL_BUFFER_POLL_MS = 200L

        /** Breather while buffered far ahead of the playhead. */
        private const val PREFETCH_PAUSE_MS = 500L

        /** Bit width of the itag half of the segment-cache key. */
        private const val ITAG_SHIFT_BITS = 32

        /** Mask for the sequence-number half of the segment-cache key. */
        private const val SEQUENCE_MASK = 0xffffffffL
    }

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            running = false
        }
    }

    private fun key(itag: Int, sequenceNumber: Int): Long =
        (itag.toLong() shl ITAG_SHIFT_BITS) or (sequenceNumber.toLong() and SEQUENCE_MASK)
}
