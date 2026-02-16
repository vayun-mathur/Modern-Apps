package com.vayunmathur.photos

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import coil.size.Precision
import com.vayunmathur.library.ui.invisibleClickable
import com.vayunmathur.photos.data.Photo

object ImageLoader {
    private lateinit var imageLoader: ImageLoader

    fun init(context: Context) {
        imageLoader = ImageLoader.Builder(context)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(0.25) // Use 25% of available RAM
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.05) // Use 5% of disk space (or a fixed size like 512MB)
                    .build()
            }
            .respectCacheHeaders(false) // Important for local files/mediastore
            .build()
    }

    @Composable
    fun PhotoItem(photo: Photo, modifier: Modifier, onClick: () -> Unit) {
        val context = LocalContext.current
        val isVideo = photo.videoData != null

        Box(
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .invisibleClickable(onClick)
                .background(Color.DarkGray),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(photo.uri.toUri())
                    .videoFrameMillis(1000) // Grabs frame at 1s mark
                    .diskCacheKey("thumb_${photo.id}")
                    .memoryCacheKey("thumb_${photo.id}")
                    .crossfade(true)
                    .size(256) // Increased slightly for better quality on high-DPI screens
                    .build(),
                imageLoader = imageLoader,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            if (isVideo) {
                // Semi-transparent circle background for the icon
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(
                            color = Color.Black.copy(alpha = 0.4f),
                            shape = androidx.compose.foundation.shape.CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painterResource(R.drawable.outline_play_circle_24),
                        contentDescription = "Video",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}