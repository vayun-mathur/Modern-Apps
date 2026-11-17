package com.vayunmathur.youpipe.network.sabr

import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrRequest
import org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrSession
import org.schabi.newpipe.extractor.services.youtube.sabrng.exception.SabrAttestationException
import org.schabi.newpipe.extractor.services.youtube.sabrng.protocol.SabrStreamingResponseReader
import java.io.IOException
import java.io.InterruptedIOException

/**
 * Coordinates protocol-level SABR request recovery: loops [YoutubeSabrSession.requestOnce] until it
 * makes progress, retrying attestation-rejected requests with a freshly minted PO token
 * ([SabrAttestationRetryHandler]) and honoring server backoff. Kotlin port of PipePipe's
 * `SabrRequestCoordinator`.
 */
internal class SabrRequestCoordinator(
    private val session: YoutubeSabrSession,
    private val attestationRetryHandler: SabrAttestationRetryHandler,
    backoffObserver: ((Long) -> Unit)?
) {
    private val backoffObserver: (Long) -> Unit = backoffObserver ?: {}
    private var backoffDeadlineNs: Long = 0
    private var noProgressDeadlineNs: Long = 0

    /**
     * Executes a logical request until it produces progress. [progressChecker] reports whether the
     * response advanced the caller's state; when null, any delivered media segment counts.
     */
    @Throws(IOException::class, ExtractionException::class)
    fun request(
        request: YoutubeSabrRequest,
        consumer: SabrStreamingResponseReader.SegmentConsumer,
        progressChecker: (() -> Boolean)? = null
    ): YoutubeSabrSession.RequestResult {
        while (true) {
            awaitBackoff()
            val result = attemptOnce(request, consumer) ?: continue

            val progress = progressChecker?.invoke() ?: (result.getSegmentCount() > 0)
            val backoffMs = result.getBackoffMs().toLong()
            backoffObserver(backoffMs)
            updateBackoffEpisode(progress, backoffMs)
            updateNoProgressEpisode(progress, backoffMs)
            if (progress) {
                return result
            }
            if (!result.isDeferred()) {
                sleep(EMPTY_RESPONSE_RETRY_MS)
            }
        }
    }

    @Throws(IOException::class, ExtractionException::class)
    private fun attemptOnce(
        request: YoutubeSabrRequest,
        consumer: SabrStreamingResponseReader.SegmentConsumer,
    ): YoutubeSabrSession.RequestResult? {
        return try {
            session.requestOnce(
                request,
                SabrStreamingResponseReader.SegmentConsumer { segment ->
                    attestationRetryHandler.onMediaReceived()
                    consumer.accept(segment)
                }
            )
        } catch (error: SabrAttestationException) {
            attestationRetryHandler.prepareRetry(session, error)
            null
        }
    }

    @Throws(IOException::class)
    private fun awaitBackoff() {
        while (true) {
            val remainingMs = session.getBackoffRemainingMs()
            backoffObserver(remainingMs)
            if (remainingMs <= 0) {
                return
            }
            throwIfBudgetExceeded(
                backoffDeadlineNs, remainingMs,
                "SABR continuous backoff exceeded ${MAX_CONTINUOUS_BACKOFF_MS}ms"
            )
            sleep(minOf(remainingMs, EMPTY_RESPONSE_RETRY_MS))
        }
    }

    @Throws(IOException::class)
    private fun updateBackoffEpisode(progress: Boolean, backoffMs: Long) {
        if (progress) {
            backoffDeadlineNs = 0
        }
        if (backoffMs <= 0) {
            return
        }
        if (backoffDeadlineNs == 0L) {
            backoffDeadlineNs = System.nanoTime() + MAX_CONTINUOUS_BACKOFF_MS * NANOS_PER_MILLISECOND
            return
        }
        throwIfBudgetExceeded(
            backoffDeadlineNs, backoffMs,
            "SABR continuous backoff exceeded ${MAX_CONTINUOUS_BACKOFF_MS}ms"
        )
    }

    @Throws(IOException::class)
    private fun updateNoProgressEpisode(progress: Boolean, backoffMs: Long) {
        if (progress) {
            noProgressDeadlineNs = 0
            return
        }
        if (noProgressDeadlineNs == 0L) {
            noProgressDeadlineNs = System.nanoTime() + MAX_CONTINUOUS_NO_PROGRESS_MS * NANOS_PER_MILLISECOND
            return
        }
        throwIfBudgetExceeded(
            noProgressDeadlineNs,
            if (backoffMs > 0) backoffMs else EMPTY_RESPONSE_RETRY_MS,
            "SABR continuous no-progress exceeded ${MAX_CONTINUOUS_NO_PROGRESS_MS}ms"
        )
    }

    private companion object {
        private const val EMPTY_RESPONSE_RETRY_MS = 250L
        private const val MAX_CONTINUOUS_BACKOFF_MS = 30_000L
        private const val MAX_CONTINUOUS_NO_PROGRESS_MS = 30_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L

        @Throws(IOException::class)
        private fun throwIfBudgetExceeded(deadlineNs: Long, waitMs: Long, message: String) {
            if (deadlineNs != 0L && waitMs * NANOS_PER_MILLISECOND > deadlineNs - System.nanoTime()) {
                throw IOException(message)
            }
        }

        @Throws(IOException::class)
        private fun sleep(milliseconds: Long) {
            try {
                Thread.sleep(milliseconds)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                val interrupted = InterruptedIOException("Interrupted during SABR request")
                interrupted.initCause(error)
                throw interrupted
            }
        }
    }
}
