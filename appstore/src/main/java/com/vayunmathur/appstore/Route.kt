package com.vayunmathur.appstore

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
/** Navigation destinations for the phone store UI. */
sealed interface Route : NavKey {
    /** Tab destination showing the store home page. */
    @Serializable data object Home : Route
    /** Tab destination showing store search. */
    @Serializable data object Search : Route
    /** Tab destination showing available updates. */
    @Serializable data object Updates : Route
    /** Tab destination showing the installed-app library. */
    @Serializable data object Library : Route
    /** Tab destination showing store sources. */
    @Serializable data object Sources : Route
    /** Destination showing source trust information. */
    @Serializable data object Trust : Route
    /** Destination showing one app's detail page, named by its package. */
    @Serializable data class Detail(val packageName: String) : Route
    /** Destination browsing one home-page section by its ID. */
    @Serializable data class Browse(val sectionId: String) : Route

    /**
     * Full-screen screenshot viewer (#918). Carries its own URL list rather than reading the
     * selected app back out of the view model, so a restored back stack still has its images.
     */
    @Serializable
    data class Screenshots(val appName: String, val urls: List<String>, val index: Int) : Route
}

/**
 * Five tabs and two pushed screens.
 *
 * Sources used to be behind the gear on the home bar, which spent a settings icon on a
 * screen opened perhaps once. It is now a tab of its own, and search — previously a text
 * field wedged above the browse list — has its own tab so browsing and searching no
 * longer fight over the same screen.
 */
