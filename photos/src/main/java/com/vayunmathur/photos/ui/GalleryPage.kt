package com.vayunmathur.photos.ui

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.photos.ImageLoader
import com.vayunmathur.photos.NavigationBar
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

    LaunchedEffect(Unit) {
        delay(200)
        withContext(Dispatchers.IO) {
            setAllPhotos(context, viewModel)
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
            }.mapValues { it.value.sortedByDescending { it.date } }
        }
    }
    Scaffold(bottomBar = { NavigationBar(Route.Gallery, backStack) }) { paddingValues ->
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
                    ImageLoader.PhotoItem(it, Modifier.fillMaxWidth().aspectRatio(1f)) {
                        backStack.add(Route.PhotoPage(it.id, null))
                    }
                }
            }
        }
    }
}



fun setAllPhotos(context: Context, viewModel: DatabaseViewModel) {
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
    viewModel.replaceAll(photos.sortedByDescending { it.date })
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