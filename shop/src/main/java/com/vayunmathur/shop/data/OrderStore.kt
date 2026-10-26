package com.vayunmathur.shop.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Local pending checkout history. Payment status remains authoritative at the merchant. */
object OrderStore {
    private const val PREFS_NAME = "shop_orders"
    private const val KEY = "orders_v1"
    private const val MAX_ORDERS = 50
    private val json = Json { ignoreUnknownKeys = true }
    private val writeMutex = Mutex()

    suspend fun getAll(context: Context): List<PlacedOrder> = withContext(Dispatchers.IO) {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return@withContext emptyList()
        runCatching { json.decodeFromString<List<PlacedOrder>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun add(context: Context, order: PlacedOrder) = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val current = prefs.getString(KEY, null)?.let {
                runCatching { json.decodeFromString<List<PlacedOrder>>(it) }.getOrDefault(emptyList())
            }.orEmpty()
            val others = current.filterNot {
                it.checkoutId == order.checkoutId && it.shopDomain == order.shopDomain
            }
            prefs.edit { putString(KEY, json.encodeToString((listOf(order) + others).take(MAX_ORDERS))) }
        }
    }
}
