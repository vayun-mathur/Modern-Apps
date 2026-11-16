package com.vayunmathur.maps.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import com.vayunmathur.library.map.MapBody
import com.vayunmathur.maps.data.PostedLimit
import com.vayunmathur.maps.ui.MapCategory
import com.vayunmathur.maps.util.RouteService

/**
 * Everything the map chrome remembers, and the single place where retain-vs-remember is decided.
 *
 * The rule, applied once here instead of at ten scattered declarations:
 *
 *  * **`retain`** for anything the user *chose* — the travel mode, the category filter, which
 *    whether the camera follows, whether it is north-up. Losing a choice on
 *    rotation reads as the app forgetting what you told it, and all but one of these were
 *    `remember`, so it did.
 *  * **`retain`** also for [postedLimit], which is neither a choice nor cheap: it is re-queried
 *    from the live map on the next GPS fix, so `remember` blanked the speed-limit sign for a
 *    second after every rotation.
 *  * **`remember`** only for the genuinely ephemeral — [lastProgrammaticMoveMs] is a timestamp
 *    used to tell our own camera animation apart from a user pan, and it means nothing once the
 *    composition it was measured in is gone.
 *
 * The properties delegate to the [MutableState] instances handed in, so reads and writes go
 * straight to the retained state; there is no copy to keep in sync.
 */
@Stable
class MapChromeState internal constructor(
    selectedRouteTypeState: MutableState<RouteService.TravelMode>,
    selectedCategoryState: MutableState<MapCategory?>,
    autoFollowState: MutableState<Boolean>,
    northUpState: MutableState<Boolean>,
    postedLimitState: MutableState<PostedLimit?>,
    lastProgrammaticMoveMsState: MutableState<Long>,
) {
    /** Which travel mode's route the sheet is showing. */
    var selectedRouteType by selectedRouteTypeState

    /** Active browse-category POI filter, or null for no filter. */
    var selectedCategory by selectedCategoryState

    /**
     * Which body the globe draws: Earth (vector basemap) or Moon (NASA SVS
     * raster pair). Session-only — deliberately `remember`, not `retain`:
     * rotation keeps it (same composition), but a process restart resets to
     * Earth. A view, not a setting.
     */
    var body: MapBody by mutableStateOf(MapBody.Earth)

    /** Whether the camera follows the puck during navigation. Cleared by a user pan. */
    var autoFollow by autoFollowState

    /** North-up vs heading-up during navigation. */
    var northUp by northUpState

    /** Posted speed limit under the puck, from the maxspeed overlay. */
    var postedLimit by postedLimitState

    /**
     * When we last moved the camera ourselves.
     *
     * Used to tell our own `animateTo` apart from a user pan: `isCameraMoving` cannot say who
     * started the move, so anything within a short window of our own animation is ours.
     */
    var lastProgrammaticMoveMs by lastProgrammaticMoveMsState

    /** Toggle a category filter off when the active chip is tapped again. */
    fun toggleCategory(category: MapCategory) {
        selectedCategory = if (selectedCategory == category) null else category
    }

}

/** The map chrome state for this screen. */
@Composable
fun rememberMapChromeState(): MapChromeState {
    val selectedRouteType = retain { mutableStateOf(RouteService.TravelMode.DRIVE) }
    val selectedCategory = retain { mutableStateOf<MapCategory?>(null) }
    val autoFollow = retain { mutableStateOf(true) }
    val northUp = retain { mutableStateOf(false) }
    val postedLimit = retain { mutableStateOf<PostedLimit?>(null) }
    val lastProgrammaticMoveMs = remember { mutableStateOf(0L) }

    return remember {
        MapChromeState(
            selectedRouteTypeState = selectedRouteType,
            selectedCategoryState = selectedCategory,
            autoFollowState = autoFollow,
            northUpState = northUp,
            postedLimitState = postedLimit,
            lastProgrammaticMoveMsState = lastProgrammaticMoveMs,
        )
    }
}
