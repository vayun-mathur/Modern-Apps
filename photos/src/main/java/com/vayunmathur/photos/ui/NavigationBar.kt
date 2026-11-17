package com.vayunmathur.photos.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.IconAlbum
import com.vayunmathur.library.ui.IconGroup
import com.vayunmathur.library.ui.IconMap
import com.vayunmathur.library.ui.IconPhotoLibrary
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.BottomNavBarItem
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.R
import com.vayunmathur.photos.Route

private enum class MainRoute(val route: Route, @StringRes val titleRes: Int, val icon: @Composable () -> Unit) {
    Gallery(Route.Gallery, R.string.label_gallery, { IconPhotoLibrary() }),
    Map(Route.Map, R.string.label_map, { IconMap() }),
    People(Route.People, R.string.label_people, { IconGroup() }),
    Albums(Route.Albums, R.string.label_albums, { IconAlbum() }),
}

@Composable
fun NavigationBar(currentRoute: Route, backStack: NavBackStack<Route>) {
    BottomNavBar {
        MainRoute.entries.forEach {
            BottomNavBarItem(
                selected = it.route == currentRoute,
                // Photos pushes rather than resetting, so back returns to the
                // previous tab instead of leaving the app.
                onClick = { backStack.add(it.route) },
                icon = { it.icon() },
                label = stringResource(it.titleRes),
            )
        }
    }
}
