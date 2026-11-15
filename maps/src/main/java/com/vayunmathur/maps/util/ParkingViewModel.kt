package com.vayunmathur.maps.util

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.maps.data.ParkingSpot
import com.vayunmathur.maps.data.ParkingStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Parking memory (P9): exposes the single active parking spot plus a short
 * history to the UI, backed by [ParkingStore]. Follows the same shape as
 * [SavedPlacesViewModel] — persistence lives in the store; this class only adds
 * the `viewModelScope`, turns the store flows into `StateFlow`s, and edits the
 * latest snapshot before writing it back.
 */
class ParkingViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ParkingStore(DataStoreUtils.getInstance(application))

    val active: StateFlow<ParkingSpot?> =
        store.activeFlow().stateIn(viewModelScope, SharingStarted.Eagerly, store.activeInitial())
    val history: StateFlow<List<ParkingSpot>> =
        store.historyFlow().stateIn(viewModelScope, SharingStarted.Eagerly, store.historyInitial())

    /** Save the current location as the active parking spot. Any previous active
     *  spot is pushed onto the history. */
    fun saveParking(lat: Double, lon: Double, note: String? = null) = launch {
        archiveActive()
        store.setActive(ParkingSpot(lat, lon, System.currentTimeMillis(), note?.ifBlank { null }))
    }

    /** Update the note on the active spot (kept in place; not archived). */
    fun updateNote(note: String?) = launch {
        val current = active.value ?: return@launch
        store.setActive(current.copy(note = note?.ifBlank { null }))
    }

    /** Clear the active spot ("found my car"), archiving it to history. */
    fun clear() = launch {
        archiveActive()
        store.setActive(null)
    }

    private suspend fun archiveActive() {
        val prev = active.value ?: return
        store.setHistory((listOf(prev) + history.value).take(MAX_HISTORY))
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    /** Shared constants and helpers for ParkingViewModel. */
    companion object {
        private const val MAX_HISTORY = 10
    }
}
