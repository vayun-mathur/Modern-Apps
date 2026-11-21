package com.vayunmathur.shop.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A checkout write whose intent (idempotency key) was persisted BEFORE the write,
 * so process death mid-create cannot lose it and a later launch cannot mint a
 * second intent/session for the same cart.
 *
 * The backend can fetch a session by checkout id (`getCheckout`) and poll order
 * tracking by token, but it has no query-by-intent: an [InFlightCheckout] with no
 * [checkoutId] yet therefore reconciles to an uncertain "check your orders /
 * merchant website" state and is never replayed.
 */
@Serializable
data class InFlightCheckout(
    /** The idempotency key the write carries (or carried). Never reused for a new write. */
    val intent: String,
    val platformId: String,
    val shopDomain: String,
    val storeName: String = "",
    /** Cart lines as submitted, images stripped like every other persisted projection. */
    val lines: List<CartLine> = emptyList(),
    /** Null until the create returns; filled in before the embedded checkout opens. */
    val checkoutId: String? = null,
    val trackingToken: String = "",
    /** inflight | uncertain | pending */
    val status: String = STATUS_INFLIGHT,
    val createdAtMs: Long = 0,
) {
    companion object {
        const val STATUS_INFLIGHT = "inflight"
        const val STATUS_UNCERTAIN = "uncertain"
        const val STATUS_PENDING = "pending"
    }
}

/** Persists the single in-flight checkout intent as JSON in SharedPreferences (OrderStore pattern). */
object CheckoutIntentStore {
    private const val PREFS_NAME = "shop_checkout_intent"
    private const val KEY = "inflight_v1"
    private val json = Json { ignoreUnknownKeys = true }
    private val writeMutex = Mutex()

    suspend fun get(context: Context): InFlightCheckout? = withContext(Dispatchers.IO) {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return@withContext null
        runCatching { json.decodeFromString<InFlightCheckout>(raw) }.getOrNull()
    }

    suspend fun save(context: Context, record: InFlightCheckout) = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit { putString(KEY, json.encodeToString(record)) }
        }
    }

    suspend fun clear(context: Context, platformId: String, shopDomain: String) = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val current = prefs.getString(KEY, null)?.let {
                runCatching { json.decodeFromString<InFlightCheckout>(it) }.getOrNull()
            }
            // Only clear our own store's record; another store may have taken over.
            if (current == null || (current.platformId == platformId && current.shopDomain == shopDomain)) {
                prefs.edit { remove(KEY) }
            }
        }
    }
}
