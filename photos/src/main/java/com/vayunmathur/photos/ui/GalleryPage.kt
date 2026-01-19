package com.vayunmathur.photos.ui

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import androidx.navigation3.runtime.NavBackStack
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.ImageRequest
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.data.Photo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

@Composable
fun GalleryPage(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel) {
    val photos by viewModel.data<Photo>().collectAsState()
    val context = LocalContext.current
    val imageLoader = remember { ImageLoader.Builder(context)
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
        .build() }
    LaunchedEffect(Unit) {
        delay(1000)
        withContext(Dispatchers.IO) {
            if (photos.isEmpty()) {
                addAllPhotos(context, viewModel)
            }
            println(photos.count { !it.exifSet })
            setExifData(photos, viewModel, context)
        }
    }
    val photosGroupedByMonth by remember {
        derivedStateOf {
            photos.groupBy {
                val date = Instant.fromEpochMilliseconds(it.date).toLocalDateTime(TimeZone.currentSystemDefault())
                LocalDate(date.year, date.month, 1)
            }.toSortedMap(Comparator<LocalDate>(LocalDate::compareTo).reversed()).mapKeys {
                MonthNames.ENGLISH_ABBREVIATED.names[it.key.month.ordinal] + " " + it.key.year
            }
        }
    }
    Scaffold() { paddingValues ->
        LazyVerticalGrid(
            GridCells.Adaptive(40.dp),
            Modifier.padding(paddingValues),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            photosGroupedByMonth.forEach { (month, photos) ->
                item(span = {
                    GridItemSpan(maxLineSpan)
                }) {
                    Text(
                        month,
                        Modifier.padding(top = 16.dp, bottom = 8.dp),
                        style = MaterialTheme.typography.titleLarge
                    )
                }
                items(photos, { it.id }, contentType = { "photo_thumbnail" }) {
                    PhotoItem(it, imageLoader, backStack)
                }
            }
        }
    }
}

@Composable
fun PhotoItem(photo: Photo, imageLoader: ImageLoader, backStack: NavBackStack<Route>) {
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
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                backStack.add(Route.Photo(photo.id))
            }
            .background(Color.LightGray) // Placeholder while loading
    )
}

fun addAllPhotos(context: Context, viewModel: DatabaseViewModel) {
    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.INFERRED_DATE,
        MediaStore.Images.Media.WIDTH,
        MediaStore.Images.Media.HEIGHT
    )
    val query = context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        projection,
        null, null, null
    )

    val photos = mutableListOf<Photo>()

    query?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
        val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.INFERRED_DATE)
        val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
        val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)

        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            val name = cursor.getString(nameColumn)
            val date = cursor.getLong(dateColumn)
            val width = cursor.getInt(widthColumn)
            val height = cursor.getInt(heightColumn)

            photos += Photo(id, name, contentUri.toString(), date, width, height, false, null, null)

        }
    }
    viewModel.upsertAll(photos.sortedByDescending { it.date })
}

suspend fun CoroutineScope.setExifData(photos: List<Photo>, viewModel: DatabaseViewModel, context: Context) {
    val ps = photos.filter { !it.exifSet }.sortedByDescending { it.date }
    ps.chunked(50).forEachIndexed { it, photos ->
        val newPhotos = photos.map { photo ->
            async {
                val (lat, long) = context.contentResolver.openInputStream(
                    MediaStore.setRequireOriginal(
                        photo.uri.toUri()
                    )
                )?.use { inputStream ->
                    val exif = ExifInterface(inputStream)
                    val latLong = exif.latLong
                    val lat = latLong?.getOrNull(0)
                    val long = latLong?.getOrNull(1)
                    listOf(lat, long)
                } ?: listOf(null, null)
                photo.copy(exifSet = true, lat = lat, long = long)
            }
        }.awaitAll()
        viewModel.upsertAll(newPhotos)
        println("${it * 50} / ${ps.size}")
    }

}