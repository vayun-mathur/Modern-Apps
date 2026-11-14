package com.vayunmathur.findfamily.util

import com.vayunmathur.findfamily.data.Coord
import com.vayunmathur.findfamily.data.FindFamilyRepository
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.library.map.GeoPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Map-page UI state extracted from [FindFamilyViewModel] so the ViewModel
 * stays under the function cap.
 *
 * Owns which user / waypoint is selected, present-vs-history display, the
 * historical position, and the waypoint editing form (name / range /
 * coordinate + edited id, persisted through [repository] on [scope]).
 * Selection writes are synchronous; only [saveCurrentWaypoint] launches.
 */
class MapPageState(
    private val scope: CoroutineScope,
    private val repository: FindFamilyRepository,
) {

    private val _selectedUserId = MutableStateFlow<Long?>(null)
    val selectedUserId: StateFlow<Long?> = _selectedUserId.asStateFlow()

    private val _selectedWaypointId = MutableStateFlow<Long?>(null)
    val selectedWaypointId: StateFlow<Long?> = _selectedWaypointId.asStateFlow()

    private val _isShowingPresent = MutableStateFlow(true)
    val isShowingPresent: StateFlow<Boolean> = _isShowingPresent.asStateFlow()

    private val _historicalPosition = MutableStateFlow<GeoPoint?>(null)
    val historicalPosition: StateFlow<GeoPoint?> = _historicalPosition.asStateFlow()

    private val _waypointName = MutableStateFlow("")
    val waypointName: StateFlow<String> = _waypointName.asStateFlow()

    private val _waypointRange = MutableStateFlow("")
    val waypointRange: StateFlow<String> = _waypointRange.asStateFlow()

    private val _waypointCoord = MutableStateFlow(Coord(0.0, 0.0))
    val waypointCoord: StateFlow<Coord> = _waypointCoord.asStateFlow()

    fun setSelectedUserId(id: Long?) {
        _selectedUserId.value = id
        // A new person means no historical pin yet: never show whoever was viewed
        // before as this person's position (GitHub #818 follow-up).
        _historicalPosition.value = null
    }
    fun setSelectedWaypointId(id: Long?) { _selectedWaypointId.value = id }
    fun setShowingPresent(value: Boolean) {
        _isShowingPresent.value = value
        // Leaving history drops the historical pin with it, so it cannot linger as a
        // ghost marker or a stale camera target once live tracking resumes.
        if (value) _historicalPosition.value = null
    }
    fun setHistoricalPosition(position: GeoPoint?) { _historicalPosition.value = position }
    fun setWaypointName(name: String) { _waypointName.value = name }
    fun setWaypointRange(range: String) { _waypointRange.value = range }
    fun setWaypointCoord(coord: Coord) { _waypointCoord.value = coord }

    fun selectUser(userId: Long) {
        _selectedUserId.value = userId
        _selectedWaypointId.value = null
        _isShowingPresent.value = true
        // Same reason as setSelectedUserId: the newly selected person starts with no
        // historical pin of their own.
        _historicalPosition.value = null
    }

    fun clearSelection() {
        _selectedUserId.value = null
        _selectedWaypointId.value = null
        _historicalPosition.value = null
    }

    /**
     * Apply the initial selection passed in via navigation. Called from the
     * MainPage entry so that opening the screen with a deep-linked user or
     * waypoint id selects it on arrival.
     */
    fun applyInitialSelection(initialUserId: Long?, initialWaypointId: Long?) {
        _selectedUserId.value = initialUserId
        _selectedWaypointId.value = initialWaypointId
        // A deep link can swap the person while history mode stays on, so the previous
        // person's pin must not survive the switch.
        _historicalPosition.value = null
    }

    /** Begin creating a brand-new waypoint with sensible defaults. */
    fun beginCreateWaypoint() {
        _selectedWaypointId.value = 0L
        _waypointName.value = ""
        _waypointRange.value = "100"
        _waypointCoord.value = Coord(0.0, 0.0)
    }

    /** Begin editing an existing waypoint, prefilling the form. */
    fun beginEditWaypoint(waypoint: Waypoint) {
        _selectedWaypointId.value = waypoint.id
        _waypointName.value = waypoint.name
        _waypointRange.value = waypoint.range.toString()
        _waypointCoord.value = waypoint.coord
    }

    /**
     * Persist the in-progress waypoint. Silently no-ops if the form is invalid
     * (matching the original FAB-click behaviour).
     */
    fun saveCurrentWaypoint() {
        val name = _waypointName.value
        val range = _waypointRange.value.toDoubleOrNull() ?: return
        if (name.isBlank()) return
        val id = _selectedWaypointId.value ?: return
        val coord = _waypointCoord.value
        scope.launch(Dispatchers.IO) {
            val base = if (id == 0L) Waypoint.NEW_WAYPOINT else repository.waypointStore.get(id)
            repository.waypointStore.upsert(base.copy(name = name, range = range, coord = coord))
            withContext(Dispatchers.Main) {
                _selectedWaypointId.value = null
            }
        }
    }
}
