package com.vayunmathur.shop.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Local cart, persisted as JSON in SharedPreferences. Lines carry a snapshot of the
 * title/price/image at add time so the cart renders offline; prices are always
 * re-confirmed server-side before checkout is created.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object ShopCartStore {

    private const val PREFS_NAME = "shop_cart"
    private const val KEY = "cart_lines_v1"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Single writer, so back-to-back mutations commit in the order they were made — a save
     * must not overtake the clear that follows a placed order.
     */
    private val io = Dispatchers.IO.limitedParallelism(1)

    /** Outlives composition: a cart edit has to be persisted even if the screen goes away. */
    private val scope = CoroutineScope(SupervisorJob() + io)

    suspend fun getAll(context: Context): List<CartLine> = withContext(io) {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return@withContext emptyList()
        try {
            json.decodeFromString<List<CartLine>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, items: List<CartLine>) {
        val appCtx = context.applicationContext
        val snapshot = items.toList()
        scope.launch {
            appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit { putString(KEY, json.encodeToString(snapshot)) }
        }
    }

    fun clear(context: Context) {
        val appCtx = context.applicationContext
        scope.launch {
            appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit { remove(KEY) }
        }
    }
}
