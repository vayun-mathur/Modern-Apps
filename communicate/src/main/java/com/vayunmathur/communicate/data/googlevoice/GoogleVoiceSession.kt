package com.vayunmathur.communicate.data.googlevoice

import android.content.Context
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore-backed store for the Google Voice web session captured by the WebView
 * sign-in flow. Google Voice has no public API, so every authenticated RPC replays
 * the browser's cookies plus the sniffed web `apiKey`; this persists that material
 * (see `voice-documentation.md` "Authentication, Headers, And Request Conventions").
 *
 * Cookies and the API key are sensitive; they live in the app's private,
 * credential-encrypted DataStore file like any other secret in this repo.
 */
class GoogleVoiceSession private constructor(private val store: DataStoreUtils) {

    /** Raw `Cookie:` header value captured from the signed-in voice.google.com session. */
    suspend fun cookieHeader(): String? = store.getStringAwait(KEY_COOKIE)

    /** SAPISID cookie value, extracted from [cookieHeader] for SAPISIDHASH auth. */
    suspend fun sapisid(): String? = store.getStringAwait(KEY_SAPISID)

    /** Web `key=` API key sniffed from a voiceclient RPC URL. */
    suspend fun apiKey(): String? = store.getStringAwait(KEY_API_KEY)

    /** `x-goog-authuser` index (the `/u/<n>/` account slot). Defaults to "0". */
    suspend fun authUser(): String = store.getStringAwait(KEY_AUTHUSER) ?: "0"

    /** The account's Google Voice phone number (from `account/get`), for display + line label. */
    suspend fun phoneNumber(): String? = store.getStringAwait(KEY_PHONE_NUMBER)

    /** New-message notification watermark; zero means the first background poll should seed it. */
    suspend fun lastSeenMessageTimestampMillis(): Long = store.getLongAwait(KEY_LAST_SEEN_MESSAGE_TS) ?: 0L

    suspend fun setLastSeenMessageTimestampMillis(timestampMillis: Long) {
        store.setLong(KEY_LAST_SEEN_MESSAGE_TS, timestampMillis)
    }

    suspend fun isSignedIn(): Boolean = store.getBooleanAwait(KEY_SIGNED_IN, default = false)

    /** Reactive sign-in state for UI (Accounts screen, line pickers). */
    val signedInFlow: Flow<Boolean>
        get() = store.booleanFlow(KEY_SIGNED_IN)

    /** True only when we have the minimum needed to make an authenticated RPC. */
    suspend fun hasUsableCredentials(): Boolean =
        !cookieHeader().isNullOrBlank() && !apiKey().isNullOrBlank() && !sapisid().isNullOrBlank()

    /**
     * Persist the material captured by the WebView. [cookieHeader] is the full cookie
     * string; [sapisid] is extracted from it here if not supplied.
     */
    suspend fun save(
        cookieHeader: String,
        apiKey: String,
        authUser: String,
        sapisid: String? = null,
    ) {
        store.setString(KEY_COOKIE, cookieHeader)
        store.setString(KEY_API_KEY, apiKey)
        store.setString(KEY_AUTHUSER, authUser)
        val resolvedSapisid = sapisid ?: extractSapisid(cookieHeader)
        if (resolvedSapisid != null) store.setString(KEY_SAPISID, resolvedSapisid)
        store.setBoolean(KEY_SIGNED_IN, true)
    }

    /** Refresh just the cookie string (e.g. after a WebView revisit), keeping other fields. */
    suspend fun updateCookies(cookieHeader: String) {
        store.setString(KEY_COOKIE, cookieHeader)
        extractSapisid(cookieHeader)?.let { store.setString(KEY_SAPISID, it) }
    }

    suspend fun setPhoneNumber(number: String) {
        store.setString(KEY_PHONE_NUMBER, number)
    }

    suspend fun signOut() {
        store.removeKeys(
            listOf(KEY_COOKIE, KEY_SAPISID, KEY_API_KEY, KEY_AUTHUSER, KEY_PHONE_NUMBER, KEY_LAST_SEEN_MESSAGE_TS),
        )
        store.setBoolean(KEY_SIGNED_IN, false)
    }

    val phoneNumberFlow: Flow<String?>
        get() = store.stringFlow(KEY_PHONE_NUMBER).map { it }

    /** Preference keys for the Google Voice session (cookie, SAPISID, phone number). */
    companion object {
        private const val KEY_COOKIE = "gv_cookie"
        private const val KEY_SAPISID = "gv_sapisid"
        private const val KEY_API_KEY = "gv_api_key"
        private const val KEY_AUTHUSER = "gv_authuser"
        private const val KEY_PHONE_NUMBER = "gv_phone_number"
        private const val KEY_SIGNED_IN = "gv_signed_in"
        private const val KEY_LAST_SEEN_MESSAGE_TS = "gv_last_seen_message_ts"

        fun get(context: Context): GoogleVoiceSession =
            GoogleVoiceSession(DataStoreUtils.getInstance(context.applicationContext))

        /**
         * Pull the SAPISID (or __Secure-3PAPISID as a fallback) value out of a raw cookie
         * header. Google's SAPISIDHASH scheme signs requests with this value.
         */
        fun extractSapisid(cookieHeader: String): String? {
            val cookies = cookieHeader.split(';').map { it.trim() }
            fun value(name: String): String? = cookies
                .firstOrNull { it.startsWith("$name=") }
                ?.substringAfter('=')
                ?.takeIf { it.isNotBlank() }
            return value("SAPISID")
                ?: value("__Secure-3PAPISID")
                ?: value("__Secure-1PAPISID")
        }
    }
}
