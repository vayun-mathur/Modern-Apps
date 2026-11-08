package com.vayunmathur.games.chess.network

import com.vayunmathur.games.chess.auth.LichessTokenStore
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.NetworkDataStream
import com.vayunmathur.library.network.SimpleResponse
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Thrown when Lichess answers HTTP 401 — the token was revoked or expired. */
class LichessAuthExpired(message: String) : IOException(message)

/**
 * Lichess Board API client (`https://lichess.org/api#tag/Board`), built only on [NetworkClient]
 * primitives (`getJson` / `performRequest` / `stream`) with `TrustBundle.STANDARD` (set once in
 * `MainActivity.onCreateBeforeContent`). No raw OkHttp/Retrofit/`HttpURLConnection`.
 *
 * Mutating calls use [NetworkClient.performRequest] (which never throws on HTTP status) so a 401
 * maps to [LichessAuthExpired] while other failures keep their status + body prefix. Streams run
 * until the server closes them or the caller's coroutine is cancelled; the ViewModel owns
 * exponential-backoff reconnect around them.
 */
class LichessApi(private val tokenStore: LichessTokenStore) {

    companion object {
        private const val BASE = "https://lichess.org"
        private const val FORM = "application/x-www-form-urlencoded"

        /** Idle games outlast the 60 s default read timeout; moves reset it on every read. */
        private const val STREAM_READ_TIMEOUT_MS = 10 * 60_000L

        private const val MAX_ERROR_CHARS = 200

        private val HTTP_401 = Regex("^HTTP 401\\b")

        private val ndjson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        private fun form(params: Map<String, String>): String =
            params.entries.joinToString("&") { (k, v) ->
                URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
            }
    }

    private suspend fun headers(extra: Map<String, String> = emptyMap()): Map<String, String> =
        tokenStore.authHeaders() + extra

    private fun checkOk(response: SimpleResponse, what: String) {
        when (response.status) {
            in 200..299 -> return
            401 -> throw LichessAuthExpired("Lichess $what: unauthorized (401)")
            else -> throw IOException("Lichess $what: HTTP ${response.status}: ${response.body.take(MAX_ERROR_CHARS)}")
        }
    }

    /** Maps [NetworkClient.callJson]/[getJson] transport failures: 401 → [LichessAuthExpired]. */
    private suspend fun <T> wrapJson(what: String, block: suspend () -> T): T {
        try {
            return block()
        } catch (e: IOException) {
            if (e is LichessAuthExpired || e.message?.let(HTTP_401::containsMatchIn) == true) {
                throw LichessAuthExpired("Lichess $what: unauthorized (401)")
            }
            throw e
        }
    }

    /** Verifies the stored token and returns the account profile (id + username). */
    suspend fun account(): LichessProfile = wrapJson("account") {
        NetworkClient.getJson("$BASE/api/account", headers())
    }

    /**
     * Opens a public seek (`rated=false`, random color, standard variant). Matchmaking happens
     * server-side; the pairing arrives as a `gameStart` event on [streamEvents].
     */
    suspend fun seek(timeControl: LichessTimeControl) {
        val response = NetworkClient.performRequest(
            "$BASE/api/board/seek",
            method = "POST",
            headers = headers(mapOf("Content-Type" to FORM)),
            body = form(
                mapOf(
                    "rated" to "false",
                    "time" to timeControl.minutes.toString(),
                    "increment" to timeControl.incrementSeconds.toString(),
                    "color" to "random",
                    "variant" to "standard",
                )
            ),
        )
        checkOk(response, "seek")
    }

    @Serializable
    private data class ChallengeCreated(val id: String = "")

    /**
     * Challenges `username` to an unrated standard game. Returns the challenge id; acceptance
     * arrives as `gameStart`, refusal as `challengeDeclined` on [streamEvents].
     */
    suspend fun challenge(username: String, timeControl: LichessTimeControl): String {
        val encoded = URLEncoder.encode(username.trim(), "UTF-8")
        val response = NetworkClient.performRequest(
            "$BASE/api/board/challenge/$encoded",
            method = "POST",
            headers = headers(mapOf("Content-Type" to FORM)),
            body = form(
                mapOf(
                    "rated" to "false",
                    "clock.limit" to (timeControl.minutes * 60).toString(),
                    "clock.increment" to timeControl.incrementSeconds.toString(),
                    "color" to "random",
                    "variant.key" to "standard",
                )
            ),
        )
        checkOk(response, "challenge")
        return try {
            ndjson.decodeFromString(ChallengeCreated.serializer(), response.body).id
        } catch (_: IllegalArgumentException) {
            ""
        }
    }

    /** Declines an incoming challenge (Challenge API; same host and Bearer auth). */
    suspend fun declineChallenge(challengeId: String) {
        val response = NetworkClient.performRequest(
            "$BASE/api/challenge/$challengeId/decline",
            method = "POST",
            headers = headers(),
        )
        checkOk(response, "decline challenge")
    }

    /** Sends one move in UCI (`e2e4`, `e7e8q`, `e1g1`). Empty POST per the Board API. */
    suspend fun sendMove(gameId: String, uci: String) {
        val response = NetworkClient.performRequest(
            "$BASE/api/board/game/$gameId/move/$uci",
            method = "POST",
            headers = headers(),
        )
        checkOk(response, "move $uci")
    }

    suspend fun resign(gameId: String) {
        val response = NetworkClient.performRequest(
            "$BASE/api/board/game/$gameId/resign",
            method = "POST",
            headers = headers(),
        )
        checkOk(response, "resign")
    }

    suspend fun abort(gameId: String) {
        val response = NetworkClient.performRequest(
            "$BASE/api/board/game/$gameId/abort",
            method = "POST",
            headers = headers(),
        )
        checkOk(response, "abort")
    }

    /**
     * Follows the account event stream (`gameStart` / `gameFinish` / `challenge` /
     * `challengeDeclined`), invoking [onEvent] per NDJSON line. Returns only when the server
     * closes the stream or the caller's coroutine is cancelled; throws [LichessAuthExpired] on
     * 401, [IOException] on transport failure.
     */
    suspend fun streamEvents(onEvent: suspend (LichessEvent) -> Unit) {
        NetworkClient.stream(
            "$BASE/api/stream/event",
            headers = headers(),
            readTimeoutMs = STREAM_READ_TIMEOUT_MS,
        ) { stream, response ->
            if (stream == null) {
                checkOk(response, "event stream")
                return@stream
            }
            readLines(stream) { line -> parseEvent(line)?.let { onEvent(it) } }
        }
    }

    /**
     * Follows one game's stream (`gameFull` then `gameState` deltas). Same lifecycle as
     * [streamEvents]; the ViewModel re-attaches with backoff after a drop.
     */
    suspend fun streamGame(gameId: String, onUpdate: suspend (LichessGameUpdate) -> Unit) {
        NetworkClient.stream(
            "$BASE/api/board/game/stream/$gameId",
            headers = headers(),
            readTimeoutMs = STREAM_READ_TIMEOUT_MS,
        ) { stream, response ->
            if (stream == null) {
                checkOk(response, "game stream")
                return@stream
            }
            readLines(stream) { line -> parseGameLine(line)?.let { onUpdate(it) } }
        }
    }

    private fun parseEvent(line: String): LichessEvent? = try {
        ndjson.decodeFromString(LichessEventEnvelope.serializer(), line).toEvent()
            .takeIf { it != LichessEvent.Ignored }
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun parseGameLine(line: String): LichessGameUpdate? = try {
        decodeGameLine(ndjson.parseToJsonElement(line), ndjson)
            .takeIf { it != LichessGameUpdate.Ignored }
    } catch (_: IllegalArgumentException) {
        null
    }

    /**
     * Splits a [NetworkDataStream] into lines at `\n`, invoking [onLine] per non-blank line.
     * Lichess NDJSON is `\n`-delimited with no escapes spanning lines, so a byte scan suffices.
     * A transport failure surfaces as end-of-stream (`-1`); reconnect is the caller's job.
     */
    private suspend fun readLines(stream: NetworkDataStream, onLine: suspend (String) -> Unit) {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        val pending = ByteArrayOutputStream()
        while (true) {
            val read = stream.read(buffer)
            if (read == -1) break
            if (read == 0) continue
            var start = 0
            for (i in 0 until read) {
                if (buffer[i] == NEWLINE_BYTE) {
                    pending.write(buffer, start, i - start)
                    val line = pending.toString(Charsets.UTF_8.name()).trim()
                    pending.reset()
                    start = i + 1
                    if (line.isNotEmpty()) onLine(line)
                }
            }
            pending.write(buffer, start, read - start)
        }
    }
}

private const val READ_BUFFER_SIZE = 8 * 1024
private const val NEWLINE_BYTE: Byte = '\n'.code.toByte()
