package com.vayunmathur.fooddelivery.data

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

/** Persists the single-merchant cart as JSON in SharedPreferences, with writes serialised on one dispatcher. */
@OptIn(ExperimentalCoroutinesApi::class)
object CartStore {

    private const val PREFS_NAME = "fooddelivery_cart"

    /**
     * v2: modifiers changed from `Modifier{id,name,price}` to
     * `SelectedModifier{modifierGroupId,modifierId,...}`. A v1 cart would still decode
     * (ignoreUnknownKeys drops the old `id`) but every modifier would silently carry
     * modifierId=0 and modifierGroupId=0 and check out mispriced — so read a new key and
     * let stale carts fall away instead.
     */
    private const val KEY = "cart_items_v2"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Single writer, so back-to-back mutations commit in the order they were made — a save
     * must not overtake the clear that follows a placed order.
     */
    private val io = Dispatchers.IO.limitedParallelism(1)

    /** Outlives composition: a cart edit has to be persisted even if the screen goes away. */
    private val scope = CoroutineScope(SupervisorJob() + io)

    suspend fun getAll(context: Context): List<CartItem> = withContext(io) {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return@withContext emptyList()
        try {
            json.decodeFromString<List<CartItem>>(raw)
        } catch (_: Exception) { emptyList() }
    }

    fun save(context: Context, items: List<CartItem>) {
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
