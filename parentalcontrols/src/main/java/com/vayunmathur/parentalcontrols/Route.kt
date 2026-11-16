package com.vayunmathur.parentalcontrols

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
/** Navigation destinations for Parental Controls. */
sealed interface Route : NavKey {
    @Serializable
    /** The top-level parental-controls page from Settings. Read-only without the PIN. */
    data object Dashboard : Route

    @Serializable
    /** The device-wide daily screen-time budget. Parent-gated. */
    data object DailyLimit : Route

    @Serializable
    /** Per-app daily caps. Parent-gated. */
    data object AppLimits : Route

    @Serializable
    /** The nightly bedtime window. Parent-gated. */
    data object Bedtime : Route

    @Serializable
    /** The daytime downtime window. Parent-gated. */
    data object Downtime : Route

    @Serializable
    /** The school-hours window. Parent-gated. */
    data object SchoolTime : Route

    @Serializable
    /** Web content filters. Parent-gated. */
    data object WebFilters : Route

    @Serializable
    /** First-run PIN creation, and PIN change after the gate passes. */
    data object PinSetup : Route

    @Serializable
    /**
     * The parent-PIN challenge for [target]. The gated destination itself is
     * never on the stack below this route: verification replaces the gate
     * with the destination, and cancel pops it (finishing the host when the
     * gate is the only entry), so back can never expose gated content.
     */
    data class PinGate(val target: PinGateTarget) : Route
}

/** What a passed [Route.PinGate] unlocks. */
@Serializable
enum class PinGateTarget {
    /** Applying the dashboard master switch. */
    Controls,

    /** Replacing the PIN: the gate is followed by [Route.PinSetup]. */
    ManagePin,
    DailyLimit,
    AppLimits,
    Bedtime,
    Downtime,
    SchoolTime,
    WebFilters;

    /** The editor route this target unlocks; null when it unlocks an action, not a screen. */
    fun editorRoute(): Route? = when (this) {
        Controls, ManagePin -> null
        DailyLimit -> Route.DailyLimit
        AppLimits -> Route.AppLimits
        Bedtime -> Route.Bedtime
        Downtime -> Route.Downtime
        SchoolTime -> Route.SchoolTime
        WebFilters -> Route.WebFilters
    }

    companion object {
        /** The gate target guarding [route], or null for ungated destinations. */
        fun forRoute(route: Route): PinGateTarget? = when (route) {
            Route.DailyLimit -> DailyLimit
            Route.AppLimits -> AppLimits
            Route.Bedtime -> Bedtime
            Route.Downtime -> Downtime
            Route.SchoolTime -> SchoolTime
            Route.WebFilters -> WebFilters
            else -> null
        }
    }
}
