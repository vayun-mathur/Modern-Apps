package com.vayunmathur.photos.ui

import android.content.Context
import android.location.Geocoder
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.navigation3.runtime.NavBackStack
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.data.Photo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.absoluteValue
import kotlin.time.Instant

@Composable
fun PhotoPage(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel, id: Long) {
    val photos by viewModel.data<Photo>().collectAsState(initial = emptyList())
    val context = LocalContext.current
    val photosSorted = remember(photos) { photos.sortedByDescending { it.date } }

    val initialIndex = remember(photosSorted, id) {
        val index = photosSorted.indexOfFirst { it.id == id }
        if (index == -1) 0 else index
    }

    // State to toggle metadata visibility globally (or per page)
    var isMetadataVisible by remember { mutableStateOf(true) }

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
                // Disable paging if the user is zoomed in (handled in the child)
                userScrollEnabled = true
            ) { pageIndex ->
                val photo = photosSorted[pageIndex]

                PhotoDetailView(
                    photo = photo,
                    context = context,
                    pagerState = pagerState,
                    pageIndex = pageIndex,
                    isMetadataVisible = isMetadataVisible,
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
    onToggleMetadata: () -> Unit
) {
    var countryName by remember(photo.id) { mutableStateOf<String?>(null) }

    // Zoom/Pan States
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // Reset zoom when the page is swiped away
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != pageIndex) {
            scale = 1f
            offset = Offset.Zero
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
                // Handle Tap to Hide/Show
                detectTapGestures(
                    onTap = { onToggleMetadata() },
                    onDoubleTap = {
                        // Simple double tap to reset or zoom in
                        scale = if (scale > 1f) 1f else 2.5f
                        offset = Offset.Zero
                    }
                )
            }
            .pointerInput(Unit) {
                // Handle Pinch and Pan
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    // Only allow panning if zoomed in
                    if (scale > 1f) {
                        offset += pan
                    } else {
                        offset = Offset.Zero
                    }
                }
            }
    ) {
        // 1. The Zoomable Image
        AsyncImage(
            model = ImageRequest.Builder(context).data(photo.uri.toUri()).build(),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            contentScale = ContentScale.Fit
        )

        // 2. The Fading & Toggling Metadata Overlay
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
                        // Fade out based on swiping distance
                        val pageOffset = ((pagerState.currentPage - pageIndex) + pagerState.currentPageOffsetFraction).absoluteValue
                        alpha = 1f - pageOffset.coerceIn(0f, 1f)
                    }
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(16.dp)
            ) {
                Text(text = photo.name, color = Color.White, style = MaterialTheme.typography.titleLarge)

                val dateFormatted = remember(photo.date) {
                    Instant.fromEpochMilliseconds(photo.date)
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                        .let { "${it.dayOfMonth} ${it.month.name.lowercase().capitalize()} ${it.year}" }
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