package com.vayunmathur.parentalcontrols.domain

import com.vayunmathur.parentalcontrols.Route
import kotlinx.serialization.Serializable

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

    /** Route lookup for the PIN-gate target guarding each editor. */
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
