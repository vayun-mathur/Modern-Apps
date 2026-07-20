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
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.pdf.R
import com.vayunmathur.pdf.util.ComposePdfDocument
import com.vayunmathur.pdf.util.SafePdfPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

/**
 * "Cut and glue": compose a new PDF by appending whole PDFs or images, then
 * drag pages into the desired order. Starts empty.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CutGlueScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    androidx.activity.compose.BackHandler { onBack() }
    val doc = remember { ComposePdfDocument.create() }
    DisposableEffect(doc) { onDispose { doc.close() } }

    // Stable per-page keys so drag-reorder animates; order mirrors native pages.
    val pageKeys = remember { mutableStateListOf<Long>() }
    var nextKey by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }

    // Rendered pages cached by stable page key. Appends/reorders/removes then
    // reuse already-rendered pages instead of re-rendering every visible
    // thumbnail, which is what made the grid slow to load.
    val pageCache = remember { mutableStateMapOf<Long, SafePdfPage>() }

    val pdfPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            } ?: return@launch
            val added = doc.appendPdf(bytes)
            repeat(added) { pageKeys.add(nextKey++.toLong()) }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) scope.launch {
            val jpeg = withContext(Dispatchers.IO) { readAsJpegPage(context, uri) } ?: return@launch
            val ok = doc.appendImage(jpeg.bytes, jpeg.width, jpeg.height)
            if (ok > 0) pageKeys.add(nextKey++.toLong())
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { outUri ->
        if (outUri != null) scope.launch {
            val bytes = doc.save()
            if (bytes != null) withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(outUri)?.use { it.write(bytes) } }
            }
        }
    }

    val gridState = rememberLazyGridState()
    val reorderState = rememberReorderableLazyGridState(gridState) { from, to ->
        if (from.index < pageKeys.size && to.index < pageKeys.size) {
            val k = pageKeys.removeAt(from.index)
            pageKeys.add(to.index, k)
            scope.launch { doc.movePage(from.index, to.index) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cut & Glue") },
                navigationIcon = { IconNavigation { onBack() } },
                actions = {
                    if (pageKeys.isNotEmpty()) {
                        IconButton({ saveLauncher.launch("composed.pdf") }) { IconSave() }
                    }
                },
            )
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { menuOpen = true }) { IconAdd() }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        leadingIcon = { com.vayunmathur.library.ui.IconImage() },
                        text = { Text("Append image") },
                        onClick = {
                            menuOpen = false
                            imagePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                    )
                    DropdownMenuItem(
                        leadingIcon = { com.vayunmathur.library.ui.IconShapeRectOutline() },
                        text = { Text("Append PDF") },
                        onClick = { menuOpen = false; pdfPicker.launch(arrayOf("application/pdf")) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (pageKeys.isEmpty()) {
                Text(
                    "Tap + to append a PDF or image",
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
                        ReorderableItem(reorderState, key = key) { _ ->
                            ComposePageThumb(
                                doc = doc,
                                pageKey = key,
                                index = index,
                                cache = pageCache,
                                onDelete = {
                                    if (index in pageKeys.indices) {
                                        pageKeys.removeAt(index)
                                        pageCache.remove(key)
                                        scope.launch { doc.removePage(index) }
                                    }
                                },
                                dragHandle = Modifier.longPressDraggableHandle(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposePageThumb(
    doc: ComposePdfDocument,
    pageKey: Long,
    index: Int,
    cache: androidx.compose.runtime.snapshots.SnapshotStateMap<Long, SafePdfPage>,
    onDelete: () -> Unit,
    dragHandle: Modifier,
) {
    // Render each page once and cache it by its stable key. Reorders and appends
    // then reuse the cached render instead of re-rendering every visible thumb.
    val page by produceState<SafePdfPage?>(cache[pageKey], pageKey) {
        cache[pageKey]?.let { value = it; return@produceState }
        if (index >= 0) {
            val rendered = doc.renderPage(index)
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
            .then(dragHandle),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White),
        ) {
            if (current == null || current.width <= 0f) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                Canvas(Modifier.fillMaxSize()) { drawSafePage(current) }
            }
        }
        IconButton(
            onClick = onDelete,
            modifier = Modifier.align(Alignment.TopEnd),
        ) { IconDelete() }
    }
}

private class JpegPage(val bytes: ByteArray, val width: Int, val height: Int)

/** Decode [uri] and re-encode as JPEG for an image page; null on failure. */
private fun readAsJpegPage(context: android.content.Context, uri: Uri): JpegPage? = runCatching {
    val bmp = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it)
    } ?: return null
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
    JpegPage(out.toByteArray(), bmp.width, bmp.height)
}.getOrNull()
