package com.vayunmathur.emergency

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
/** Navigation destinations for the emergency surfaces. */
sealed interface Route : NavKey {
    @Serializable
    /** The SOS countdown. Hosted by SosActivity, not the main host (see its manifest entry). */
    data object Sos : Route

    @Serializable
    /** Read-only medical info and contacts, reachable over the lock screen. */
    data object ViewInfo : Route

    @Serializable
    /** The medical-info and emergency-contacts editor. */
    data object EditInfo : Route
}
