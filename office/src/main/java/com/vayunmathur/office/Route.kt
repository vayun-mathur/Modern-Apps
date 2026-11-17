package com.vayunmathur.office

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/** Top-level navigation routes for the Office app (shared nav framework). */
@Serializable
sealed interface Route : NavKey {
    /** Offline home tab (sibling route, the start destination). */
    @Serializable data object OfflineHome : Route
    /** Online cloud-docs home tab (sibling route). */
    @Serializable data object OnlineHome : Route
    /** Editing a local/offline document (optionally identified by its source uri). */
    @Serializable data class OfflineEditor(val uri: String? = null) : Route
    /** Editing a cloud-synced document, identified by its document id. */
    @Serializable data class OnlineEditor(val docId: String) : Route
}
