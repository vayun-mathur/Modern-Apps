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
import com.vayunmathur.photos.ui.COLUMN_COUNT_KEY
import com.vayunmathur.photos.ui.LocalColumnCount
import com.vayunmathur.photos.ui.MAX_COLUMN_COUNT
import com.vayunmathur.photos.ui.MIN_COLUMN_COUNT
import com.vayunmathur.photos.data.PhotosRepository
import com.vayunmathur.photos.widget.PhotoGlanceWidgetReceiver
import com.vayunmathur.photos.ui.AlbumDetailPage
import com.vayunmathur.photos.ui.FavoritesPage
import com.vayunmathur.photos.ui.AlbumsPage
import com.vayunmathur.photos.ui.MapPage
import com.vayunmathur.photos.ui.PeoplePage
import com.vayunmathur.photos.ui.SecureFolderPage
import com.vayunmathur.photos.ui.VaultViewerPage
import com.vayunmathur.photos.ui.WallpaperPage
import com.vayunmathur.photos.platform.GalleryViewModel
import com.vayunmathur.photos.platform.GalleryViewModelFactory
import com.vayunmathur.photos.platform.ImageLoader
import com.vayunmathur.photos.platform.PhotoMapViewModel
import com.vayunmathur.photos.platform.PhotoMapViewModelFactory
import com.vayunmathur.photos.platform.SecureFolderViewModel
import com.vayunmathur.photos.platform.SecureFolderViewModelFactory
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt


/** Main Activity: a ma fragment activity implementation for main activity. */
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
