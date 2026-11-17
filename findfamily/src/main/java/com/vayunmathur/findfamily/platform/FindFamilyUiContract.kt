package com.vayunmathur.findfamily.platform

import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.TemporaryLink
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.Waypoint
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The UI contract between [FindFamilyViewModel] and the bottom-sheet screens on the map
 * page.
 *
 * The sheets take a state value plus an actions interface rather than the ViewModel itself,
 * so they can be rendered by a `@Preview` — which is what the store listing images are
 * generated from. That matters more here than elsewhere: the map underneath the sheet is a
 * tile renderer that Layoutlib cannot draw, so the sheets are the only part of the main
 * screen a preview can show.
 *
 * This lives in `util` rather than `ui` so the dependency runs one way: `ui` depends on
 * `util`, and the ViewModel implements these interfaces.
 */

/** Everything the collapsed/expanded family sheet draws. */
data class FamilyListUiState(
    val connectedUsers: List<User> = emptyList(),
    val awaitingRequestUsers: List<User> = emptyList(),
    val temporaryLinks: List<TemporaryLink> = emptyList(),
    val waypoints: List<Waypoint> = emptyList(),
    /** Most recent location report per user id, for the speed/battery/last-seen line. */
    val locationByUser: Map<Long, LocationValue> = emptyMap(),
    /** Names of the people currently at each saved place, keyed by place name. */
    val userNamesByLocationName: Map<String, List<String>> = emptyMap(),
)

/**
 * Family-sheet callbacks. Every method has a no-op default so a preview can render the
 * sheet without supplying behaviour — [Noop] is the whole implementation a preview needs.
 *
 * [selectUser] and [beginEditWaypoint] are navigation actions (they push
 * `Route.PersonSheet` / `Route.WaypointSheet`); the caller that owns the back
 * stack supplies them. [FindFamilyViewModel] implements only the pure
 * data change ([deleteTemporaryLink], plus the waypoint-form prefill behind
 * `beginEditWaypoint`).
 */
interface FamilyListActions {
    fun selectUser(userId: Long) {}
    fun acceptRequest(userId: Long) {}
    fun copyLink(link: TemporaryLink) {}
    fun deleteTemporaryLink(link: TemporaryLink) {}
    fun beginEditWaypoint(waypoint: Waypoint) {}

    /** No-op [FamilyListActions] for previews. */
    companion object {
        val Noop: FamilyListActions = object : FamilyListActions {}
    }
}

/** Everything the single-person sheet draws. */
data class PersonUiState(
    val user: User,
    val location: LocationValue? = null,
    /** Saved places, offered in the auto-toggle dropdown as "Arrival at <name>" triggers. */
    val waypoints: List<Waypoint> = emptyList(),
    /** False while the Quick Settings sharing tile has sharing paused (GitHub #648). */
    val sharingGloballyEnabled: Boolean = true,
    /** Whether this phone has opted in to finding other people's powered-off devices. */
    val crowdFindingEnabled: Boolean = false,
    /**
     * Connected family members, offered on the user's own entry as the choice of who may locate
     * this phone once it is switched off. Empty for anyone else's sheet — the powered-off
     * controls are properties of this device, not of a relationship.
     */
    val connectedUsers: List<User> = emptyList(),
)

/** Person-sheet callbacks. Same no-op-default arrangement as [FamilyListActions]. */
interface PersonActions {
    fun setUserSharing(user: User, enabled: Boolean) {}

    /** Flip sharing after [duration]; null means Never. */
    fun setUserAutoToggle(user: User, duration: Duration?) {}

    /** Flip sharing when "Me" arrives at waypoint [waypointId]; null means Never. */
    fun setUserArrivalToggle(user: User, waypointId: Long?) {}

    /** Re-pick which device contact this connection is named after. */
    fun changeConnectedContact() {}

    /**
     * Watch [user] for a no-show: alert if they have not arrived at [waypointId]
     * by [expectedAt] plus [graceMinutes]. Null [waypointId] means any fresh
     * location update counts as arrival.
     */
    fun setNoShowAlert(user: User, waypointId: Long?, expectedAt: Instant, graceMinutes: Int) {}

    /** Opt in or out of acting as a finder for other people's powered-off devices. */
    fun setCrowdFinding(enabled: Boolean) {}

    /** No-op [PersonActions] for previews. */
    companion object {
        val Noop: PersonActions = object : PersonActions {}
    }
}

/**
 * Everything the stateless map-page layout (`MainPageContent`) needs to lay out its chrome:
 * the family sheet, the top-bar contents and the floating action button. It carries plain
 * data only — no ViewModel — so both the real app and the store-listing previews can build it.
 *
 * Selection is deliberately absent: `MainPage` always shows the family list.
 * The selected person/place lives in the back stack (`Route.PersonSheet` /
 * `Route.WaypointSheet`) and its UI is hosted by those destinations.
 *
 * The map itself is passed to `MainPageContent` as a slot, so this state deliberately says
 * nothing about map tiles or the camera.
 */
data class MainPageUiState(
    val usingGpsFallback: Boolean = false,
    val familyList: FamilyListUiState = FamilyListUiState(),
)

/**
 * Map-overlay and FAB callbacks for the map page. Same no-op-default arrangement as the sheet
 * action interfaces so [Noop] is all a preview needs. The stateful `MainPage` wires these to
 * the ViewModel and nav back stack. Selection-scoped actions (delete, UWB,
 * history, waypoint save) live on the `PersonSheet` / `WaypointSheet`
 * destinations, not here.
 */
interface MainPageActions {
    fun onGpsWarningClick() {}
    fun addPerson() {}
    fun beginCreateWaypoint() {}
    fun addLink() {}
    fun addTracker() {}

    /** No-op [MainPageActions] for previews. */
    companion object {
        val Noop: MainPageActions = object : MainPageActions {}
    }
}
