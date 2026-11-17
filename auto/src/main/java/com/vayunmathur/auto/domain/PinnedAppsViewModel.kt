package com.vayunmathur.auto.domain

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.auto.data.PinnedAppsPrefs
import com.vayunmathur.auto.platform.CarAppDiscovery
import com.vayunmathur.auto.platform.DiscoveredApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One row in the pin picker: the app plus its pinned rank (null = unpinned). */
data class PinnedAppRow(
    val app: DiscoveredApp,
    val rank: Int?,
)

/**
 * The phone pin picker state: every discovered car app plus the pinned order.
 *
 * Toggle pins/unpins (guarding the max-5 cap, reporting rejection so the UI
 * can snackbar); move reorders. All writes go through [PinnedAppsPrefs].
 */
@androidx.car.app.annotations.ExperimentalCarApi
class PinnedAppsViewModel(application: Application) : AndroidViewModel(application) {
    private val context: android.content.Context get() = getApplication<android.app.Application>().applicationContext

    private val _allApps = MutableStateFlow<List<DiscoveredApp>>(emptyList())

    /** Every discovered car app, in label order. */
    val allApps: StateFlow<List<DiscoveredApp>> = _allApps

    /** Ordered pinned ids, intersected with the discovered set. */
    val pinnedIds: StateFlow<List<String>> = PinnedAppsPrefs.pinnedFlow(context)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /** Picker rows: pinned first in dock order, then the rest in label order. */
    val rows: StateFlow<List<PinnedAppRow>> = combine(_allApps, pinnedIds) { apps, pinned ->
        val rankById = pinned.withIndex().associate { (index, id) -> id to index }
        val pinnedRows = pinned.mapNotNull { id ->
            apps.firstOrNull { it.id == id }?.let { PinnedAppRow(it, rankById[id]) }
        }
        val unpinned = apps.filter { it.id !in rankById }.map { PinnedAppRow(it, null) }
        pinnedRows + unpinned
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /** Emits ids the toggle rejected (dock full); the UI snackbars on these. */
    private val _rejected = MutableStateFlow<String?>(null)
    val rejected: StateFlow<String?> = _rejected

    init {
        refresh()
    }

    /** Re-runs discovery (package changes while the picker is open). */
    @androidx.car.app.annotations.ExperimentalCarApi
    fun refresh() {
        viewModelScope.launch {
            _allApps.value = runCatching { CarAppDiscovery.queryAll(context) }.getOrDefault(emptyList())
        }
    }

    /** Pins or unpins [id]; reports rejection when the dock is full. */
    fun toggle(id: String) {
        viewModelScope.launch {
            val pinned = PinnedAppsPrefs.pinnedNow(context)
            if (id in pinned) {
                PinnedAppsPrefs.unpin(context, id)
            } else if (!PinnedAppsPrefs.pin(context, id)) {
                _rejected.value = id
            }
        }
    }

    /** Clears a delivered rejection. */
    fun consumeRejected() {
        _rejected.value = null
    }

    /** Moves [id] one step toward the front of the dock. */
    fun moveUp(id: String) {
        viewModelScope.launch {
            val pinned = PinnedAppsPrefs.pinnedNow(context)
            val index = pinned.indexOf(id)
            if (index > 0) PinnedAppsPrefs.move(context, id, index - 1)
        }
    }

    /** Moves [id] one step toward the back of the dock. */
    fun moveDown(id: String) {
        viewModelScope.launch {
            val pinned = PinnedAppsPrefs.pinnedNow(context)
            val index = pinned.indexOf(id)
            if (index >= 0) PinnedAppsPrefs.move(context, id, index + 1)
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
