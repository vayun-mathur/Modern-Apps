package com.vayunmathur.communicate.data.googlevoice

import android.content.Context
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.SimpleResponse
import java.io.IOException

/** Raised when the Google Voice session is missing or rejected (401/403) so callers re-auth. */
class GoogleVoiceAuthException(message: String) : IOException(message)

/**
 * Thin client over the documented Google Voice web RPCs (`voice-documentation.md`,
 * Reverse-Slice Summary + Second Capture). All requests are
 * `POST https://clients6.google.com/voice/v1/voiceclient/<path>?alt=protojson&key=<apiKey>`
 * with `application/json+protobuf` positional-array bodies and cookie + SAPISIDHASH auth.
 *
 * Networking goes through the repo's [NetworkClient] (HttpURLConnection, no OkHttp); parsing
 * is delegated to [GoogleVoiceParser].
 */
class GoogleVoiceClient(private val session: GoogleVoiceSession) {

    suspend fun getAccount(): GvAccount {
        val body = post("account/get", GoogleVoiceParser.buildAccountBody())
        val account = GoogleVoiceParser.parseAccount(body)
        account.phoneNumber?.let { session.setPhoneNumber(it) }
        return account
    }

    suspend fun listThreads(folder: GvFolder = GvFolder.Inbox): List<GvThread> {
        val body = post("api2thread/list", GoogleVoiceParser.buildListBody(folder))
        return GoogleVoiceParser.parseThreads(body)
    }

    /**
     * Messages for a single thread. `api2thread/get` returned only an empty stub for our request
     * shape, but `api2thread/list` embeds every message per thread, so we read them from there
     * (checking the Inbox then the All folder).
     */
    suspend fun getThread(remoteId: String): List<GvMessage> {
        for (folder in listOf(GvFolder.Inbox, GvFolder.All)) {
            val body = post("api2thread/list", GoogleVoiceParser.buildListBody(folder, pageSize = 100, window = 50))
            val messages = GoogleVoiceParser.parseThreadMessages(body, remoteId)
            if (messages.isNotEmpty()) return messages
            if (GoogleVoiceParser.parseThreads(body).any { it.id == remoteId }) return messages
        }
        return emptyList()
    }

    suspend fun search(query: String): List<GvThread> {
        val body = post("api2thread/search", GoogleVoiceParser.buildSearchBody(query))
        return GoogleVoiceParser.parseThreads(body)
    }

    suspend fun listCalls(): List<GvCall> {
        val body = post("api2thread/list", GoogleVoiceParser.buildListBody(GvFolder.Calls))
        return GoogleVoiceParser.parseCalls(body)
    }

    /** Carrier-style inbox for the Voicemail tab: folder 4 holds `c.`-records with transcripts + media. */
    suspend fun listVoicemails(): List<GvVoicemail> {
        val body = post("api2thread/list", GoogleVoiceParser.buildListBody(GvFolder.Voicemail))
        return GoogleVoiceParser.parseVoicemails(body)
    }

    /** Mark a voicemail played/read — same batchupdate shape the web app sends on playback. */
    suspend fun markVoicemailRead(remoteId: String) {
        updateThreadAttributes(remoteId, GoogleVoiceParser.ThreadAction.MarkRead)
    }

    suspend fun sendSms(recipient: String, text: String, threadRemoteId: String? = null) {
        post("api2thread/sendsms", GoogleVoiceParser.buildSendSmsBody(recipient, text, threadRemoteId))
    }

    /** Replay an exact, already-token-bearing sendsms body captured from the web app. */
    suspend fun sendPreparedSms(body: String) {
        post("api2thread/sendsms", body)
    }

    suspend fun updateThreadAttributes(remoteId: String, action: GoogleVoiceParser.ThreadAction) {
        post("thread/batchupdateattributes", GoogleVoiceParser.buildBatchUpdateBody(remoteId, action))
    }

    suspend fun markAllRead() {
        // Real web body: [1] (folder id), not {}.
        post("thread/markallread", "[1]")
    }

    suspend fun getSipRegisterInfo(): GvSipRegisterInfo {
        // Real web body: [3, "<12-char client token>"]; [{},{}] returns HTTP 400.
        val token = (1..12).map { TOKEN_CHARS[kotlin.random.Random.nextInt(TOKEN_CHARS.length)] }.joinToString("")
        val body = post("sipregisterinfo/get", "[3,${'"'}$token${'"'}]")
        return GoogleVoiceParser.parseSipRegisterInfo(body)
    }

    // ------------------------------------------------------------------

    private suspend fun post(path: String, jsonBody: String): String {
        val cookie = session.cookieHeader()
        val sapisid = session.sapisid()
        val authUser = session.authUser()
        val apiKey = session.apiKey()
        if (cookie.isNullOrBlank() || sapisid.isNullOrBlank() || apiKey.isNullOrBlank()) {
            throw GoogleVoiceAuthException("Missing Google Voice session")
        }

        val url = "$BASE$path?alt=protojson&key=$apiKey"
        val headers = GoogleVoiceAuth.headers(cookie, sapisid, authUser)
        val response = NetworkClient.performRequest(
            url = url,
            method = "POST",
            headers = headers,
            body = jsonBody,
        )
        checkVoiceResponse(path, response)
        // TEMP diagnostic logging of raw protojson so the positional parser can be pinned to the
        // real wire shapes. Chunked because logcat truncates long lines.
        val body = response.body
        Log.dev(TAG, "$path <= ${body.length} bytes")
        body.chunked(LOG_CHUNK).forEachIndexed { i, chunk -> Log.dev(TAG, "$path[$i] $chunk") }
        return body
    }

    /** Auth + status checks for a Voice API response (split for ThrowsCount). */
    private fun checkVoiceResponse(path: String, response: SimpleResponse) {
        if (response.status == HTTP_UNAUTHORIZED || response.status == HTTP_FORBIDDEN) {
            throw GoogleVoiceAuthException("Google Voice auth rejected (${response.status})")
        }
        if (!response.isSuccess) {
            Log.error(TAG, "$path failed HTTP ${response.status}: ${response.body.take(LOG_SNIPPET)}")
            throw IOException("Google Voice ${path} failed: HTTP ${response.status}")
        }
    }

    companion object {
        private const val TAG = "GoogleVoiceRaw"
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val LOG_CHUNK = 3500
        private const val LOG_SNIPPET = 500
        private const val TOKEN_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        private const val BASE = "https://clients6.google.com/voice/v1/voiceclient/"

        fun get(context: Context): GoogleVoiceClient =
            GoogleVoiceClient(GoogleVoiceSession.get(context))
    }
}
