package com.vayunmathur.photos.ui

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.database.getLongOrNull
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.photos.ImageLoader
import com.vayunmathur.photos.NavigationBar
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.VideoData
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
import kotlin.math.roundToInt
import kotlin.time.Instant

@Composable
fun GalleryPage(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel) {
    val photos by viewModel.data<Photo>().collectAsState()
    val context = LocalContext.current
    var columnCount by remember { mutableFloatStateOf(3f) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            syncPhotos(context, viewModel)
            val updatedPhotos = viewModel.getAll<Photo>()
            setExifData(updatedPhotos, viewModel, context)
        }
    }
    val photosGroupedByMonth by remember {
        derivedStateOf {
            photos.groupBy {
                val date = Instant.fromEpochMilliseconds(it.date).toLocalDateTime(TimeZone.currentSystemDefault())
                LocalDate(date.year, date.month, 1)
            }.toSortedMap(Comparator<LocalDate>(LocalDate::compareTo).reversed()).mapKeys {
                MonthNames.ENGLISH_ABBREVIATED.names[it.key.month.ordinal] + " " + it.key.year
            }.mapValues { pair -> pair.value.sortedByDescending { it.date } }
        }
    }
    Scaffold(bottomBar = { NavigationBar(Route.Gallery, backStack) }) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        // Initial pass lets the parent intercept events
                        // BEFORE the clickable items in the grid consume them
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)

                            if (event.changes.size > 1) {
                                val zoom = event.calculateZoom()
                                if (zoom != 1f) {
                                    // Update your state
                                    columnCount = (columnCount / zoom).coerceIn(2f, 8f)

                                    // Consume the changes so the grid doesn't scroll
                                    // while you are zooming
                                    event.changes.forEach { it.consume() }
                                }
                            }

                            // Break the loop if all pointers are up
                            if (event.changes.all { it.changedToUp() }) break
                        }
                    }
                }
        ) {
            LazyVerticalGrid(
                GridCells.Fixed(columnCount.roundToInt().coerceIn(2, 8)),
                Modifier
                    .padding(paddingValues),
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
}



suspend fun syncPhotos(context: Context, viewModel: DatabaseViewModel) {
    val existingPhotos = viewModel.getAll<Photo>().associateBy { it.id }
    val newOrUpdatedPhotos = mutableListOf<Photo>()
    val mediaStoreIds = mutableSetOf<Long>()

    fun processCursor(cursor: android.database.Cursor, isVideo: Boolean) {
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        val dateTakenColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
        val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
        val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
        val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
        val durationColumn = if (isVideo) cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION) else -1

        val baseUri = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            mediaStoreIds.add(id)
            val name = cursor.getString(nameColumn)
            val dateTaken = cursor.getLongOrNull(dateTakenColumn)
            val date = if (dateTaken != null && dateTaken > 0) dateTaken else (cursor.getLong(dateAddedColumn) * 1000)
            val width = cursor.getInt(widthColumn)
            val height = cursor.getInt(heightColumn)
            val contentUri = ContentUris.withAppendedId(baseUri, id).toString()
            val videoData = if (isVideo) VideoData(cursor.getLong(durationColumn)) else null

            val existing = existingPhotos[id]
            if (existing == null || existing.date != date || existing.uri != contentUri || existing.videoData != videoData || existing.width != width || existing.height != height) {
                newOrUpdatedPhotos += Photo(id, name, contentUri, date, width, height, existing?.exifSet ?: false, existing?.lat, existing?.long, videoData)
            }
        }
    }

    // Images
    context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT),
        null, null, null
    )?.use { processCursor(it, false) }

    // Videos
    context.contentResolver.query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DATE_TAKEN, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT, MediaStore.Video.Media.DURATION),
        null, null, null
    )?.use { processCursor(it, true) }

    if (newOrUpdatedPhotos.isNotEmpty()) {
        viewModel.upsertAll(newOrUpdatedPhotos)
    }

    val toDelete = existingPhotos.keys - mediaStoreIds
    if (toDelete.isNotEmpty()) {
        toDelete.chunked(900).forEach { chunk ->
            viewModel.deleteIf<Photo>("id IN (${chunk.joinToString(",")})")
        }
    }
}

suspend fun CoroutineScope.setExifData(photos: List<Photo>, viewModel: DatabaseViewModel, context: Context) {
    val ps = photos.filter { !it.exifSet }.sortedByDescending { it.date }
    ps.chunked(50).forEachIndexed { index, photosChunk ->
        val newPhotos = photosChunk.map { photo ->
            async {
                try {
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
                } catch (e: Exception) {
                    photo.copy(exifSet = true) // Mark as set even on error to avoid retry every time
                }
            }
        }.awaitAll()
        viewModel.upsertAll(newPhotos)
        println("${index * 50} / ${ps.size}")
    }
}
