package com.vayunmathur.files

import com.vayunmathur.files.platform.FileCategory
import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/**
 * Where the browser is, as navigation rather than as view-model state.
 *
 * Depth is the point. A folder three levels down is three entries, not one entry holding a long
 * path, because that is what makes Back go up exactly one level and lets the predictive-back
 * preview show the folder the gesture will actually land on. The previous single [Home]/`Browser`
 * pair could not express depth at all, so directory traversal lived in `FilesViewModel.handleBack`
 * and the system gesture had nothing to animate.
 */
@Serializable
sealed interface Route : NavKey {
    /** The home screen: storage meter, shortcuts, recents, and bookmarks. */
    @Serializable
    data object Home : Route

    /** A folder on the real filesystem. One entry per level. */
    @Serializable
    data class Directory(val path: String) : Route

    /**
     * A flat, cross-directory listing - every image on the device, say - so it has no parent folder
     * to go up to and always sits directly on top of [Home].
     */
    @Serializable
    data class Category(val category: FileCategory) : Route

    /**
     * The system trash. Like [Category], a flat listing with no parent folder, so it always
     * sits directly on top of [Home].
     */
    @Serializable
    data object Trash : Route

    /**
     * Inside an archive. [internalPath] is empty at the archive root and gains one entry per folder
     * within it, so Back walks out of the archive the same way it walks up a directory.
     */
    @Serializable
    data class Zip(val zipPath: String, val internalPath: String = "") : Route
}
