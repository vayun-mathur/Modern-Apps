package com.vayunmathur.measure

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/**
 * Navigation destinations for the Measure toolkit: compass, level, ruler, AR measure, saved items, settings, and
 * diagnostics.
 */
@Serializable
sealed interface Route : NavKey {
    /** Compass destination showing magnetic/true-north bearing. */
    @Serializable data object Compass : Route
    /** Bubble level destination showing pitch, roll, and edge angle. */
    @Serializable data object Level : Route
    /** On-screen ruler destination scaled to the display's physical pixel density. */
    @Serializable data object Ruler : Route

    /**
     * Camera is requested here rather than at app launch: the sensor tools are fully
     * usable without it, so gating the whole app on CAMERA would be a permission prompt
     * most sessions never need.
     */
    @Serializable data object ArMeasure : Route

    /** Saved measurements destination listing recorded distances, areas, and other results. */
    @Serializable data object Saved : Route
    /** Settings destination for units, north reference, haptics, and calibration. */
    @Serializable data object Settings : Route
    /** Diagnostics destination showing tracking, sensor-rate, and camera-intrinsics detail. */
    @Serializable data object Diagnostics : Route
}
