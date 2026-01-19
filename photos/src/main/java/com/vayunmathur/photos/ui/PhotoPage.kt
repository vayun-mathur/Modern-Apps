package com.vayunmathur.photos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

@Composable
fun PhotoPage(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel, id: Long) {
    val photo by viewModel.get<Photo>(id)
    val context = LocalContext.current

    Scaffold(
        containerColor = Color.Black // Better for viewing photos
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            // 1. Full Screen Image
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(photo.uri.toUri())
                    .crossfade(true)
                    .build(),
                contentDescription = photo.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit // Shows the whole photo without cropping
            )

            // 2. Metadata Overlay (Bottom)
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(16.dp)
            ) {
                Text(text = photo.name, color = Color.White, style = MaterialTheme.typography.titleLarge)

                val dateFormatted = remember(photo.date) {
                    Instant.fromEpochMilliseconds(photo.date)
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                        .let { "${it.day} ${it.month} ${it.year}" }
                }

                Text(text = "Taken on: $dateFormatted", color = Color.LightGray)

                // Location and Resolution (assuming they are properties of your Photo class)
                Text(text = "Location: ${photo.country ?: "Unknown"}", color = Color.LightGray)
                Text(text = "Resolution: ${photo.width} x ${photo.height}", color = Color.LightGray)
            }
        }
    }
}