package com.vayunmathur.findfamily.platform

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

/**
 * Map-page UI state extracted from [FindFamilyViewModel] so the ViewModel
 * stays under the function cap.
 *
 * Owns present-vs-history display, the historical position, and the waypoint
 * editing form (name / range / coordinate, persisted through [repository] on
 * [scope]). Which person/place is selected is NOT held here: selection lives
 * in the navigation back stack (`Route.PersonSheet` / `Route.WaypointSheet`),
 * and call sites receive the selected id from the route. Only
 * [saveCurrentWaypoint] launches.
 */
class MapPageState(
    private val scope: CoroutineScope,
    private val repository: FindFamilyRepository,
) {

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

    /**
     * Reset history state for a newly opened person sheet. Called when a
     * `Route.PersonSheet` is pushed: the newly selected person starts in
     * present mode with no historical pin of their own (GitHub #818).
     */
    fun onPersonOpened() {
        _isShowingPresent.value = true
        _historicalPosition.value = null
    }

    /** Begin creating a brand-new waypoint with sensible defaults. */
    fun beginCreateWaypoint() {
        _waypointName.value = ""
        _waypointRange.value = "100"
        _waypointCoord.value = Coord(0.0, 0.0)
    }

    /** Begin editing an existing waypoint, prefilling the form. */
    fun beginEditWaypoint(waypoint: Waypoint) {
        _waypointName.value = waypoint.name
        _waypointRange.value = waypoint.range.toString()
        _waypointCoord.value = waypoint.coord
    }

    /**
     * Persist the in-progress waypoint [waypointId] (0 means create). Returns
     * false without touching the repository if the form is invalid (matching
     * the original FAB-click behaviour); the caller pops the sheet only on
     * true.
     */
    fun saveCurrentWaypoint(waypointId: Long): Boolean {
        val name = _waypointName.value
        val range = _waypointRange.value.toDoubleOrNull() ?: return false
        if (name.isBlank()) return false
        val coord = _waypointCoord.value
        scope.launch(Dispatchers.IO) {
            val base = if (waypointId == 0L) Waypoint.NEW_WAYPOINT else repository.waypointStore.get(waypointId)
            repository.waypointStore.upsert(base.copy(name = name, range = range, coord = coord))
        }
        return true
    }
}
