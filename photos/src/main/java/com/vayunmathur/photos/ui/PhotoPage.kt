package com.vayunmathur.photos.ui

import android.app.Activity
import android.content.Intent
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.isExpandedWidth
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.util.GalleryViewModel
import com.vayunmathur.photos.util.LiveWallpaperLauncher
import com.vayunmathur.photos.util.PhotoMapViewModel
import kotlinx.coroutines.launch

@Composable
fun PhotoPage(galleryViewModel: GalleryViewModel, photoMapViewModel: PhotoMapViewModel, id: Long, overridePhotosList: List<Photo>?, pendingUri: String? = null, backStack: NavBackStack<Route>? = null) {
    val photosAll by galleryViewModel.photos.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    // Photos deleted from within this viewer, hidden immediately so the pager
    // advances to the next photo without waiting for the MediaStore resync.
    val locallyDeleted = remember { mutableStateListOf<Long>() }
    // One remembered pass instead of an unmemoized filter plus a sort plus two
    // index scans in the composable body. The sort is gone: the DAO returns rows
    // newest-first (ORDER BY date DESC) and every list handed in via
    // overridePhotosList is already date-descending.
    val photosSorted = remember(photosAll, overridePhotosList, locallyDeleted.toList()) {
        val source = overridePhotosList ?: photosAll.filter { !it.isTrashed }
        source.filter { it.id !in locallyDeleted }
    }
    // Favorite state from the live rows: overridePhotosList (an album, person or
    // search snapshot) does not see a heart tapped after it was taken.
    val favoriteIds = remember(photosAll) { photosAll.filter { it.isFavorite }.mapTo(HashSet()) { it.id } }
    val toggleFavorite = rememberFavoriteToggle(galleryViewModel.favoriteToggler)
    val matchedCounts by galleryViewModel.faceCountByPhoto.collectAsState()
    val faceBoxes by galleryViewModel.faceBoxesByPhoto.collectAsState()

    // In-viewer delete: move the current photo to the system trash via the same
    // MediaStore IntentSender flow the grid uses. MANAGE_MEDIA (enforced at app
    // start) means no per-item confirmation popup. On success we hide it locally
    // (the pager falls through to the next photo) and trash it in the DB.
    //
    // Declared before the delete launcher: its result callback resets the chrome
    // so the photo the pager falls through to keeps its info card.
    var isMetadataVisible by remember { mutableStateOf(true) }
    var pendingDelete by remember { mutableStateOf<Photo?>(null) }
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingDelete?.let { p ->
                locallyDeleted.add(p.id)
                galleryViewModel.trashPhotoLocally(p)
            }
            galleryViewModel.runSync()
            // The pager falls through to the next photo: keep its chrome up so
            // the info card is there regardless of taps on the trashed one.
            isMetadataVisible = true
        }
        pendingDelete = null
    }
    val onDeletePhoto: (Photo) -> Unit = { p ->
        pendingDelete = p
        val pendingIntent = MediaStore.createTrashRequest(
            context.contentResolver, listOf(p.uri.toUri()), true
        )
        deleteLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
    }

    // Resolve the page to open by id, falling back to the incoming view-intent
    // URI. A freshly received photo may not be indexed into the DB yet, so it
    // won't be in the list until the background sync writes its row.
    val initialIndex =
        remember(photosSorted, id, pendingUri) {
            var index = photosSorted.indexOfFirst { it.id == id }
            if (index == -1 && pendingUri != null) {
                index = photosSorted.indexOfFirst { it.uri == pendingUri }
            }
            index
        }

    // Once the pager is showing, stay in it even if the originally-opened photo
    // leaves the list (e.g. the user just deleted it) — otherwise deleting the
    // current photo would blank the screen instead of advancing to the next.
    var hasEntered by remember { mutableStateOf(false) }
    if (initialIndex != -1) hasEntered = true

    // Not in the library yet: show the incoming image directly so the viewer
    // opens instantly. Once indexing adds the row, this recomposes into the
    // swipeable pager below (initialIndex becomes valid).
    if (!hasEntered) {
        if (pendingUri != null) {
            PendingPhotoView(uri = pendingUri, context = context)
        }
        return
    }

    // Deleting the last remaining photo empties the list — leave the viewer.
    LaunchedEffect(photosSorted.isEmpty()) {
        if (photosSorted.isEmpty()) backStack?.pop()
    }

    var refreshKey by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshKey++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Persist zoom states in a map that survives as long as this screen is active
    val zoomStates = remember { mutableStateMapOf<Long, ZoomState>() }

    if (photosSorted.isNotEmpty()) {
        val pagerState =
            rememberPagerState(initialPage = initialIndex.coerceAtLeast(0), pageCount = { photosSorted.size })

        // RAW SCAFFOLD EXCEPTION: bar-less full-screen black media viewer (no top/bottom
        // bar); controls are a bottom overlay inside the pager. AppScaffold always renders a
        // top app bar, which would break the immersive viewer.
        Scaffold(containerColor = Color.Black) { paddingValues ->
            // Expanded windows keep the same pager but beside a filmstrip of the
            // surrounding photos; compact keeps the full-bleed pager.
            val pagerContent: @Composable (Modifier) -> Unit = { pagerModifier ->
                HorizontalPager(
                    state = pagerState,
                    modifier = pagerModifier.padding(paddingValues),
                    beyondViewportPageCount = 1,
                    userScrollEnabled = true,
                    // Without a key, a list change re-associates pages by index, so
                    // the two neighbours kept composed either side end up holding
                    // the wrong photo.
                    key = { photosSorted[it].id },
                ) { pageIndex ->
                    val photo = photosSorted[pageIndex]
                    // derivedStateOf rather than a direct map read: reading zoomStates
                    // here would recompose this pager item — and all of
                    // PhotoDetailView — on every frame of a pinch. The state object is
                    // passed down and read only inside draw/layer lambdas.
                    val zoomState = remember(photo.id) {
                        derivedStateOf { zoomStates[photo.id] ?: ZoomState() }
                    }

                    PhotoDetailView(
                        photo = photo,
                        context = context,
                        photoMapViewModel = photoMapViewModel,
                        pagerState = pagerState,
                        pageIndex = pageIndex,
                        isSettled = pagerState.settledPage == pageIndex,
                        isMetadataVisible = isMetadataVisible,
                        currentZoom = zoomState,
                        peopleCount = matchedCounts[photo.id] ?: 0,
                        faceBoxes = faceBoxes[photo.id],
                        onOpenPerson = { clusterId ->
                            // Resolved on demand rather than by collecting the whole
                            // `people` list here, which this screen needed for
                            // nothing else.
                            coroutineScope.launch {
                                val clusterPhotos = galleryViewModel.photosForCluster(clusterId)
                                clusterPhotos.firstOrNull()?.let { first ->
                                    backStack?.add(Route.PhotoPage(first.id, clusterPhotos))
                                }
                            }
                        },
                        onZoomUpdate = { newState -> zoomStates[photo.id] = newState },
                        onToggleMetadata = { isMetadataVisible = !isMetadataVisible },
                        refreshKey = refreshKey,
                        onEditPhoto = {
                            val activityClass =
                                if (photo.videoData != null) VideoEditActivity::class.java
                                else EditActivity::class.java
                            val intent =
                                Intent(context, activityClass).apply {
                                    putExtra("photo_id", photo.id)
                                }
                            context.startActivity(intent)
                        },
                        onSetWallpaper = { p ->
                            if (p.videoData != null || p.isGif) {
                                coroutineScope.launch {
                                    LiveWallpaperLauncher.apply(context, p.uri, p.videoData != null)
                                }
                            } else {
                                backStack?.add(Route.Wallpaper(p.id, p.uri))
                            }
                        },
                        onDelete = onDeletePhoto,
                        isFavorite = photo.id in favoriteIds,
                        onToggleFavorite = { p -> toggleFavorite(listOf(p.copy(isFavorite = p.id in favoriteIds))) },
                        // The route's own photo, not whichever page the pager has settled on:
                        // after a swipe those differ, and back would then morph into a grid tile
                        // the user never tapped.
                        sharedKey = photo.id.takeIf { it == id }
                    )
                }
            }
            if (isExpandedWidth()) {
                PhotoViewerWideLayout(
                    viewer = pagerContent,
                    filmstrip = { FilmstripPane(photosSorted, pagerState) },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                pagerContent(Modifier.fillMaxSize())
            }
        }
    }
}
