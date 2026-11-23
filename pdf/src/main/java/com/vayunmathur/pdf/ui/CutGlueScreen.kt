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
import androidx.compose.ui.res.stringResource

/**
 * "Cut and glue": compose a new PDF by appending whole PDFs or images, then
 * drag pages into the desired order. Starts empty.
 */
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

    CutGlueContent(
        pageKeys = pageKeys,
        renderPage = { index -> doc.renderPage(index) },
        onAppendImage = {
            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onAppendPdf = { pdfPicker.launch(arrayOf("application/pdf")) },
        onSave = { saveLauncher.launch("composed.pdf") },
        onMove = { from, to ->
            val k = pageKeys.removeAt(from)
            pageKeys.add(to, k)
            scope.launch { doc.movePage(from, to) }
        },
        onDelete = { index ->
            pageKeys.removeAt(index)
            scope.launch { doc.removePage(index) }
        },
        onBack = onBack,
    )
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
