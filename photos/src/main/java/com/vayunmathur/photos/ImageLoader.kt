package com.vayunmathur.photos

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.navigation3.runtime.NavBackStack
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.ImageRequest
import com.vayunmathur.library.ui.invisibleClickable
import com.vayunmathur.photos.data.Photo

object ImageLoader {
    private lateinit var imageLoader: ImageLoader;

    fun init(context: Context) {
        imageLoader = ImageLoader.Builder(context)
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
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(photo.uri.toUri())
                .diskCacheKey(photo.id.toString())
                .memoryCacheKey(photo.id.toString())
                .crossfade(true)
                .size(128) // Coil will handle the downsampling for you
                .build(),
            imageLoader = imageLoader,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .invisibleClickable(onClick)
                .background(Color.LightGray) // Placeholder while loading
        )
    }
}