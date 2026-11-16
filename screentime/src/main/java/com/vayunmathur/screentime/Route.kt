package com.vayunmathur.screentime

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
/** Navigation destinations for Screen Time. */
sealed interface Route : NavKey {
    @Serializable
    /** The usage dashboard, reached from Settings > Digital Wellbeing. */
    data object Dashboard : Route

    @Serializable
    /** One app's usage, timer and pause state; the package name is the identity. */
    data class AppDetails(val packageName: String) : Route

    @Serializable
    /** The overnight wind-down schedule, reached from Settings. */
    data object WindDown : Route
}
