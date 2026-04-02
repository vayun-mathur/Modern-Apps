package com.vayunmathur.photos

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Icon
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.PermissionsChecker
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.PhotoDatabase
import com.vayunmathur.photos.ui.GalleryPage
import com.vayunmathur.photos.ui.MapPage
import com.vayunmathur.photos.ui.PhotoPage
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val db = buildDatabase<PhotoDatabase>()
        val viewModel = DatabaseViewModel(db, Photo::class to db.photoDao())
        ImageLoader.init(this)
        setContent {
            DynamicTheme {
                if(Build.VERSION.SDK_INT >= 33) {
                    PermissionsChecker(
                        arrayOf(
                            Manifest.permission.READ_MEDIA_IMAGES,
                            Manifest.permission.READ_MEDIA_VIDEO,
                            Manifest.permission.ACCESS_MEDIA_LOCATION
                        ), "Grant Image/Video Permissions"
                    ) {
                        Navigation(viewModel)
                    }
                } else {
                    PermissionsChecker(
                        arrayOf(
                            Manifest.permission.READ_EXTERNAL_STORAGE
                        ), "Grant Storage Permission"
                    ) {
                        Navigation(viewModel)
                    }
                }
            }
        }
    }
}

@Serializable
sealed interface Route: NavKey {
    @Serializable
    data object Gallery: Route

    @Serializable
    data class PhotoPage(val id: Long, val overridePhotosList: List<Photo>?): Route

    @Serializable
    data object Map: Route
}

@Composable
fun Navigation(viewModel: DatabaseViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Gallery)
    MainNavigation(backStack) {
        entry<Route.Gallery> {
            GalleryPage(backStack, viewModel)
        }

        entry<Route.Map> {
            MapPage(backStack, viewModel)
        }

        entry<Route.PhotoPage> {
            PhotoPage(viewModel, it.id, it.overridePhotosList)
        }
    }
}

private enum class MainRoute(val route: Route, val title: String, val icon: Int) {
    Gallery(Route.Gallery, "Gallery", R.drawable.gallery_thumbnail_24px),
    Map(Route.Map, "Map", R.drawable.map_24px)
}

@Composable
fun NavigationBar(currentRoute: Route, backStack: NavBackStack<Route>) {
    ShortNavigationBar {
        MainRoute.entries.forEach {
            ShortNavigationBarItem(it.route == currentRoute, { backStack.add(it.route) }, {
                Icon(painterResource(it.icon), null)
            }, {
                Text(it.title)
            })
        }
    }
}