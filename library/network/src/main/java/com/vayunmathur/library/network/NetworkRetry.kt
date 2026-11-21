package com.vayunmathur.library.network

import java.io.IOException
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random
import kotlinx.coroutines.delay

/**
 * Central, opt-in retry for [NetworkClient] — the only retry loop app
 * `network.Networking` owners should need for a single HTTP request.
 *
 * Policy in one paragraph: idempotent methods (GET/HEAD/PUT/DELETE) are
 * retried on 408/429/502/503/504 and on [IOException]; any other method is
 * retried only when the caller passes an idempotency key, which is sent as
 * the `Idempotency-Key` header on every attempt so the server can dedupe.
 * The wait between attempts honours `Retry-After` (both forms, via
 * [RetryAfter]) and otherwise doubles from [RetryPolicy.initialDelayMs],
 * capped at [RetryPolicy.maxDelayMs], plus jitter so throttled clients do
 * not retry in lockstep. After the last attempt the final response is
 * returned (status failures never throw, matching [NetworkClient.execute])
 * or the final [IOException] is rethrown.
 *
 * Business polling — "keep asking until the job finishes" (travel pricing,
 * order status) — is NOT this: those loops stay in their app and only their
 * per-request transient failure handling moves here.
 */
data class RetryPolicy(
    /** Total attempts including the first; 1 disables retry. */
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    /** First backoff step; doubles per attempt. */
    val initialDelayMs: Long = DEFAULT_INITIAL_DELAY_MS,
    /** Cap for both backoff and a server-named `Retry-After`. */
    val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
    /** Uniform jitter added to each wait, in `[0, jitterMs)`. */
    val jitterMs: Long = DEFAULT_JITTER_MS,
    /** Statuses worth another attempt. */
    val retryableStatuses: Set<Int> = RETRYABLE_STATUSES,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
        require(initialDelayMs >= 0 && maxDelayMs >= 0 && jitterMs >= 0) { "delays must be >= 0" }
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 3
        const val DEFAULT_INITIAL_DELAY_MS = 500L
        const val DEFAULT_MAX_DELAY_MS = 30_000L
        const val DEFAULT_JITTER_MS = 250L

        /** Transient statuses: request timeout, throttling, gateway/server overload. */
        val RETRYABLE_STATUSES: Set<Int> = setOf(408, 429, 502, 503, 504)

        val DEFAULT = RetryPolicy()
        val NONE = RetryPolicy(maxAttempts = 1)
    }
}

/** Methods that are safe to replay verbatim (RFC 9110 §9.2.2). */
private val IDEMPOTENT_METHODS = setOf("GET", "HEAD", "PUT", "DELETE")

internal const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"
internal const val RETRY_AFTER_HEADER = "Retry-After"

/** True when [method] may be replayed under [idempotencyKey] (null = no key supplied). */
internal fun isRetryEligible(method: String, idempotencyKey: String?): Boolean =
    method.uppercase() in IDEMPOTENT_METHODS || !idempotencyKey.isNullOrBlank()

/**
 * Wait before attempt [attempt] (0-based failure count): the server-named
 * [retryAfterMs] when positive, else exponential backoff; both capped at
 * [RetryPolicy.maxDelayMs], plus jitter.
 */
internal fun retryDelayMs(
    policy: RetryPolicy,
    attempt: Int,
    retryAfterMs: Long,
    random: Random = Random,
): Long {
    val base = if (retryAfterMs > 0) {
        retryAfterMs
    } else {
        val shift = attempt.coerceIn(0, MAX_BACKOFF_SHIFT)
        (policy.initialDelayMs shl shift)
    }
    val capped = base.coerceIn(0, policy.maxDelayMs)
    val jitter = if (policy.jitterMs > 0) random.nextLong(policy.jitterMs) else 0L
    return capped + jitter
}

private const val MAX_BACKOFF_SHIFT = 10

/**
 * Runs [block] until it yields a non-retryable result, throws a
 * non-[IOException], or [RetryPolicy.maxAttempts] is exhausted.
 *
 * [shouldRetry] decides whether a result is transient; [retryAfterMsOf]
 * extracts the server-named wait from a result (0 = none named). The wait
 * itself goes through [sleeper] so tests can record instead of sleeping.
 * Cancellation is never swallowed: `delay` (the default sleeper) and
 * [block] both propagate it, and it is not an [IOException].
 */
suspend fun <T> withRetry(
    policy: RetryPolicy = RetryPolicy.DEFAULT,
    shouldRetry: (T) -> Boolean,
    retryAfterMsOf: (T) -> Long = { 0L },
    sleeper: suspend (Long) -> Unit = { delay(it) },
    block: suspend () -> T,
): T {
    var attempt = 0
    while (true) {
        val result = try {
            block()
        } catch (e: IOException) {
            if (attempt >= policy.maxAttempts - 1) throw e
            sleeper(retryDelayMs(policy, attempt, retryAfterMs = 0L))
            attempt++
            continue
        }
        if (!shouldRetry(result) || attempt >= policy.maxAttempts - 1) return result
        sleeper(retryDelayMs(policy, attempt, retryAfterMsOf(result)))
        attempt++
    }
}

/** Adds the `Idempotency-Key` header unless the caller already set one. */
private fun withIdempotencyKey(
    headers: Map<String, *>,
    idempotencyKey: String?,
): Map<String, Any?> {
    if (idempotencyKey.isNullOrBlank()) return headers.mapValues { it.value }
    val hasKey = headers.keys.any { it.equals(IDEMPOTENCY_KEY_HEADER, ignoreCase = true) }
    if (hasKey) return headers.mapValues { it.value }
    return headers.mapValues { it.value } + (IDEMPOTENCY_KEY_HEADER to idempotencyKey)
}

/**
 * [NetworkClient.execute] with central retry (see [RetryPolicy]). Opt-in:
 * plain `execute` never retries. A non-idempotent method without
 * [idempotencyKey] is executed exactly once, whatever the policy says.
 */
suspend fun NetworkClient.executeWithRetry(
    url: String,
    method: String = "GET",
    headers: Map<String, *> = emptyMap<String, Any>(),
    body: Any? = null,
    followRedirects: Boolean = true,
    connectTimeoutMs: Long? = null,
    readTimeoutMs: Long? = connectTimeoutMs,
    sslSocketFactory: SSLSocketFactory? = null,
    useSystemTrust: Boolean = false,
    retryPolicy: RetryPolicy = RetryPolicy.DEFAULT,
    idempotencyKey: String? = null,
): RawResponse {
    if (!isRetryEligible(method, idempotencyKey)) {
        return execute(url, method, headers, body, followRedirects, connectTimeoutMs, readTimeoutMs, sslSocketFactory, useSystemTrust)
    }
    val attemptHeaders = withIdempotencyKey(headers, idempotencyKey)
    return withRetry(
        policy = retryPolicy,
        shouldRetry = { it.status in retryPolicy.retryableStatuses },
        retryAfterMsOf = { RetryAfter.parseMs(it.header(RETRY_AFTER_HEADER)) },
    ) {
        execute(url, method, attemptHeaders, body, followRedirects, connectTimeoutMs, readTimeoutMs, sslSocketFactory, useSystemTrust)
    }
}

/**
 * [NetworkClient.performRequest] with central retry; same contract as
 * [executeWithRetry]. Exists because `performRequest` (HttpURLConnection,
 * text body) is the shape most app `Networking` owners already call.
 */
suspend fun NetworkClient.performRequestWithRetry(
    url: String,
    method: String = "GET",
    headers: Map<String, *> = emptyMap<String, Any>(),
    body: Any? = null,
    sslSocketFactory: SSLSocketFactory? = null,
    useSystemTrust: Boolean = false,
    retryPolicy: RetryPolicy = RetryPolicy.DEFAULT,
    idempotencyKey: String? = null,
): SimpleResponse {
    if (!isRetryEligible(method, idempotencyKey)) {
        return performRequest(url, method, headers, body, sslSocketFactory, useSystemTrust)
    }
    val attemptHeaders = withIdempotencyKey(headers, idempotencyKey)
    return withRetry(
        policy = retryPolicy,
        shouldRetry = { it.status in retryPolicy.retryableStatuses },
        retryAfterMsOf = { RetryAfter.parseMs(it.header(RETRY_AFTER_HEADER)) },
    ) {
        performRequest(url, method, attemptHeaders, body, sslSocketFactory, useSystemTrust)
    }
}
