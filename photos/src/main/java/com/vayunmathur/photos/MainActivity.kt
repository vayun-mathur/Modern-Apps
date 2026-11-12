package com.vayunmathur.photos

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.library.ui.LoadingIndicator
import com.vayunmathur.library.ui.IconAlbum
import com.vayunmathur.library.ui.IconGroup
import com.vayunmathur.library.ui.IconMap
import com.vayunmathur.library.ui.IconPhotoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import com.vayunmathur.library.ui.AppPermissionsGate
import com.vayunmathur.library.ui.AppPermissionsSpec
import com.vayunmathur.library.ui.MaFragmentActivity
import com.vayunmathur.library.ui.PermissionRequirement
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.MorphPage
import com.vayunmathur.library.util.SiblingPage
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.BottomNavBarItem
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.library.widgets.updateWidgetPreviews
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.PhotosRepository
import com.vayunmathur.photos.glance.PhotoGlanceWidgetReceiver
import com.vayunmathur.photos.ui.AlbumDetailPage
import com.vayunmathur.photos.ui.FavoritesPage
import com.vayunmathur.photos.ui.AlbumsPage
import com.vayunmathur.photos.ui.GalleryPage
import com.vayunmathur.photos.ui.MapPage
import com.vayunmathur.photos.ui.PeoplePage
import com.vayunmathur.photos.ui.PhotoPage
import com.vayunmathur.photos.ui.SecureFolderPage
import com.vayunmathur.photos.ui.TrashPage
import com.vayunmathur.photos.ui.VaultViewerPage
import com.vayunmathur.photos.ui.WallpaperPage
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
private const val MIN_COLUMN_COUNT = 2
private const val MAX_COLUMN_COUNT = 8

val LocalColumnCount = staticCompositionLocalOf<MutableFloatState> {
    error("No LocalColumnCount provided")
}

class MainActivity : MaFragmentActivity() {
    private val galleryViewModel: GalleryViewModel by viewModels {
        GalleryViewModelFactory(application, PhotosRepository.get(application))
    }
    private val photoMapViewModel: PhotoMapViewModel by viewModels {
        PhotoMapViewModelFactory(application)
    }
    private val secureFolderViewModel: SecureFolderViewModel by viewModels {
        SecureFolderViewModelFactory(application)
    }

    override fun onCreateBeforeContent() {
        updateWidgetPreviews(PhotoGlanceWidgetReceiver::class)
        ImageLoader.init(this)
    }

    @Composable
    override fun Content() {
        val dataStore = DataStoreUtils.getInstance(applicationContext)
        val columnCount = rememberSaveable {
            mutableFloatStateOf(dataStore.getLong(COLUMN_COUNT_KEY)?.toFloat() ?: 3f)
        }
        LaunchedEffect(Unit) {
            snapshotFlow { columnCount.floatValue.roundToInt().coerceIn(MIN_COLUMN_COUNT, MAX_COLUMN_COUNT) }
                .distinctUntilChanged()
                .collect { dataStore.setLong(COLUMN_COUNT_KEY, it.toLong()) }
        }
        // No OfflineAware banner: photos is offline-first and takes no
        // :library:network dependency, so the connectivity-monitor UI (which
        // needs ACCESS_NETWORK_STATE from :library:network) cannot live here
        // without reintroducing the #884 leak. Failures surface per-screen.
        CompositionLocalProvider(LocalColumnCount provides columnCount) {
            PermissionsWrapper(viewUri = if (intent?.action == Intent.ACTION_VIEW) intent?.data else null)
        }
    }

    @Composable
    private fun PermissionsWrapper(viewUri: Uri? = null) {
        // minSdk is 31. API 31-32 need READ_EXTERNAL_STORAGE, API 33+ need READ_MEDIA_*.
        val runtime: PermissionRequirement.Runtime =
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                PermissionRequirement.Runtime(
                    arrayOf(
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VIDEO,
                        Manifest.permission.ACCESS_MEDIA_LOCATION
                    )
                )
            } else {
                PermissionRequirement.Runtime(
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                )
            }
        AppPermissionsGate(
            spec = AppPermissionsSpec(
                title = getString(R.string.grant_image_video_permissions),
                requirements = listOf(runtime, PermissionRequirement.ManageMedia)
            )
        ) {
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
    data object Albums: Route

    @Serializable
    data class AlbumDetail(val albumName: String): Route

    /** The Favorites album: every MediaStore favorite, opened from its Albums tile. */
    @Serializable
    data object Favorites: Route

    @Serializable
    data object Trash: Route

    @Serializable
    data object SecureFolder: Route

    @Serializable
    data class VaultViewer(val vaultId: Long): Route

    @Serializable
    data class Wallpaper(val id: Long, val uri: String? = null) : Route
}

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
private fun shouldRelock(
    relockOnExit: Boolean,
    topRoute: Route?,
    vaultPhotoDao: com.vayunmathur.photos.data.VaultPhotoDao?,
): Boolean {
    if (!relockOnExit || topRoute == null || vaultPhotoDao == null) return false
    // The vault viewer counts as inside the folder.
    return topRoute !is Route.SecureFolder && topRoute !is Route.VaultViewer
}

@Composable
fun Navigation(
    galleryViewModel: GalleryViewModel,
    photoMapViewModel: PhotoMapViewModel,
    secureFolderViewModel: SecureFolderViewModel,
    viewUri: Uri? = null,
) {
    // Opened via ACTION_VIEW from another app (e.g. the camera): that one photo is the whole app
    // for this launch, so it is the root of the stack and the gallery is not underneath it. Back
    // then belongs to the system, which finishes this Activity straight back to whoever fired the
    // intent - the same task they started us in - and runs the cross-activity predictive back
    // animation itself. An in-app back handler would swallow that gesture, animate nothing, and
    // land the user on a home screen they never asked for.
    //
    // The MediaStore _id in a content URI equals Photo.id, so the page can be named up front and
    // render the incoming URI directly while the background index writes the row and the full sync
    // populates the rest of the library for swiping. PhotoPage reconciles to the DB-backed pager.
    val viewerRoute = remember(viewUri) { viewerRouteFor(viewUri) }

    val backStack = rememberNavBackStack<Route>(viewerRoute ?: Route.Gallery)
    val vaultPhotoDao by secureFolderViewModel.vaultPhotoDao.collectAsState()
    val vaultPassword by secureFolderViewModel.vaultPassword.collectAsState()
    val relockOnExit by secureFolderViewModel.relockOnExit.collectAsState()

    RelockEffect(backStack, relockOnExit, vaultPhotoDao, secureFolderViewModel)
    IndexViewUriEffect(viewUri, galleryViewModel)

    // PhotoPage pops itself when its last remaining photo is deleted. With the viewer as the root
    // there is nothing under it to pop to, and leaving the viewer here means leaving the app: hand
    // back to whoever sent us rather than hand NavDisplay an empty stack, which it rejects.
    val activity = LocalActivity.current
    if (backStack.backStack.isEmpty()) {
        LaunchedEffect(Unit) { activity?.finish() }
        return
    }

    MainNavigation(backStack) {
        // List role for the gallery so medium widths pair it with the viewer; Sibling motion
        // kept so switching bottom-nav tabs still crossfades instead of sliding.
        entry<Route.Gallery>(metadata = ListPage() + SiblingPage()) {
            GalleryPage(backStack, galleryViewModel, secureFolderViewModel)
        }

        entry<Route.Map>(metadata = SiblingPage()) {
            MapPage(backStack, galleryViewModel, photoMapViewModel)
        }

        entry<Route.People>(metadata = SiblingPage()) {
            PeoplePage(backStack, galleryViewModel)
        }

        entry<Route.Albums>(metadata = SiblingPage()) {
            AlbumsPage(backStack, galleryViewModel, secureFolderViewModel)
        }

        entry<Route.AlbumDetail> {
            AlbumDetailPage(backStack, galleryViewModel, it.albumName)
        }

        entry<Route.PhotoPage>(metadata = ListDetailPage() + MorphPage()) {
            PhotoPage(galleryViewModel, photoMapViewModel, it.id, it.overridePhotosList, it.pendingUri, backStack)
        }

        entry<Route.Wallpaper>(metadata = ListDetailPage()) {
            WallpaperPage(backStack, it.uri)
        }

        // Built-in collections, opened from the Albums grid's default tiles
        // (Favorites, Trash, Secure Folder). Detail pages: they push over Albums
        // and show a back arrow instead of the bottom bar.
        entry<Route.Favorites> {
            FavoritesPage(backStack, galleryViewModel, secureFolderViewModel)
        }

        entry<Route.Trash> {
            TrashPage(backStack, galleryViewModel)
        }

        entry<Route.SecureFolder> {
            SecureFolderEntry(backStack, secureFolderViewModel, vaultPhotoDao != null, vaultPassword)
        }

        entry<Route.VaultViewer>(metadata = ListDetailPage()) {
            VaultViewerEntry(backStack, it.vaultId, vaultPassword, secureFolderViewModel)
        }
    }
}

/** The viewer root for an ACTION_VIEW launch, or null for the normal gallery root. */
private fun viewerRouteFor(viewUri: Uri?): Route.PhotoPage? {
    viewUri ?: return null
    return Route.PhotoPage(
        runCatching { ContentUris.parseId(viewUri) }.getOrNull() ?: -1L,
        null,
        viewUri.toString(),
    )
}

/**
 * Opt-in re-lock: leaving the Secure Folder (any route other than the
 * folder itself or the vault viewer, which counts as inside) locks the
 * vault so the next open requires unlock again. Off by default, which
 * preserves the current stay-unlocked behaviour.
 */
@Composable
private fun RelockEffect(
    backStack: NavBackStack<Route>,
    relockOnExit: Boolean,
    vaultPhotoDao: com.vayunmathur.photos.data.VaultPhotoDao?,
    secureFolderViewModel: SecureFolderViewModel,
) {
    val topRoute = backStack.backStack.lastOrNull()
    LaunchedEffect(topRoute, relockOnExit) {
        if (shouldRelock(relockOnExit, topRoute, vaultPhotoDao)) {
            secureFolderViewModel.lock()
        }
    }
}

/** Index one incoming URI once per launch (survives configuration change). */
@Composable
private fun IndexViewUriEffect(viewUri: Uri?, galleryViewModel: GalleryViewModel) {
    var indexedViewUri by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(viewUri) {
        if (viewUri == null || indexedViewUri) return@LaunchedEffect
        indexedViewUri = true
        galleryViewModel.resolveAndIndex(viewUri) {}
    }
}

@Composable
private fun VaultViewerEntry(
    backStack: NavBackStack<Route>,
    vaultId: Long,
    vaultPassword: String?,
    secureFolderViewModel: SecureFolderViewModel,
) {
    val password = vaultPassword
    if (password != null) {
        VaultViewerPage(backStack, vaultId, password, secureFolderViewModel)
    } else {
        SecureFolderEntry(backStack, secureFolderViewModel, false, null)
    }
}

@Composable
private fun SecureFolderEntry(
    backStack: NavBackStack<Route>,
    secureFolderViewModel: SecureFolderViewModel,
    isUnlocked: Boolean,
    vaultPassword: String?,
) {
    val activity = LocalActivity.current as FragmentActivity
    if (!isUnlocked) {
        LaunchedEffect(Unit) {
            secureFolderViewModel.unlock(
                activity,
                onSuccess = { _, _ -> },
                onFailure = { message ->
                    if (message != null) {
                        AppMessages.show(message)
                    }
                    backStack.pop()
                },
            )
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LoadingIndicator()
        }
    } else {
        SecureFolderPage(backStack, vaultPassword!!, secureFolderViewModel)
    }
}

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
