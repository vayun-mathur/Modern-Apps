package com.vayunmathur.photos.ui

import android.content.Context
import android.location.Geocoder
import android.net.Uri
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.photos.R
import com.vayunmathur.photos.data.Photo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withContext
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.absoluteValue

// Helper class to store zoom information
data class ZoomState(val scale: Float = 1f, val offset: Offset = Offset.Zero)

@Composable
fun PhotoPage(viewModel: DatabaseViewModel, id: Long, overridePhotosList: List<Photo>?) {
    val photosAll by viewModel.data<Photo>().collectAsState(initial = emptyList())
    val photos = overridePhotosList ?: photosAll
    val context = LocalContext.current
    val photosSorted = remember(photos) { photos.sortedByDescending { it.date } }

    val initialIndex = remember(photosSorted, id) {
        val index = photosSorted.indexOfFirst { it.id == id }
        if (index == -1) 0 else index
    }

    var isMetadataVisible by remember { mutableStateOf(true) }

    // Persist zoom states in a map that survives as long as this screen is active
    val zoomStates = remember { mutableStateMapOf<Long, ZoomState>() }

    if (photosSorted.isNotEmpty()) {
        val pagerState = rememberPagerState(
            initialPage = initialIndex,
            pageCount = { photosSorted.size }
        )

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
                    pagerState = pagerState,
                    pageIndex = pageIndex,
                    isMetadataVisible = isMetadataVisible,
                    currentZoom = zoomState,
                    onZoomUpdate = { newState -> zoomStates[photo.id] = newState },
                    onToggleMetadata = { isMetadataVisible = !isMetadataVisible }
                )
            }
        }
    }
}

@Composable
fun PhotoDetailView(
    photo: Photo,
    context: Context,
    pagerState: PagerState,
    pageIndex: Int,
    isMetadataVisible: Boolean,
    currentZoom: ZoomState,
    onZoomUpdate: (ZoomState) -> Unit,
    onToggleMetadata: () -> Unit
) {
    var countryName by remember(photo.id) { mutableStateOf<String?>(null) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    val updatedZoomState by rememberUpdatedState(currentZoom)
    val updatedOnZoomUpdate by rememberUpdatedState(onZoomUpdate)
    val updatedOnToggleMetadata by rememberUpdatedState(onToggleMetadata)

    // Derived state for how far this specific page is from the center
    val pageOffset by remember {
        derivedStateOf {
            ((pagerState.currentPage - pageIndex) + pagerState.currentPageOffsetFraction).absoluteValue
        }
    }

    // Reset zoom only when the page is fully scrolled out of view (offset >= 1.0)
    // This allows the "fadeOut" to happen while the image is still zoomed.
    LaunchedEffect(Unit) {
        snapshotFlow { pageOffset }
            .filter { it >= 0.99f }
            .distinctUntilChanged()
            .collect {
                if (updatedZoomState.scale > 1f) {
                    updatedOnZoomUpdate(ZoomState())
                }
            }
    }

    LaunchedEffect(photo.id) {
        if (photo.lat != null && photo.long != null) {
            withContext(Dispatchers.IO) {
                try {
                    val geocoder = Geocoder(context)
                    countryName = geocoder.getFromLocation(photo.lat, photo.long, 1)?.firstOrNull()?.countryName
                } catch (e: Exception) { countryName = "Unknown" }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { updatedOnToggleMetadata() },
                    onDoubleTap = {
                        val newScale = if (updatedZoomState.scale > 1f) 1f else 2.5f
                        updatedOnZoomUpdate(ZoomState(scale = newScale, offset = Offset.Zero))
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
                            val newScale = (updatedZoomState.scale * zoomChange).coerceIn(1f, 5f)

                            if (newScale > 1f) {
                                val maxX = (size.width * (newScale - 1) / 2)
                                val maxY = (size.height * (newScale - 1) / 2)

                                val newOffset = updatedZoomState.offset + panChange

                                val isAtLeftEdge = newOffset.x >= maxX && panChange.x > 0
                                val isAtRightEdge = newOffset.x <= -maxX && panChange.x < 0

                                val boundedOffset = Offset(
                                    newOffset.x.coerceIn(-maxX, maxX),
                                    newOffset.y.coerceIn(-maxY, maxY)
                                )

                                updatedOnZoomUpdate(ZoomState(scale = newScale, offset = boundedOffset))

                                if (isPinching || (!isAtLeftEdge && !isAtRightEdge)) {
                                    event.changes.forEach { it.consume() }
                                }
                            } else {
                                updatedOnZoomUpdate(ZoomState(scale = 1f, offset = Offset.Zero))
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
    ) {
        if(photo.videoData == null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(photo.uri.toUri())
                    .build(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
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
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { size = it.size }
                    .graphicsLayer {
                        scaleX = currentZoom.scale
                        scaleY = currentZoom.scale
                        translationX = currentZoom.offset.x
                        translationY = currentZoom.offset.y
                    },
                uri = photo.uri.toUri(),
                isMetadataVisible = isMetadataVisible,
            )
        }

        AnimatedVisibility(
            visible = isMetadataVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomStart)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        // Keep the metadata fade-out tied to the swiping distance
                        alpha = 1f - pageOffset.coerceIn(0f, 1f)
                    }
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(16.dp)
            ) {
                Text(text = photo.name, color = Color.White, style = MaterialTheme.typography.titleLarge)

                val dateFormatted = remember(photo.date) {
                    Instant.fromEpochMilliseconds(photo.date)
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                        .let { "${it.day} ${it.month.name.lowercase().replaceFirstChar { c -> c.uppercase() }} ${it.year}" }
                }

                Text(text = "Taken on: $dateFormatted", color = Color.LightGray)
                if (photo.exifSet) {
                    Text(text = "Location: ${countryName ?: "Detecting..."}", color = Color.LightGray)
                }
                Text(text = "Resolution: ${photo.width} x ${photo.height}", color = Color.LightGray)
            }
        }
    }
}

@Composable
fun VideoPlayer(
    modifier: Modifier,
    uri: Uri,
    isMetadataVisible: Boolean
) {
    val context = LocalContext.current

    // Default to a sane ratio until the player loads the real one
    var videoAspectRatio by remember { mutableStateOf(16f / 9f) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }

    var isPlaying by remember { mutableStateOf(true) }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                // Calculate ratio from the actual video stream
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoAspectRatio = (videoSize.width * videoSize.pixelWidthHeightRatio) / videoSize.height
                }
            }
        }

        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        // The container ensures the surface stays centered and "fitted"
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            PlayerSurface(
                player = exoPlayer,
                modifier = Modifier
                    .fillMaxWidth() // Try to fill width
                    .aspectRatio(videoAspectRatio),
                surfaceType = SURFACE_TYPE_TEXTURE_VIEW
            )
        }

        // Play/Pause Overlay
        AnimatedVisibility(
            visible = isMetadataVisible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            IconButton(
                onClick = { if (isPlaying) exoPlayer.pause() else exoPlayer.play() }
            ) {
                Icon(
                    painter = if (isPlaying) {
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