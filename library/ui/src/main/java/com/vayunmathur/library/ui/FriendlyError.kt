package com.vayunmathur.library.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

/**
 * The one mapper from a failure to a user-facing error string.
 *
 * Raw exception text must never reach the UI: it leaks URLs, hostnames, server
 * response bodies, parser offsets and class names, and it is untranslatable.
 * This maps the common failure shapes to string resources and falls back to a
 * generic message. It NEVER returns exception text (nor any substring of it);
 * the only dynamic value it ever surfaces is an HTTP status code.
 *
 * Mapping (first match wins, cause chain walked up to [MAX_CAUSES] deep):
 * - HTTP status (from an `HTTP <code>` message or a `statusCode`/`code` field)
 * - timeout ([SocketTimeoutException], [TimeoutException], coroutine timeouts)
 * - parse (serialization / JSON / parse failures, incl. `ManifestError.Parse`-style names)
 * - offline / IO ([UnknownHostException], [ConnectException], [SocketException], [IOException])
 * - anything else (including null): generic
 */

/** Deepest cause chain [friendlyErrorRes] will walk looking for a known shape. */
private const val MAX_CAUSES = 8

private val HTTP_STATUS_REGEX = Regex("""\bHTTP\s+(\d{3})\b""", RegexOption.IGNORE_CASE)

/** Maps [error] to a friendly string resource. Never derived from exception text. */
@StringRes
fun friendlyErrorRes(error: Throwable?): Int {
    if (error == null) return R.string.friendly_error_generic
    httpStatusOf(error)?.let { return httpErrorRes(it) }
    val chain = generateSequence(error) { it.cause }.take(MAX_CAUSES).toList()
    if (chain.any(::isTimeout)) return R.string.friendly_error_timeout
    if (chain.any(::isParse)) return R.string.friendly_error_parse
    if (chain.any(::isOfflineOrIo)) return R.string.friendly_error_offline
    // Custom network failures that wrap nothing (e.g. ManifestError.Network,
    // whose message is prose): classify by keyword only, never echo the text.
    val text = chain.joinToString(" ") { it.message.orEmpty() }.lowercase()
    return when {
        "timed out" in text || "timeout" in text -> R.string.friendly_error_timeout
        "malformed" in text || "not a" in text && "manifest" in text -> R.string.friendly_error_parse
        "offline" in text || "unreachable" in text || "could not reach" in text ||
            "unknown host" in text || "no address associated" in text -> R.string.friendly_error_offline
        else -> R.string.friendly_error_generic
    }
}

/**
 * Maps an already-stringified error (a state field that stored `e.message`) to
 * a friendly resource. Same guarantee: the input is classified, never returned.
 */
@StringRes
fun friendlyErrorRes(message: String?): Int {
    if (message.isNullOrBlank()) return R.string.friendly_error_generic
    HTTP_STATUS_REGEX.find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?.let { return httpErrorRes(it) }
    val text = message.lowercase()
    return when {
        "timed out" in text || "timeout" in text -> R.string.friendly_error_timeout
        "json" in text || "parse" in text || "malformed" in text || "decode" in text ||
            "unexpected" in text && "format" in text -> R.string.friendly_error_parse
        "offline" in text || "unreachable" in text || "unknown host" in text ||
            "could not reach" in text || "failed to connect" in text ||
            "connection" in text || "network" in text -> R.string.friendly_error_offline
        else -> R.string.friendly_error_generic
    }
}

/** Friendly string for [error], resolved against this context. */
fun Context.friendlyError(error: Throwable?): String {
    val res = friendlyErrorRes(error)
    return if (res == R.string.friendly_error_http_code) {
        getString(res, httpStatusOf(error) ?: 0)
    } else {
        getString(res)
    }
}

/** Friendly string for an already-stringified [message]; the input is never echoed. */
fun Context.friendlyErrorMessage(message: String?): String {
    val res = friendlyErrorRes(message)
    if (res != R.string.friendly_error_http_code) return getString(res)
    val code = message?.let { HTTP_STATUS_REGEX.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() } ?: 0
    return getString(res, code)
}

/** Friendly string for [error] in a composable. See [friendlyErrorRes]. */
@Composable
fun friendlyError(error: Throwable?): String {
    val res = friendlyErrorRes(error)
    return if (res == R.string.friendly_error_http_code) {
        stringResource(res, httpStatusOf(error) ?: 0)
    } else {
        stringResource(res)
    }
}

/** Friendly string for an already-stringified [message] in a composable; input never echoed. */
@Composable
fun friendlyErrorMessage(message: String?): String {
    val res = friendlyErrorRes(message)
    if (res != R.string.friendly_error_http_code) return stringResource(res)
    val code = message?.let { HTTP_STATUS_REGEX.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() } ?: 0
    return stringResource(res, code)
}

@StringRes
private fun httpErrorRes(status: Int): Int = when (status) {
    401, 403 -> R.string.friendly_error_http_auth
    404 -> R.string.friendly_error_http_not_found
    429 -> R.string.friendly_error_http_rate_limited
    in 500..599 -> R.string.friendly_error_http_server
    else -> R.string.friendly_error_http_code
}

private fun httpStatusOf(error: Throwable?): Int? {
    if (error == null) return null
    var current: Throwable? = error
    repeat(MAX_CAUSES) {
        val e = current ?: return null
        HTTP_STATUS_REGEX.find(e.message.orEmpty())?.groupValues?.getOrNull(1)
            ?.toIntOrNull()?.let { return it }
        reflectedStatus(e)?.let { return it }
        current = e.cause
    }
    return null
}

/** Status carried as a property (e.g. WebSocketHandshakeException.statusCode), read without a dep. */
private fun reflectedStatus(error: Throwable): Int? {
    for (fieldName in listOf("statusCode", "code", "status")) {
        val value = runCatching {
            val field = error.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            (field.get(error) as? Number)?.toInt()
        }.getOrNull()
        if (value != null && value in 100..599) return value
    }
    return null
}

private fun isTimeout(error: Throwable): Boolean =
    error is SocketTimeoutException || error is TimeoutException ||
        error.javaClass.name.contains("TimeoutCancellationException") ||
        error.javaClass.name.contains("HttpTimeoutException")

private fun isParse(error: Throwable): Boolean {
    val name = error.javaClass.name
    val simple = error.javaClass.simpleName
    return name.contains("SerializationException") || name.contains("Json") ||
        name.contains("JSONException") || error is java.text.ParseException ||
        simple == "Parse" || simple.endsWith("ParseException") || simple.endsWith("DecodingException")
}

private fun isOfflineOrIo(error: Throwable): Boolean =
    error is UnknownHostException || error is ConnectException || error is SocketException ||
        error is NoRouteToHostException || error is IOException
