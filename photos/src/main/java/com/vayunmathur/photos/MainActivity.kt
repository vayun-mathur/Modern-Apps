package com.vayunmathur.photos

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.IconGroup
import com.vayunmathur.library.ui.IconLock
import com.vayunmathur.library.ui.IconMap
import com.vayunmathur.library.ui.IconPhotoLibrary
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.ShortNavigationBar
import com.vayunmathur.library.ui.ShortNavigationBarItem
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.PermissionsChecker
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.room.buildDatabase
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.library.widgets.updateWidgetPreviews
import com.vayunmathur.photos.data.FaceDao
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.PhotoDao
import com.vayunmathur.photos.data.PhotoDatabase
import com.vayunmathur.photos.glance.PhotoGlanceWidgetReceiver
import com.vayunmathur.photos.ui.GalleryPage
import com.vayunmathur.photos.ui.MapPage
import com.vayunmathur.photos.ui.PeoplePage
import com.vayunmathur.photos.ui.PhotoPage
import com.vayunmathur.photos.ui.SecureFolderPage
import com.vayunmathur.photos.ui.TrashPage
import com.vayunmathur.photos.util.GalleryViewModel
import com.vayunmathur.photos.util.GalleryViewModelFactory
import com.vayunmathur.photos.util.ImageLoader
import com.vayunmathur.photos.util.PhotoMapViewModel
import com.vayunmathur.photos.util.PhotoMapViewModelFactory
import com.vayunmathur.photos.util.SecureFolderViewModel
import com.vayunmathur.photos.util.SecureFolderViewModelFactory
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

private const val COLUMN_COUNT_KEY = "photos_column_count"

val LocalColumnCount = staticCompositionLocalOf<MutableFloatState> {
    error("No LocalColumnCount provided")
}

class MainActivity : FragmentActivity() {
    private lateinit var photoDao: PhotoDao
    private lateinit var faceDao: FaceDao

    private val galleryViewModel: GalleryViewModel by viewModels {
        GalleryViewModelFactory(application, photoDao, faceDao)
    }
    private val photoMapViewModel: PhotoMapViewModel by viewModels {
        PhotoMapViewModelFactory(application)
    }
    private val secureFolderViewModel: SecureFolderViewModel by viewModels {
        SecureFolderViewModelFactory(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateWidgetPreviews(PhotoGlanceWidgetReceiver::class)
        enableEdgeToEdge()
        val db = buildDatabase<PhotoDatabase>()
        photoDao = db.photoDao()
        faceDao = db.faceDao()
        ImageLoader.init(this)
        val dataStore = DataStoreUtils.getInstance(applicationContext)
        setContent {
            DynamicTheme {
                val columnCount = rememberSaveable {
                    mutableFloatStateOf(dataStore.getLong(COLUMN_COUNT_KEY)?.toFloat() ?: 3f)
                }
                LaunchedEffect(Unit) {
                    snapshotFlow { columnCount.floatValue.roundToInt().coerceIn(2, 8) }
                        .distinctUntilChanged()
                        .collect { dataStore.setLong(COLUMN_COUNT_KEY, it.toLong()) }
                }
                CompositionLocalProvider(LocalColumnCount provides columnCount) {
                    PermissionsWrapper(viewUri = if (intent?.action == Intent.ACTION_VIEW) intent?.data else null)
                }
            }
        }
    }

    @Composable
    private fun PermissionsWrapper(viewUri: Uri? = null) {
        val context = LocalContext.current
        
        // minSdk is 31. API 31-32 need READ_EXTERNAL_STORAGE, API 33+ need READ_MEDIA_*
        // MANAGE_MEDIA is needed on API 31+ (checked after storage permissions)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            PermissionsChecker(
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.ACCESS_MEDIA_LOCATION
                ), getString(R.string.grant_image_video_permissions)
            ) {
                CheckManageMediaPermission(context, viewUri)
            }
        } else {
            PermissionsChecker(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ), getString(R.string.grant_storage_permission)
            ) {
                CheckManageMediaPermission(context, viewUri)
            }
        }
    }
    
    @Composable
    private fun CheckManageMediaPermission(context: Context, viewUri: Uri? = null) {
        // Use state to track permission status, updated when activity resumes
        var hasManageMedia by remember { 
            mutableStateOf(MediaStore.canManageMedia(context)) 
        }
        
        // Re-check permission when the composable is resumed (user returns from Settings)
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    hasManageMedia = MediaStore.canManageMedia(context)
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }
        
        if (!hasManageMedia) {
            // Show a screen demanding MANAGE_MEDIA permission using Scaffold
            Scaffold { paddingValues ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Text(
                            text = "Media Management Permission Required",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Spacer(modifier = Modifier.padding(16.dp))
                        Text(
                            text = "This app needs permission to manage media files to function properly. Please grant 'Allow media management' permission in Settings.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.padding(16.dp))
                        Button(
                            onClick = {
                                val intent = Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA).apply {
                                    data = "package:${context.packageName}".toUri()
                                }
                                context.startActivity(intent)
                            }
                        ) {
                            Text("Open Settings")
                        }
                    }
                }
            }
        } else {
            Navigation(galleryViewModel, photoMapViewModel, secureFolderViewModel, viewUri)
        }
    }
}

@Serializable
sealed interface Route: NavKey {
    @Serializable
    data object Gallery: Route

    @Serializable
    data class PhotoPage(val id: Long, val overridePhotosList: List<Photo>?, val pendingUri: String? = null): Route

    @Serializable
    data object Map: Route

    @Serializable
    data object People: Route

    @Serializable
    data object Trash: Route

    @Serializable
    data object SecureFolder: Route
}

@Composable
fun Navigation(
    galleryViewModel: GalleryViewModel,
    photoMapViewModel: PhotoMapViewModel,
    secureFolderViewModel: SecureFolderViewModel,
    viewUri: Uri? = null,
) {
    val backStack = rememberNavBackStack<Route>(Route.Gallery)
    val vaultPhotoDao by secureFolderViewModel.vaultPhotoDao.collectAsState()
    val vaultPassword by secureFolderViewModel.vaultPassword.collectAsState()

    // Opened via ACTION_VIEW from another app (e.g. the camera): open the
    // swipeable PhotoPage immediately, rendering the incoming URI directly so
    // there's no wait. The MediaStore _id in a content URI equals Photo.id, so
    // once the background index writes the row (and the full sync populates the
    // rest of the library for swiping), PhotoPage reconciles to the DB-backed
    // pager. Done once per URI, even across configuration changes.
    var handledViewUri by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(viewUri) {
        if (viewUri != null && !handledViewUri) {
            handledViewUri = true
            val parsedId = runCatching { ContentUris.parseId(viewUri) }.getOrNull() ?: -1L
            backStack.add(Route.PhotoPage(parsedId, null, viewUri.toString()))
            galleryViewModel.resolveAndIndex(viewUri) {}
        }
    }

    MainNavigation(backStack) {
        entry<Route.Gallery> {
            GalleryPage(backStack, galleryViewModel, secureFolderViewModel)
        }

        entry<Route.Map> {
            MapPage(backStack, galleryViewModel, photoMapViewModel)
        }

        entry<Route.People> {
            PeoplePage(backStack, galleryViewModel)
        }

        entry<Route.PhotoPage> {
            PhotoPage(galleryViewModel, photoMapViewModel, it.id, it.overridePhotosList, it.pendingUri)
        }

        entry<Route.Trash> {
            TrashPage(backStack, galleryViewModel)
        }

        entry<Route.SecureFolder> {
            SecureFolderEntry(backStack, secureFolderViewModel, vaultPhotoDao != null, vaultPassword)
        }
    }
}

@Composable
private fun SecureFolderEntry(
    backStack: NavBackStack<Route>,
    secureFolderViewModel: SecureFolderViewModel,
    isUnlocked: Boolean,
    vaultPassword: String?,
) {
    val activity = LocalContext.current as FragmentActivity
    if (!isUnlocked) {
        LaunchedEffect(Unit) {
            secureFolderViewModel.unlock(
                activity,
                onSuccess = { _, _ -> },
                onFailure = { backStack.pop() },
            )
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        SecureFolderPage(backStack, vaultPassword!!, secureFolderViewModel)
    }
}

private enum class MainRoute(val route: Route, @StringRes val titleRes: Int, val icon: @Composable () -> Unit) {
    Gallery(Route.Gallery, R.string.label_gallery, { IconPhotoLibrary() }),
    Map(Route.Map, R.string.label_map, { IconMap() }),
    People(Route.People, R.string.label_people, { IconGroup() }),
    Trash(Route.Trash, R.string.label_trash, { IconDelete() }),
    SecureFolder(Route.SecureFolder, R.string.label_secure_folder, { IconLock() })
}

@Composable
fun NavigationBar(currentRoute: Route, backStack: NavBackStack<Route>) {
    ShortNavigationBar {
        MainRoute.entries.forEach {
            ShortNavigationBarItem(it.route == currentRoute, { backStack.add(it.route) }, {
                it.icon()
            }, {
                Text(stringResource(it.titleRes))
            })
        }
    }
}
