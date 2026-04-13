package com.vayunmathur.photos.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
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
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.photos.util.ImageLoader
import com.vayunmathur.photos.NavigationBar
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.util.SyncWorker
import com.vayunmathur.photos.data.Photo
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
