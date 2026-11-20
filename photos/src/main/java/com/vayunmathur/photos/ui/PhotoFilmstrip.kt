package com.vayunmathur.photos.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.invisibleClickable
import com.vayunmathur.photos.data.Photo
import kotlinx.coroutines.launch

/**
 * Expanded filmstrip beside the viewer: the surrounding photos as small tiles
 * that jump the pager. Slots only; the viewer pager owns the state.
 */
@Composable
internal fun FilmstripPane(photos: List<Photo>, pagerState: PagerState) {
    val scope = rememberCoroutineScope()
    androidx.compose.foundation.lazy.LazyColumn(
        Modifier.fillMaxSize().background(Color.Black).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(photos.size, key = { photos[it].id }) { index ->
            val photo = photos[index]
            val selected = pagerState.currentPage == index
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f)
                    .border(
                        if (selected) 2.dp else 0.dp,
                        if (selected) Color.White else Color.Transparent,
                    )
                    .invisibleClickable { scope.launch { pagerState.animateScrollToPage(index) } },
            ) {
                com.vayunmathur.photos.platform.ImageLoader.PhotoItem(photo, Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * Minimal full-screen viewer for a photo that just arrived via ACTION_VIEW but
 * isn't in the gallery DB yet. Renders the URI directly so the app opens on the
 * image with no delay; [PhotoPage] swaps to the full swipeable pager as soon as
 * the background index writes the row.
 */
@Composable
internal fun PendingPhotoView(uri: String, context: Context) {
    com.vayunmathur.library.ui.AppScaffold(topBar = {}, containerColor = Color.Black) { paddingValues ->
        AsyncImage(
            model = ImageRequest.Builder(context).data(uri.toUri()).build(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentScale = ContentScale.Fit,
        )
    }
}
