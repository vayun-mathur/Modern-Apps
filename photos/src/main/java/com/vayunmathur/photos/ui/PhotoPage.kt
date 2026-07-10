package com.vayunmathur.photos.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.photos.R
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.util.GalleryViewModel
import com.vayunmathur.photos.util.PhotoMapViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.absoluteValue
import kotlin.time.Instant
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

// Helper class to store zoom information
data class ZoomState(val scale: Float = 1f, val offset: Offset = Offset.Zero)

@Composable
fun PhotoPage(galleryViewModel: GalleryViewModel, photoMapViewModel: PhotoMapViewModel, id: Long, overridePhotosList: List<Photo>?) {
    val photosAll by galleryViewModel.photos.collectAsState()
    val photos = overridePhotosList ?: photosAll.filter { !it.isTrashed }
    val context = LocalContext.current
    val photosSorted = remember(photos) { photos.sortedByDescending { it.date } }
    val matchedCounts by galleryViewModel.faceCountByPhoto.collectAsState()

    val initialIndex =
            remember(photosSorted, id) {
                val index = photosSorted.indexOfFirst { it.id == id }
                if (index == -1) 0 else index
            }

    var isMetadataVisible by remember { mutableStateOf(true) }

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
                rememberPagerState(initialPage = initialIndex, pageCount = { photosSorted.size })

        Scaffold(containerColor = Color.Black) { paddingValues ->
            HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    beyondViewportPageCount = 1,
                    userScrollEnabled = true
            ) { pageIndex ->
                val photo = photosSorted[pageIndex]
                val zoomState = zoomStates[photo.id] ?: ZoomState()

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
                        onZoomUpdate = { newState -> zoomStates[photo.id] = newState },
                        onToggleMetadata = { isMetadataVisible = !isMetadataVisible },
                        refreshKey = refreshKey,
                        onEditPhoto = {
                            val intent =
                                    Intent(context, EditActivity::class.java).apply {
                                        putExtra("photo_id", photo.id)
                                    }
                            context.startActivity(intent)
                        }
                )
            }
        }
    }
}

@Composable
fun PhotoDetailView(
        photo: Photo,
        context: Context,
        photoMapViewModel: PhotoMapViewModel,
        pagerState: PagerState,
        pageIndex: Int,
        isSettled: Boolean,
        isMetadataVisible: Boolean,
        currentZoom: ZoomState,
        peopleCount: Int = 0,
        onZoomUpdate: (ZoomState) -> Unit,
        onToggleMetadata: () -> Unit,
        refreshKey: Int = 0,
        onEditPhoto: () -> Unit
) {
    val countryNames by photoMapViewModel.countryNames.collectAsState()
    val countryName = countryNames[photo.id]
    var size by remember { mutableStateOf(IntSize.Zero) }

    val updatedZoomState by rememberUpdatedState(currentZoom)
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
    LaunchedEffect(Unit) {
        snapshotFlow { pageOffset }.filter { it >= 0.99f }.distinctUntilChanged().collect {
            if (updatedZoomState.scale > 1f) {
                updatedOnZoomUpdate(ZoomState())
            }
        }
    }

    LaunchedEffect(photo.id) {
        if (photo.lat != null && photo.long != null) {
            photoMapViewModel.requestCountryName(photo.id, photo.lat, photo.long)
        }
    }

    // Panoramas/spheres get an interactive 360 renderer that owns its own touch
    // handling (drag/pinch/tap), so the flat zoom/pan gestures are gated off.
    val isPanorama = photo.videoData == null && photo.panoData != null

    Box(
            modifier =
                    Modifier.fillMaxSize()
                            .then(
                                if (isPanorama) Modifier
                                else Modifier
                            .pointerInput(Unit) {
                                detectTapGestures(
                                        onTap = { updatedOnToggleMetadata() },
                                        onDoubleTap = {
                                            val newScale =
                                                    if (updatedZoomState.scale > 1f) 1f else 2.5f
                                            updatedOnZoomUpdate(
                                                    ZoomState(
                                                            scale = newScale,
                                                            offset = Offset.Zero
                                                    )
                                            )
                                        }
                                )
                            }
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    do {
                                        val event = awaitPointerEvent()
                                        val zoomChange = event.calculateZoom()
                                        val panChange = event.calculatePan()

                                        val isPinching = zoomChange != 1f
                                        val isZoomed = updatedZoomState.scale > 1.01f

                                        if (isZoomed || isPinching) {
                                            val newScale =
                                                    (updatedZoomState.scale * zoomChange).coerceIn(
                                                            1f,
                                                            5f
                                                    )

                                            if (newScale > 1f) {
                                                val maxX = (size.width * (newScale - 1) / 2)
                                                val maxY = (size.height * (newScale - 1) / 2)

                                                val newOffset = updatedZoomState.offset + panChange

                                                val isAtLeftEdge =
                                                        newOffset.x >= maxX && panChange.x > 0
                                                val isAtRightEdge =
                                                        newOffset.x <= -maxX && panChange.x < 0

                                                val boundedOffset =
                                                        Offset(
                                                                newOffset.x.coerceIn(-maxX, maxX),
                                                                newOffset.y.coerceIn(-maxY, maxY)
                                                        )

                                                updatedOnZoomUpdate(
                                                        ZoomState(
                                                                scale = newScale,
                                                                offset = boundedOffset
                                                        )
                                                )

                                                if (isPinching || (!isAtLeftEdge && !isAtRightEdge)
                                                ) {
                                                    event.changes.forEach { it.consume() }
                                                }
                                            } else {
                                                updatedOnZoomUpdate(
                                                        ZoomState(scale = 1f, offset = Offset.Zero)
                                                )
                                            }
                                        }
                                    } while (event.changes.any { it.pressed })
                                }
                            }
                            )
    ) {
        if (isPanorama) {
            PanoramaSphereView(
                    photo = photo,
                    modifier = Modifier.fillMaxSize(),
                    onTap = { updatedOnToggleMetadata() }
            )
        } else if (photo.videoData == null) {
            AsyncImage(
                    model =
                            ImageRequest.Builder(context)
                                    .data(photo.uri.toUri())
                                    .diskCacheKey("thumb_${photo.id}_${photo.dateModified}_$refreshKey")
                                    .memoryCacheKey("thumb_${photo.id}_${photo.dateModified}_$refreshKey")
                                    .build(),
                    contentDescription = null,
                    modifier =
                            Modifier.fillMaxSize()
                                    .onGloballyPositioned { layoutCoordinates ->
                                        size = layoutCoordinates.size
                                    }
                                    .graphicsLayer {
                                        scaleX = currentZoom.scale
                                        scaleY = currentZoom.scale
                                        translationX = currentZoom.offset.x
                                        translationY = currentZoom.offset.y
                                    },
                    contentScale = ContentScale.Fit
            )
        } else {
            VideoPlayer(
                    modifier =
                            Modifier.fillMaxSize()
                                    .onGloballyPositioned { size = it.size }
                                    .graphicsLayer {
                                        scaleX = currentZoom.scale
                                        scaleY = currentZoom.scale
                                        translationX = currentZoom.offset.x
                                        translationY = currentZoom.offset.y
                                    },
                    uri = photo.uri.toUri(),
                    isMetadataVisible = isMetadataVisible,
                    isSettledPage = isSettled
            )
        }

        AnimatedVisibility(
                visible = isMetadataVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomStart)
        ) {
            Column(
                    modifier =
                            Modifier.fillMaxWidth()
                                    .graphicsLayer {
                                        // Keep the metadata fade-out tied to the swiping distance
                                        alpha = 1f - pageOffset.coerceIn(0f, 1f)
                                    }
                                    .background(Color.Black.copy(alpha = 0.6f))
                                    .padding(16.dp)
            ) {
                Text(
                        text = photo.name,
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge
                )

                val dateFormatted =
                        remember(photo.date) {
                            Instant.fromEpochMilliseconds(photo.date)
                                    .toLocalDateTime(TimeZone.currentSystemDefault())
                                    .let {
                                        // Simple formatting for now, better to use localized date
                                        // formatter
                                        "${it.day} ${it.month.name.lowercase().replaceFirstChar { c -> c.uppercase() }} ${it.year}"
                                    }
                        }

                Text(
                        text = stringResource(R.string.taken_on, dateFormatted),
                        color = Color.LightGray
                )
                if (photo.exifSet) {
                    Text(
                            text =
                                    stringResource(
                                            R.string.location,
                                            countryName ?: stringResource(R.string.detecting)
                                    ),
                            color = Color.LightGray
                    )
                }
                Text(
                        text = stringResource(R.string.resolution, photo.width, photo.height),
                        color = Color.LightGray
                )
                if (photo.panoData != null) {
                    Text(text = "360°", color = Color.LightGray)
                }
                if (peopleCount > 0) {
                    Text(
                            text = pluralStringResource(R.plurals.people_in_photo, peopleCount, peopleCount),
                            color = Color.LightGray
                    )
                }

                Row(
                        modifier = Modifier.align(Alignment.End),
                        verticalAlignment = Alignment.CenterVertically
                ) {
                    if (photo.videoData == null) {
                        IconButton(onClick = onEditPhoto) { IconEdit(tint = Color.White) }
                    }
                    IconButton(
                            onClick = {
                                val intent =
                                        Intent(Intent.ACTION_SEND).apply {
                                            type =
                                                    if (photo.videoData != null) "video/*"
                                                    else "image/*"
                                            putExtra(Intent.EXTRA_STREAM, photo.uri.toUri())
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                context.startActivity(Intent.createChooser(intent, "Share"))
                            }
                    ) { IconShare(tint = Color.White) }
                }
            }
        }
    }
}

@Composable
fun VideoPlayer(
        modifier: Modifier,
        uri: Uri,
        isMetadataVisible: Boolean,
        isSettledPage: Boolean,
) {
    val context = LocalContext.current

    // Default to a sane ratio until the player loads the real one
    var videoAspectRatio by remember { mutableFloatStateOf(16f / 9f) }

    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = isSettledPage
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }

    var isPlaying by remember { mutableStateOf(isSettledPage) }
    LaunchedEffect(isSettledPage) {
        if (isSettledPage) {
            exoPlayer.play()
        } else {
            exoPlayer.pause()
        }
    }

    DisposableEffect(exoPlayer) {
        val listener =
                object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) {
                        isPlaying = playing
                    }

                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        // Calculate ratio from the actual video stream
                        if (videoSize.width > 0 && videoSize.height > 0) {
                            videoAspectRatio =
                                    (videoSize.width * videoSize.pixelWidthHeightRatio) /
                                            videoSize.height
                        }
                    }
                }

        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // The container ensures the surface stays centered and "fitted"
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            PlayerSurface(
                    player = exoPlayer,
                    modifier =
                            Modifier.fillMaxWidth() // Try to fill width
                                    .aspectRatio(videoAspectRatio),
                    surfaceType = SURFACE_TYPE_TEXTURE_VIEW
            )
        }

        // Play/Pause Overlay
        AnimatedVisibility(visible = isMetadataVisible, enter = fadeIn(), exit = fadeOut()) {
            IconButton(onClick = { if (isPlaying) exoPlayer.pause() else exoPlayer.play() }) {
                Icon(
                        painter =
                                if (isPlaying) {
                                    painterResource(R.drawable.outline_pause_24)
                                } else {
                                    painterResource(R.drawable.outline_play_circle_24)
                                },
                        contentDescription = "Toggle Play",
                        modifier = Modifier.size(64.dp),
                        tint = Color.White.copy(alpha = 0.8f)
                )
            }
        }
    }
}
