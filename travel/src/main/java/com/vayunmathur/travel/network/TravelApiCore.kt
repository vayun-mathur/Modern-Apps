package com.vayunmathur.travel.network

import com.vayunmathur.library.network.NetworkClient
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Base URL for the `/api/travel` endpoints on the self-hosted proxy. */
internal const val TRAVEL_BASE = "https://api.maproj.org/api/travel"

/** Max characters of an error message surfaced to the user. */
internal const val MAX_ERROR_SNIPPET_LENGTH = 300

internal val travelJson = Json { ignoreUnknownKeys = true }

/**
 * Request bodies: every field is sent (the server requires e.g. `title` and
 * `email` even when empty), nulls are omitted.
 */
internal val travelRequestJson = Json {
    encodeDefaults = true
    explicitNulls = false
}

/**
 * JSON text for a POST body; null for "no body" (an empty string). The shared
 * network client sends non-String bodies via `toString()`, so DTOs must be
 * encoded here.
 */
internal inline fun <reified B> travelRequestBody(body: B): String? =
    if (body is String) body.ifEmpty { null } else travelRequestJson.encodeToString(body)

internal fun travelEnc(s: String): String = URLEncoder.encode(s, "UTF-8")

/** Fallback text when an error body carries no usable message. */
internal const val GENERIC_TRAVEL_ERROR = "Something went wrong. Please try again."

/**
 * Thrown when the server rejects checkout with HTTP 422: traveler details are
 * missing. The UI routes back to the passengers form with [missingFields].
 */
class MissingDetailsException(
    val missingFields: List<String>,
    message: String,
) : IllegalStateException(message)

/**
 * Thrown on HTTP 503 (still pricing): the caller may retry after
 * [retryAfterSeconds] (or a few seconds when absent).
 */
class StillPricingException(
    val retryAfterSeconds: Int,
    message: String,
) : IllegalStateException(message)

/**
 * Pull a human-readable message out of an error body. The server answers
 * non-2xx with `{"message": "<neutral text>"}`, so read `message` and fall
 * back to generic text.
 */
internal fun travelExtractError(body: String): String = runCatching {
    val start = body.indexOf('{')
    val jsonPart = if (start >= 0) body.substring(start) else body
    travelJson.parseToJsonElement(jsonPart).jsonObject["message"]
        ?.jsonPrimitive?.content?.ifBlank { null }
        ?: GENERIC_TRAVEL_ERROR
}.getOrDefault(GENERIC_TRAVEL_ERROR).take(MAX_ERROR_SNIPPET_LENGTH)

/** Map a non-2xx status + body to the exception the ViewModel surfaces. */
internal fun travelHttpError(status: Int, body: String, fallback: String): IllegalStateException {
    if (status == 422) {
        val fields = runCatching {
            val start = body.indexOf('{')
            val jsonPart = if (start >= 0) body.substring(start) else body
            val array = travelJson.parseToJsonElement(jsonPart).jsonObject["missingFields"]?.jsonArray
                ?: return@runCatching emptyList()
            array.map { it.jsonPrimitive.content }
        }.getOrDefault(emptyList())
        return MissingDetailsException(fields, travelExtractError(body))
    }
    if (status == 503) {
        val retryAfter = runCatching {
            val start = body.indexOf('{')
            val jsonPart = if (start >= 0) body.substring(start) else body
            travelJson.parseToJsonElement(jsonPart).jsonObject["retryAfterSeconds"]
                ?.jsonPrimitive?.content?.toIntOrNull()
        }.getOrNull() ?: 5
        return StillPricingException(retryAfter, travelExtractError(body))
    }
    return IllegalStateException(
        travelExtractError(body).ifBlank { "$fallback (HTTP $status)." }
    )
}

/** GET [url] and decode the JSON result, surfacing server errors. */
internal suspend inline fun <reified T> travelGetJson(url: String, fallback: String): T {
    val res = NetworkClient.performRequest(url = url, method = "GET")
    if (!res.isSuccess) throw travelHttpError(res.status, res.body, fallback)
    return travelJson.decodeFromString(res.body)
}

/** POST [body] to [url] and decode the JSON result, surfacing server errors. */
internal suspend inline fun <reified B, reified T> travelPostJson(url: String, body: B, fallback: String): T {
    val res = NetworkClient.performRequest(
        url = url,
        method = "POST",
        headers = mapOf("Content-Type" to "application/json"),
        body = travelRequestBody(body),
    )
    if (!res.isSuccess) throw travelHttpError(res.status, res.body, fallback)
    return travelJson.decodeFromString(res.body)
}
