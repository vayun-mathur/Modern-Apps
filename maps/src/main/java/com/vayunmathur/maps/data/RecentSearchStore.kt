package com.vayunmathur.maps.data

import android.content.Context
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * Persists the user's most-recent search queries so the search page can offer
 * them before any text is typed.
 *
 * Backed by a single JSON-encoded ordered string list in [DataStoreUtils] (a
 * plain preferences string, not the removed amenities.db): most-recent first,
 * de-duped case-insensitively, capped at [MAX] entries. Offline search is gone
 * (Decision D2), but the recents list is local and works without network.
 */
class RecentSearchStore private constructor(private val ds: DataStoreUtils) {

    /** Recent queries, most-recent first. Empty until the first search. */
    val recents: Flow<List<String>> = ds.stringFlow(KEY).map { decode(it) }

    /** Snapshot of the current recents (non-suspending; reads the mirrored state). */
    fun current(): List<String> = decode(ds.getString(KEY))

    /** Record [query], moving it to the front and dropping any duplicate. Blank
     *  queries are ignored. */
    suspend fun add(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        // Read-modify-write inside one DataStore edit: current() reads the
        // asynchronously-mirrored snapshot, which can lag the previous write,
        // so two quick searches could each drop the other's entry.
        ds.updateString(KEY) { raw ->
            val existing = decode(raw)
            Json.encodeToString(
                (listOf(q) + existing.filter { !it.equals(q, ignoreCase = true) }).take(MAX)
            )
        }
    }

    /** Clear all recent searches. */
    suspend fun clear() {
        ds.setString(KEY, "")
    }

    private fun decode(raw: String?): List<String> =
        raw?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Json.decodeFromString<List<String>>(it) }.getOrNull() }
            ?: emptyList()

    companion object {
        private const val KEY = "maps_recent_searches"
        private const val MAX = 10

        /** How many recent queries the store keeps; search weights decay over this. */
        internal const val RECENT_CAP = MAX

        @Volatile private var instance: RecentSearchStore? = null

        fun get(context: Context): RecentSearchStore =
            instance ?: synchronized(this) {
                instance ?: RecentSearchStore(DataStoreUtils.getInstance(context.applicationContext))
                    .also { instance = it }
            }
    }
}
