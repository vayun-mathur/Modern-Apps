package com.vayunmathur.maps.util

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.maps.data.SavedPlace
import com.vayunmathur.maps.data.SavedPlaceStore
import com.vayunmathur.maps.data.SpecificFeature
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Exposes the user's saved places to the UI. P6 saved-places model backed by
 * [SavedPlaceStore]:
 *
 *  - [saved] — a flat starred list built from the place sheet's Save action.
 *  - [lists] — named collections managed from the saved-places screen.
 *
 * All persistence lives in [SavedPlaceStore]; this class only adds the
 * `viewModelScope`, turns the store's flows into `StateFlow`s, and applies the
 * small list edits (add / remove / rename / list membership) against the latest
 * snapshot before writing it back.
 */
class SavedPlacesViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SavedPlaceStore(DataStoreUtils.getInstance(application))

    val saved: StateFlow<List<SavedPlace>> =
        store.savedFlow().stateIn(viewModelScope, SharingStarted.Eagerly, store.savedInitial())
    val lists: StateFlow<Map<String, List<SavedPlace>>> =
        store.listsFlow().stateIn(viewModelScope, SharingStarted.Eagerly, store.listsInitial())

    // --- Flat saved (starred) list ----------------------------------------

    /** True if [feature] is already in the flat saved list. */
    fun isSaved(feature: SpecificFeature.RoutableFeature): Boolean =
        saved.value.any { it.matches(feature) }

    fun addSaved(feature: SpecificFeature.RoutableFeature) = launch {
        val place = SavedPlace.from(feature)
        store.updateSaved { current ->
            if (current.any { it.matches(feature) }) current else current + place
        }
    }

    fun removeSaved(place: SavedPlace) = launch { store.updateSaved { current -> current - place } }

    fun renameSaved(place: SavedPlace, newName: String) = launch {
        val name = newName.trim()
        if (name.isEmpty()) return@launch
        store.updateSaved { current -> current.map { if (it == place) it.copy(name = name) else it } }
    }

    // --- Named lists -------------------------------------------------------

    fun createList(name: String) = launch {
        val listName = name.trim()
        if (listName.isEmpty()) return@launch
        store.updateLists { current ->
            if (current.containsKey(listName)) current else current + (listName to emptyList())
        }
    }

    fun deleteList(name: String) = launch { store.updateLists { current -> current - name } }

    fun addToList(name: String, feature: SpecificFeature.RoutableFeature) = launch {
        val place = SavedPlace.from(feature)
        store.updateLists { lists ->
            val current = lists[name].orEmpty()
            if (current.any { it.matches(feature) }) lists else lists + (name to (current + place))
        }
    }

    fun addToList(name: String, place: SavedPlace) = launch {
        store.updateLists { lists ->
            val current = lists[name].orEmpty()
            if (place in current) lists else lists + (name to (current + place))
        }
    }

    fun removeFromList(name: String, place: SavedPlace) = launch {
        store.updateLists { lists ->
            val current = lists[name] ?: return@updateLists lists
            lists + (name to (current - place))
        }
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
