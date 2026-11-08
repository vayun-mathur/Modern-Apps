package com.vayunmathur.maps.data

import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * Persistence for every kind of saved place (P6), extracted from
 * `SavedPlacesViewModel` so the store logic lives in one place:
 *
 *  - **Saved** — a flat starred list the user builds from the place sheet.
 *  - **Lists** — named collections, e.g. "Trip",
 *    "Favorites"; a map of list-name → places.
 *
 * Each field is a small JSON string in DataStore. The store is deliberately thin
 * (flows + suspend setters); [com.vayunmathur.maps.util.SavedPlacesViewModel]
 * owns the coroutine scope and turns these into `StateFlow`s.
 */
class SavedPlaceStore(private val ds: DataStoreUtils) {

    // --- Flat saved (starred) list ----------------------------------------

    fun savedFlow(): Flow<List<SavedPlace>> = ds.stringFlow(KEY_SAVED).map { decodeList(it) }
    fun savedInitial(): List<SavedPlace> = decodeList(ds.getString(KEY_SAVED))

    suspend fun setSaved(list: List<SavedPlace>) = ds.setString(KEY_SAVED, Json.encodeToString(list))

    /**
     * Atomic read-modify-write of the starred list inside one DataStore edit.
     * Callers that mutate from a StateFlow snapshot race the flow's async
     * re-emission: two quick mutations can each compute from the pre-write
     * state and the later write drops the earlier change.
     */
    suspend fun updateSaved(transform: (List<SavedPlace>) -> List<SavedPlace>) =
        ds.updateString(KEY_SAVED) { raw -> Json.encodeToString(transform(decodeList(raw))) }

    // --- Named lists -------------------------------------------------------

    fun listsFlow(): Flow<Map<String, List<SavedPlace>>> = ds.stringFlow(KEY_LISTS).map { decodeLists(it) }
    fun listsInitial(): Map<String, List<SavedPlace>> = decodeLists(ds.getString(KEY_LISTS))

    suspend fun setLists(lists: Map<String, List<SavedPlace>>) =
        ds.setString(KEY_LISTS, Json.encodeToString(lists))

    /** Atomic read-modify-write of the named lists; see [updateSaved]. */
    suspend fun updateLists(transform: (Map<String, List<SavedPlace>>) -> Map<String, List<SavedPlace>>) =
        ds.updateString(KEY_LISTS) { raw -> Json.encodeToString(transform(decodeLists(raw))) }

    // --- encode / decode ---------------------------------------------------

    private fun decodeList(raw: String?): List<SavedPlace> =
        raw?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Json.decodeFromString<List<SavedPlace>>(it) }.getOrNull() }
            ?: emptyList()

    private fun decodeLists(raw: String?): Map<String, List<SavedPlace>> =
        raw?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Json.decodeFromString<Map<String, List<SavedPlace>>>(it) }.getOrNull() }
            ?: emptyMap()

    companion object {
        private const val KEY_SAVED = "saved_places_list"
        private const val KEY_LISTS = "saved_place_lists"
    }
}
