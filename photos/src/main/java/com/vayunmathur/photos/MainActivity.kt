package com.vayunmathur.photos

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.PermissionsChecker
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.PhotoDatabase
import com.vayunmathur.photos.ui.GalleryPage
import com.vayunmathur.photos.ui.MapPage
import com.vayunmathur.photos.ui.PhotoPage
import com.vayunmathur.photos.ui.SecureFolderPage
import com.vayunmathur.photos.ui.TrashPage
import com.vayunmathur.photos.util.ImageLoader
import com.vayunmathur.photos.util.SyncWorker
import kotlinx.serialization.Serializable
import com.vayunmathur.library.R as LibraryR
import com.vayunmathur.library.util.unlockDatabaseWithBiometrics
import com.vayunmathur.photos.data.VaultDatabase
import com.vayunmathur.photos.data.VaultPhoto

val LocalColumnCount = staticCompositionLocalOf<MutableFloatState> {
    error("No LocalColumnCount provided")
}

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val db = buildDatabase<PhotoDatabase>(PhotoDatabase.ALL_MIGRATIONS)
        val viewModel = DatabaseViewModel(db, Photo::class to db.photoDao())
        ImageLoader.init(this)
        setContent {
            DynamicTheme {
                val columnCount = rememberSaveable { mutableFloatStateOf(3f) }
                CompositionLocalProvider(LocalColumnCount provides columnCount) {
                    if (Build.VERSION.SDK_INT >= 33) {
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
}

@Serializable
sealed interface Route: NavKey {
    @Serializable
    data object Gallery: Route

    @Serializable
    data class PhotoPage(val id: Long, val overridePhotosList: List<Photo>?): Route

    @Serializable
    data object Map: Route

    @Serializable
    data object Trash: Route

    @Serializable
    data object SecureFolder: Route

    @Serializable
    data object Memories: Route

    @Serializable
    data object Library: Route

    @Serializable
    data object Search: Route
}

@Composable
fun Navigation(viewModel: DatabaseViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Gallery)
    val context = LocalContext.current
    val activity = context as FragmentActivity
    var vaultViewModel by remember { mutableStateOf<DatabaseViewModel?>(null) }
    var vaultPassword by remember { mutableStateOf<String?>(null) }

    MainNavigation(backStack) {
        entry<Route.Memories> {
            Scaffold(bottomBar = { NavigationBar(Route.Memories, backStack) }) { padding ->
                Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Memories Timeline", style = MaterialTheme.typography.headlineMedium)
                }
            }
        }
        entry<Route.Library> {
            Scaffold(bottomBar = { NavigationBar(Route.Library, backStack) }) { padding ->
                Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
                    Text("Library", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 24.dp))
                    
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { backStack.add(Route.Trash) }.padding(16.dp)) {
                            Box(Modifier.size(64.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
                                Icon(painterResource(LibraryR.drawable.delete_24px), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                            Text("Trash", modifier = Modifier.padding(top = 8.dp))
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { backStack.add(Route.SecureFolder) }.padding(16.dp)) {
                            Box(Modifier.size(64.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
                                Icon(painterResource(R.drawable.lock_24px), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                            Text("Locked", modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
        }
        entry<Route.Search> {
            Scaffold(bottomBar = { NavigationBar(Route.Search, backStack) }) { padding ->
                Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
                    Text("Search", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 24.dp))
                    
                    ListItem(
                        headlineContent = { Text("Places & Map") },
                        leadingContent = { Icon(painterResource(R.drawable.map_24px), null) },
                        modifier = Modifier.clickable { backStack.add(Route.Map) }
                    )
                }
            }
        }
        entry<Route.Gallery> {
            GalleryPage(backStack, viewModel, vaultViewModel, vaultPassword, onVaultUnlocked = { vvm, pass -> 
                vaultViewModel = vvm
                vaultPassword = pass
            })
        }

        entry<Route.Map> {
            MapPage(backStack, viewModel)
        }

        entry<Route.PhotoPage> {
            PhotoPage(viewModel, it.id, it.overridePhotosList)
        }

        entry<Route.Trash> {
            TrashPage(backStack, viewModel)
        }

        entry<Route.SecureFolder> {
            if (vaultViewModel == null) {
                LaunchedEffect(Unit) {
                    unlockDatabaseWithBiometrics(
                        activity,
                        onSuccess = { password ->
                            val db = activity.buildDatabase<VaultDatabase>(emptyList(), password, "vault-db")
                            vaultPassword = password
                            vaultViewModel = DatabaseViewModel(db, VaultPhoto::class to db.vaultPhotoDao())
                        },
                        onFailure = {
                            backStack.pop()
                        }
                    )
                }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                SecureFolderPage(backStack, vaultViewModel!!, vaultPassword!!)
            }
        }
    }
}

private enum class MainRoute(val route: Route, val title: String, val icon: Int) {
    Gallery(Route.Gallery, "Photos", R.drawable.gallery_thumbnail_24px),
    Memories(Route.Memories, "Memories", R.drawable.gallery_thumbnail_24px),
    Library(Route.Library, "Library", R.drawable.gallery_thumbnail_24px),
    Search(Route.Search, "Search", LibraryR.drawable.search_24px)
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