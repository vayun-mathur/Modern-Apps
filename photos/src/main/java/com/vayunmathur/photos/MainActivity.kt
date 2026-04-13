package com.vayunmathur.photos

import android.Manifest
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
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
import androidx.compose.runtime.LaunchedEffect
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
import com.vayunmathur.photos.data.MIGRATION_1_2
import com.vayunmathur.photos.data.MIGRATION_2_3
import com.vayunmathur.photos.ui.GalleryPage
import com.vayunmathur.photos.ui.MapPage
import com.vayunmathur.photos.ui.PhotoPage
import com.vayunmathur.photos.ui.EditPhotoPage
import kotlinx.serialization.Serializable
import com.vayunmathur.photos.util.ImageLoader
import com.vayunmathur.photos.util.SyncWorker

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val db = buildDatabase<PhotoDatabase>(listOf(MIGRATION_1_2, MIGRATION_2_3))
        val viewModel = DatabaseViewModel(db, Photo::class to db.photoDao())
        ImageLoader.init(this)
        SyncWorker.runOnce(this)
        SyncWorker.enqueue(this)
        setContent {
            DynamicTheme {
                if(Build.VERSION.SDK_INT >= 33) {
                    PermissionsChecker(
                        arrayOf(
                            Manifest.permission.READ_MEDIA_IMAGES,
                            Manifest.permission.READ_MEDIA_VIDEO,
                            Manifest.permission.ACCESS_MEDIA_LOCATION
                        ), getString(R.string.grant_image_video_permissions)
                    ) {
                        Navigation(viewModel)
                    }
                } else {
                    PermissionsChecker(
                        arrayOf(
                            Manifest.permission.READ_EXTERNAL_STORAGE
                        ), getString(R.string.grant_storage_permission)
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
    data class EditPhoto(val id: Long): Route

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
            PhotoPage(backStack, viewModel, it.id, it.overridePhotosList)
        }

        entry<Route.EditPhoto> {
            EditPhotoPage(backStack, viewModel, it.id)
        }
    }
}

private enum class MainRoute(val route: Route, @StringRes val titleRes: Int, val icon: Int) {
    Gallery(Route.Gallery, R.string.label_gallery, R.drawable.gallery_thumbnail_24px),
    Map(Route.Map, R.string.label_map, R.drawable.map_24px)
}

@Composable
fun NavigationBar(currentRoute: Route, backStack: NavBackStack<Route>) {
    ShortNavigationBar {
        MainRoute.entries.forEach {
            ShortNavigationBarItem(it.route == currentRoute, { backStack.add(it.route) }, {
                Icon(painterResource(it.icon), null)
            }, {
                Text(stringResource(it.titleRes))
            })
        }
    }
}