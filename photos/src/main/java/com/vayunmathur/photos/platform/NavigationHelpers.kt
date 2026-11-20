package com.vayunmathur.photos.platform

import android.content.ContentUris
import android.net.Uri
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.data.VaultPhotoDao

/**
 * Re-tapping a photo (or swiping to another) replaces the viewer instead of stacking it.
 *
 * On a two-pane layout the grid stays visible beside the viewer, so opening a second photo
 * would otherwise pile PhotoPage on PhotoPage; back would then step through stale viewers
 * instead of returning to the grid.
 */
internal fun NavBackStack<Route>.pushPhoto(route: Route.PhotoPage) {
    if (last() is Route.PhotoPage) setLast(route) else add(route)
}

/** Whether leaving [topRoute] should re-lock the vault (opt-in, off by default). */
internal fun shouldRelock(
    relockOnExit: Boolean,
    topRoute: Route?,
    vaultPhotoDao: com.vayunmathur.photos.data.VaultPhotoDao?,
): Boolean {
    if (!relockOnExit || topRoute == null || vaultPhotoDao == null) return false
    // The vault viewer counts as inside the folder.
    return topRoute !is Route.SecureFolder && topRoute !is Route.VaultViewer
}

/** The viewer root for an ACTION_VIEW launch, or null for the normal gallery root. */
internal fun viewerRouteFor(viewUri: Uri?): Route.PhotoPage? {
    viewUri ?: return null
    return Route.PhotoPage(
        runCatching { ContentUris.parseId(viewUri) }.getOrNull() ?: -1L,
        com.vayunmathur.photos.PhotoSource.Gallery,
        viewUri.toString(),
    )
}
