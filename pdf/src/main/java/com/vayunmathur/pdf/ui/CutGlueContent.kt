package com.vayunmathur.pdf.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.pdf.R
import com.vayunmathur.pdf.platform.ComposePdfDocument
import com.vayunmathur.pdf.platform.SafePdfPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import com.vayunmathur.library.ui.Motion
import com.vayunmathur.library.ui.itemMotion
import com.vayunmathur.library.ui.rememberReorderableLazyGridState
import com.vayunmathur.library.ui.reorderGridDragHandle
import com.vayunmathur.library.ui.appBarScrollBehavior
import androidx.compose.ui.res.stringResource

/**
 * The page grid, with no handle on the native document: pages arrive as decoded
 * [SafePdfPage]s through [renderPage], and every mutation is reported back. That is what
 * lets a `@Preview` render it from hand-built pages — see `src/screenshotTest`, which is
 * where the store listing images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CutGlueContent(
    pageKeys: List<Long>,
    renderPage: suspend (index: Int) -> SafePdfPage?,
    onAppendImage: () -> Unit = {},
    onAppendPdf: () -> Unit = {},
    onSave: () -> Unit = {},
    onMove: (from: Int, to: Int) -> Unit = { _, _ -> },
    onDelete: (index: Int) -> Unit = {},
    onBack: () -> Unit = {},
    /**
     * Pages already decoded, keyed as in [pageKeys]. The app starts empty and fills the
     * cache through [renderPage]; a preview seeds it instead, because [renderPage] would
     * have to run as an effect and a still preview never gets that far.
     */
    initialPages: Map<Long, SafePdfPage> = emptyMap(),
) {
    var menuOpen by remember { mutableStateOf(false) }

    // Rendered pages cached by stable page key. Appends/reorders/removes then
    // reuse already-rendered pages instead of re-rendering every visible
    // thumbnail, which is what made the grid slow to load.
    val pageCache = remember { mutableStateMapOf<Long, SafePdfPage>().apply { putAll(initialPages) } }

    val gridState = rememberLazyGridState()
    val reorderState = rememberReorderableLazyGridState(gridState) { from, to ->
        if (from.index < pageKeys.size && to.index < pageKeys.size) onMove(from.index, to.index)
    }

    AppScaffold(
        title = stringResource(R.string.cut_glue),
        onNavigateBack = onBack,
        actions = {
            if (pageKeys.isNotEmpty()) {
                IconButton(onSave) { IconSave() }
            }
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { menuOpen = true }) { IconAdd() }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        leadingIcon = { com.vayunmathur.library.ui.IconImage() },
                        text = { Text(stringResource(R.string.append_image)) },
                        onClick = { menuOpen = false; onAppendImage() },
                    )
                    DropdownMenuItem(
                        leadingIcon = { com.vayunmathur.library.ui.IconShapeRectOutline() },
                        text = { Text(stringResource(R.string.append_pdf)) },
                        onClick = { menuOpen = false; onAppendPdf() },
                    )
                }
            }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (pageKeys.isEmpty()) {
                Text(stringResource(R.string.tap_to_append_a_pdf_or_image),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    state = gridState,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                ) {
                    items(pageKeys, key = { it }) { key ->
                        val index = pageKeys.indexOf(key)
                        val motion = itemMotion(Motion.reorder())
                        // The page being dragged follows the finger, so it cannot also be placed by
                        // the grid's own animation; the rest slide aside on the reorder spec.
                        val itemModifier = if (reorderState.draggingKey == key) {
                            Modifier.zIndex(1f).graphicsLayer {
                                val translation = reorderState.draggingItemTranslation
                                translationX = translation.x
                                translationY = translation.y
                            }
                        } else {
                            motion
                        }
                        ComposePageThumb(
                            renderPage = renderPage,
                            pageKey = key,
                            index = index,
                            cache = pageCache,
                            onDelete = {
                                if (index in pageKeys.indices) {
                                    pageCache.remove(key)
                                    onDelete(index)
                                }
                            },
                            modifier = itemModifier.reorderGridDragHandle(
                                reorderState = reorderState,
                                key = key,
                                itemCount = pageKeys.size,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposePageThumb(
    renderPage: suspend (index: Int) -> SafePdfPage?,
    pageKey: Long,
    index: Int,
    cache: androidx.compose.runtime.snapshots.SnapshotStateMap<Long, SafePdfPage>,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Render each page once and cache it by its stable key. Reorders and appends
    // then reuse the cached render instead of re-rendering every visible thumb.
    val page by produceState<SafePdfPage?>(cache[pageKey], pageKey) {
        cache[pageKey]?.let { value = it; return@produceState }
        if (index >= 0) {
            val rendered = renderPage(index)
            if (rendered != null) cache[pageKey] = rendered
            value = rendered
        }
    }
    val current = page
    val ratio = if (current != null && current.height > 0f) current.width / current.height else 0.75f
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .then(modifier),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
                .clip(RoundedCornerShape(6.dp))
                .background(if (current == null) MaterialTheme.colorScheme.surfaceVariant else Color.White),
        ) {
            if (current == null || current.width <= 0f) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                Canvas(Modifier.fillMaxSize()) {
                    // drawSafePage only drains its save/saveLayer levels on the normal path, so
                    // a throwing primitive would leave them open and corrupt the sibling
                    // thumbnails drawn after this one. Anchor on the entry count and restore to
                    // it either way, as SafePdfPageCanvas does for the reader.
                    val base = drawContext.canvas.nativeCanvas.saveCount
                    try {
                        drawSafePage(current)
                    } catch (expected: RuntimeException) {
                        Log.status("CutGlueScreen", "drawSafePage failed", expected)
                    } finally {
                        drawContext.canvas.nativeCanvas.restoreToCount(base)
                    }
                }
            }
        }
        IconButton(
            onClick = onDelete,
            modifier = Modifier.align(Alignment.TopEnd)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
        ) { IconDelete(tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

