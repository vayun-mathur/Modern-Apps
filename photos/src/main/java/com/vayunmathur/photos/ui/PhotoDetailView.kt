package com.vayunmathur.photos.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.library.image.compose.AnimatedImage
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.ui.FadeVisibility
import com.vayunmathur.library.ui.FilledTonalButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.sharedContainer
import com.vayunmathur.photos.R
import com.vayunmathur.photos.data.OcrLayout
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.domain.OcrBoxStore
import com.vayunmathur.photos.platform.MotionPhotoVideo
import com.vayunmathur.photos.util.PhotoFaceBoxes
import com.vayunmathur.photos.util.PhotoMapViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.absoluteValue

// Helper class to store zoom information
data class ZoomState(val scale: Float = 1f, val offset: Offset = Offset.Zero)

@Composable
fun PhotoDetailView(
    photo: Photo,
    context: Context,
    photoMapViewModel: PhotoMapViewModel,
    pagerState: PagerState,
    pageIndex: Int,
    isSettled: Boolean,
    isMetadataVisible: Boolean,
    /**
     * Live zoom/pan for this page. A [State] rather than a value so the reads
     * happen inside gesture handlers and draw/layer lambdas: a pinch then
     * re-runs the transform without recomposing the image, the overlays or the
     * metadata card.
     */
    currentZoom: State<ZoomState>,
    peopleCount: Int = 0,
    faceBoxes: PhotoFaceBoxes? = null,
    onOpenPerson: (Long) -> Unit = {},
    onZoomUpdate: (ZoomState) -> Unit,
    onToggleMetadata: () -> Unit,
    refreshKey: Int = 0,
    onEditPhoto: () -> Unit,
    onSetWallpaper: (Photo) -> Unit = {},
    onDelete: (Photo) -> Unit = {},
    /**
     * Non-null makes this page the destination of the container transform out of the grid
     * tile. Null for a page the pager keeps composed either side of the current one: they
     * match live grid tiles too, and three photos would morph at once.
     */
    sharedKey: Any? = null
) {
    val countryNames by photoMapViewModel.countryNames.collectAsState()
    val countryName = countryNames[photo.id]

    // File size for the metadata bar, read lazily from MediaStore off the UI
    // thread (no schema change needed; mirrors how countryName is fetched).
    var fileSize by remember(photo.id) { mutableStateOf<Long?>(null) }
    LaunchedEffect(photo.id) {
        fileSize = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.query(
                    photo.uri.toUri(),
                    arrayOf(MediaStore.MediaColumns.SIZE),
                    null, null, null
                )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
            }.getOrNull()
        }
    }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val sizeReader: () -> IntSize = { size }
    // Laid-out card size; zero while hidden, so taps then restore chrome as usual.
    var metadataSize by remember(photo.id) { mutableStateOf(IntSize.Zero) }

    // The viewer never shows more than screen resolution, so the decode is capped
    // to the screen's longest edge rather than the file's. Constant for the life
    // of the composition, so it can't churn the cache key the way tracking the
    // measured container size would.
    val viewerImageSize = remember(context) {
        val metrics = context.resources.displayMetrics
        maxOf(metrics.widthPixels, metrics.heightPixels)
    }

    val updatedOnZoomUpdate by rememberUpdatedState(onZoomUpdate)
    val updatedOnToggleMetadata by rememberUpdatedState(onToggleMetadata)

    // Derived state for how far this specific page is from the center
    val pageOffset by remember {
        derivedStateOf {
            ((pagerState.currentPage - pageIndex) + pagerState.currentPageOffsetFraction)
                .absoluteValue
        }
    }

    // Reset zoom only when the page is fully scrolled out of view (offset >= 1.0)
    // This allows the "fadeOut" to happen while the image is still zoomed.
    // Re-keying the OCR overlay is also how a stale text selection gets cleared:
    // selection state lives inside SelectionContainer and isn't reachable from here.
    var ocrClearToken by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        snapshotFlow { pageOffset }.filter { it >= 0.99f }.distinctUntilChanged().collect {
            if (currentZoom.value.scale > 1f) {
                updatedOnZoomUpdate(ZoomState())
            }
            ocrClearToken++
        }
    }

    // Recognised text laid over the image so it can be selected. Loaded only for
    // the settled page: the pager keeps a neighbour composed either side, and an
    // off-screen SelectionContainer would compete for the long press.
    var ocrLayout by remember(photo.id) { mutableStateOf<OcrLayout?>(null) }
    val canHaveOcrText = photo.videoData == null && !photo.isGif
    LaunchedEffect(photo.id, isSettled, canHaveOcrText) {
        if (isSettled && canHaveOcrText) {
            // layoutFor JSON-decodes every recognised line and quad, which is far
            // too much to do on Main.immediate on every swipe.
            ocrLayout = withContext(Dispatchers.Default) { ocrLayoutFor(context, photo) }
        }
    }

    LaunchedEffect(photo.id) {
        if (photo.lat != null && photo.long != null) {
            photoMapViewModel.requestCountryName(photo.id, photo.lat, photo.long)
        }
    }

    // Panorama photos show as a flat image by default; a button opens an
    // immersive viewer — a drag-to-look sphere for 360s, or a wide
    // horizontally-scrolling rectangle for flat (cylindrical) panoramas.
    val isPanorama = photo.videoData == null && photo.panoData != null
    val isSphere = photo.panoData?.isSphere == true
    var showImmersive by remember(photo.id) { mutableStateOf(false) }

    // Motion photos (still JPEG with an embedded MicroVideo clip) play like
    // panoramas view: a toggle button replaces the still with the shared
    // VideoPlayer. Detection opens the original bytes' XMP off the main
    // thread and reads as not-motion on any failure; the grid is untouched
    // (per-tile EXIF IO would jank scrolling), so the check lives here in the
    // viewer only. The GIF path below is untouched: mimeType wins there.
    val messenger = rememberMessenger()
    val motionFailedMessage = stringResource(R.string.motion_photo_failed)
    val canBeMotion = photo.videoData == null && !photo.isGif
    val isMotionPhoto by produceState(initialValue = false, photo.id, canBeMotion) {
        value = if (canBeMotion) MotionPhotoVideo.isMotionPhoto(context, photo.uri) else false
    }
    var motionPlaying by remember(photo.id) { mutableStateOf(false) }
    var motionFile by remember(photo.id) { mutableStateOf<File?>(null) }
    var motionExtracting by remember(photo.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(
        modifier =
            Modifier.fillMaxSize()
                .then(
                    photoZoomGestures(
                        currentZoom = currentZoom,
                        containerSize = sizeReader,
                        onZoomUpdate = updatedOnZoomUpdate,
                        onToggleMetadata = updatedOnToggleMetadata,
                        isChromeTap = { tap -> metadataSize.height <= 0 || tap.y < (sizeReader().height - metadataSize.height).toFloat() },
                    )
                )
    ) {
        // Shared by the image and the OCR text overlay so the two can't drift apart.
        // Reads currentZoom inside the layer block, so a pinch updates the GPU
        // transform without recomposing anything in this subtree.
        val zoomModifier =
            Modifier.graphicsLayer {
                val zoom = currentZoom.value
                scaleX = zoom.scale
                scaleY = zoom.scale
                translationX = zoom.offset.x
                translationY = zoom.offset.y
            }
        if (photo.videoData == null && !(motionPlaying && motionFile != null)) {
            val imageModifier =
                Modifier.fillMaxSize()
                    .onGloballyPositioned { layoutCoordinates ->
                        size = layoutCoordinates.size
                    }
                    .then(zoomModifier)
                    .then(
                        if (sharedKey == null) Modifier
                        else Modifier.sharedContainer("photo-image-$sharedKey")
                    )
            if (photo.isGif) {
                AnimatedImage(
                    uri = photo.uri.toUri(),
                    contentDescription = null,
                    modifier = imageModifier
                )
            } else {
                AsyncImage(
                    model =
                        ImageRequest.Builder(context)
                            .data(photo.uri.toUri())
                            // refreshKey stays out of the disk key: it bumps on
                            // every ON_RESUME, so including it there made a
                            // return from the editor or a share sheet re-fetch
                            // and re-write the file. The memory key still
                            // carries it, which is the point — the pixels may
                            // have been edited.
                            .diskCacheKey("thumb_${photo.id}_${photo.dateModified}")
                            .memoryCacheKey("thumb_${photo.id}_${photo.dateModified}_$refreshKey")
                            // Without a size this decodes at full resolution: a
                            // 12 MP photo is ~48 MB as ARGB_8888, enough for one
                            // opened photo to evict every grid thumbnail from a
                            // memory cache measured in tens of MB.
                            .size(viewerImageSize)
                            .build(),
                    contentDescription = null,
                    modifier = imageModifier,
                    contentScale = ContentScale.Fit
                )
            }
        } else if (motionPlaying && motionFile != null) {
            // Motion-photo playback. The seek bar is the frame inspector:
            // scrubbing pauses polling and seeks on release, like videos.
            // Autoplay is gated on the settled page, mirroring plain videos.
            VideoPlayer(
                modifier =
                    Modifier.fillMaxSize()
                        .onGloballyPositioned { size = it.size }
                        .then(zoomModifier),
                uri = Uri.fromFile(motionFile!!),
                isMetadataVisible = isMetadataVisible,
                isSettledPage = isSettled && motionPlaying
            )
        } else {
            VideoPlayer(
                modifier =
                    Modifier.fillMaxSize()
                        .onGloballyPositioned { size = it.size }
                        .then(zoomModifier),
                uri = photo.uri.toUri(),
                isMetadataVisible = isMetadataVisible,
                isSettledPage = isSettled
            )
        }

        PhotoDetailSection(
            photo = photo,
            context = context,
            countryName = countryName,
            fileSize = fileSize,
            pageOffset = pageOffset,
            peopleCount = peopleCount,
            isSphere = isSphere,
            isMotionPhoto = isMotionPhoto,
            isPanorama = isPanorama,
            isMetadataVisible = isMetadataVisible,
            showImmersive = showImmersive,
            motionPlaying = motionPlaying,
            motionExtracting = motionExtracting,
            faceBoxes = faceBoxes,
            ocrLayout = ocrLayout,
            ocrClearToken = ocrClearToken,
            isSettled = isSettled,
            size = size,
            currentZoom = currentZoom,
            zoomModifier = zoomModifier,
            onOpenPerson = onOpenPerson,
            onSetWallpaper = onSetWallpaper,
            onEditPhoto = onEditPhoto,
            onDelete = onDelete,
            onShowImmersive = { showImmersive = it },
            onMetadataLayout = { metadataSize = it },
            onMotionClick = {
                if (motionPlaying) {
                    motionPlaying = false
                } else if (motionFile != null) {
                    motionPlaying = true
                } else if (!motionExtracting) {
                    motionExtracting = true
                    scope.launch {
                        val file = MotionPhotoVideo.motionVideoFile(context, photo.uri, photo.id)
                        motionExtracting = false
                        if (file != null) {
                            motionFile = file
                            motionPlaying = true
                        } else {
                            // Extraction failure: stay on the still and report it.
                            messenger.show(motionFailedMessage)
                        }
                    }
                }
            },
        )
    }
}

/**
 * OCR lookup seam for tests, mirroring `workInfosOverride` in GalleryViewModel:
 * unit tests have no AndroidKeyStore (the scan database is SQLCipher-backed),
 * so they set this to return null while production leaves it null to read the
 * real recognised-text store. Behavior-preserving: the default path is unchanged.
 */
internal var ocrLayoutOverride: (suspend (android.content.Context, Photo) -> OcrLayout?)? = null

private suspend fun ocrLayoutFor(context: android.content.Context, photo: Photo): OcrLayout? {
    // Read the hook into a local first: `override?.invoke(...) ?: default`
    // cannot tell "no override" apart from "override returned null".
    val hook = ocrLayoutOverride
    return if (hook != null) hook.invoke(context, photo)
    else OcrBoxStore.layoutFor(context, photo)
}
