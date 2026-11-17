package com.vayunmathur.youpipe.network.sabr

import org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrSession
import org.schabi.newpipe.extractor.services.youtube.sabrng.exception.SabrAttestationException

/**
 * Maintains the PO-token recovery budget for one SABR media acquisition. On a rejected attestation
 * identity it mints a fresh PO token and injects it into the session.
 *
 * The [tokenMinter] is invoked with `force=true`: [LocalDomPoTokenProvider] then tears down the
 * burned BotGuard session and re-bootstraps from a fresh home page before minting, so every
 * retry comes from a new attestation identity. Because each retry refreshes the global session,
 * the identity is always fresh again after a rejection episode, including for the next
 * acquisition — no separate exhaustion hook is needed.
 */
internal class SabrAttestationRetryHandler(
    private val videoId: String,
    private val tokenMinter: ((Boolean) -> ByteArray?)?,
) {
    private var retriesRemaining = MAX_RETRIES

    @Synchronized
    @Throws(SabrAttestationException::class)
    fun prepareRetry(session: YoutubeSabrSession, rejectedTokenError: SabrAttestationException) {
        if (retriesRemaining == 0) {
            throw exhaustedAttestationException(rejectedTokenError)
        }
        val retryNumber = MAX_RETRIES - retriesRemaining + 1
        retriesRemaining--
        val token = mintFreshToken(retryNumber)
        if (token == null || token.isEmpty()) {
            throw emptyTokenAttestationException(retryNumber, rejectedTokenError)
        }
        session.setPoToken(token)
    }

    @Throws(SabrAttestationException::class)
    private fun mintFreshToken(retryNumber: Int): ByteArray? {
        val minter = tokenMinter
            ?: throw noMinterAttestationException()
        return try {
            minter(true)
        } catch (error: org.schabi.newpipe.extractor.exceptions.ExtractionException) {
            throw recoveryAttestationException(retryNumber, error)
        }
    }

    private fun exhaustedAttestationException(cause: SabrAttestationException) =
        SabrAttestationException(
            "SABR PO token was rejected after $MAX_RETRIES attestation recovery retries " +
                "for video=$videoId",
            cause,
        )

    private fun noMinterAttestationException() =
        SabrAttestationException(
            "SABR attestation failed and no PO token minter is available for video=$videoId",
        )

    private fun recoveryAttestationException(retryNumber: Int, cause: Throwable) =
        SabrAttestationException(
            "SABR PO token recovery failed on retry $retryNumber of $MAX_RETRIES " +
                "for video=$videoId: ${cause.message}",
            cause,
        )

    private fun emptyTokenAttestationException(
        retryNumber: Int,
        cause: SabrAttestationException,
    ) = SabrAttestationException(
        "SABR PO token recovery returned no token on retry $retryNumber of $MAX_RETRIES " +
            "for video=$videoId",
        cause,
    )

    /** A media payload proves the current token is usable and restores a fresh budget. */
    @Synchronized
    fun onMediaReceived() {
        retriesRemaining = MAX_RETRIES
    }

    private companion object {
        private const val MAX_RETRIES = 3
    }
}
