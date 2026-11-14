package com.vayunmathur.findfamily.util

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
 * [FindFamilyViewModel] implements the three that are pure state changes; the two that
 * need the nav back stack or the clipboard are supplied by the caller.
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
 * which sheet to show, the top-bar contents and the floating action button. It carries plain
 * data only — no ViewModel — so both the real app and the store-listing previews can build it.
 *
 * The map itself is passed to `MainPageContent` as a slot, so this state deliberately says
 * nothing about map tiles or the camera.
 */
data class MainPageUiState(
    val selectedUserId: Long? = null,
    val selectedWaypointId: Long? = null,
    /** False while viewing a contact's past track (history mode). */
    val isShowingPresent: Boolean = true,
    val usingGpsFallback: Boolean = false,
    /** Whether UWB "Find Nearby" is available on this device (controls the overlay entry). */
    val uwbAvailable: Boolean = false,
    /** This device's own user id, so the layout can hide self-only controls. */
    val selfUserId: Long = -1L,
    /** The selected contact, resolved for the person sheet, history title and delete action. */
    val selectedUser: User? = null,
    val waypointName: String = "",
    val waypointRange: String = "",
    val familyList: FamilyListUiState = FamilyListUiState(),
    /** Built for [selectedUser] when a person (not a place) is selected. */
    val person: PersonUiState? = null,
) {
    /** A contact is selected and we're viewing their past track. */
    val historyMode: Boolean get() = selectedUserId != null && !isShowingPresent
    val isSelfSelected: Boolean get() = selectedUserId != null && selectedUserId == selfUserId
    val nothingSelected: Boolean get() = selectedUserId == null && selectedWaypointId == null
}

/**
 * Map-overlay and FAB callbacks for the map page. Same no-op-default arrangement as the sheet
 * action interfaces so [Noop] is all a preview needs. The stateful `MainPage` wires these to
 * the ViewModel and nav back stack.
 */
interface MainPageActions {
    fun clearSelection() {}
    fun setShowingPresent(present: Boolean) {}
    fun onGpsWarningClick() {}
    fun onShowSecurityCode() {}
    fun openUwbRanging(userId: Long) {}
    fun deleteSelectedUser() {}
    fun deleteSelectedWaypoint() {}
    fun addPerson() {}
    fun beginCreateWaypoint() {}
    fun addLink() {}
    fun addTracker() {}
    fun saveCurrentWaypoint() {}
    /** Enter history mode for the selected contact. */
    fun enterHistory() {}
    fun setWaypointName(name: String) {}
    fun setWaypointRange(range: String) {}

    /** No-op [MainPageActions] for previews. */
    companion object {
        val Noop: MainPageActions = object : MainPageActions {}
    }
}
