package com.vayunmathur.maps.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.util.NavBackStack
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.ImageLoader
import com.vayunmathur.maps.Route
import com.vayunmathur.maps.ZoneDownloadManager
import kotlin.math.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadedMapsPage(backStack: NavBackStack<Route>) {
    val context = LocalContext.current
    val zoneManager = remember { ZoneDownloadManager(context) }
    val downloadedMaps by zoneManager.getDownloadedZonesFlow().collectAsState(initial = emptyList())
    val downloadingZones by zoneManager.getDownloadingZonesFlow().collectAsState(initial = emptyMap())

    var showDownloadDialogForZone by remember { mutableStateOf<Int?>(null) }
    var showDeleteDialogForZone by remember { mutableStateOf<Int?>(null) }
    val imageLoader = remember {
        ImageLoader.Builder(context)
            .build()
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Downloaded Maps") }, navigationIcon = {
            IconNavigation(backStack)
        })
    }) { paddingValues ->
        Box(
            Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            val imgMinLat = -85.0
            val imgMaxLat = 85.0

            fun merc(lat: Double): Double {
                val phi = lat * PI / 180.0
                return ln(tan(PI / 4.0 + phi / 2.0))
            }

            val totalMercHeight = merc(imgMaxLat) - merc(imgMinLat)
            val worldAspectRatio = (2.0 * PI / totalMercHeight).toFloat()

            Box(
                Modifier
                    .fillMaxSize()
                    .aspectRatio(worldAspectRatio)
            ) {
                // World Map Background Image
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data("file:///android_asset/world_map.png")
                        .build(),
                    imageLoader = imageLoader,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds
                )

                // Grid
                Column(Modifier.fillMaxSize()) {
                    val rowDefinitions = listOf(
                        imgMaxLat to 67.5,
                        67.5 to 45.0,
                        45.0 to 22.5,
                        22.5 to 0.0,
                        0.0 to -22.5,
                        -22.5 to -45.0,
                        -45.0 to -67.5,
                        -67.5 to imgMinLat
                    )

                    rowDefinitions.forEachIndexed { index, (topLat, bottomLat) ->
                        val weight = (merc(topLat) - merc(bottomLat)) / totalMercHeight
                        Row(Modifier.weight(weight.toFloat())) {
                            for (col in 0..7) {
                                val rowIdx = 7 - index
                                val zoneId = getZoneId(rowIdx, col)

                                val progress = downloadingZones[zoneId]
                                val isDownloaded = zoneId in downloadedMaps

                                val status = when {
                                    progress != null -> ZoneDownloadManager.ZoneStatus.DOWNLOADING
                                    isDownloaded -> ZoneDownloadManager.ZoneStatus.FINISHED
                                    else -> ZoneDownloadManager.ZoneStatus.NOT_STARTED
                                }

                                ZoneCell(
                                    status = status,
                                    progress = progress ?: 0f,
                                    onDownloadRequest = { showDownloadDialogForZone = zoneId },
                                    onDeleteRequest = { showDeleteDialogForZone = zoneId },
                                    modifier = Modifier.weight(1f).fillMaxHeight()
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    showDownloadDialogForZone?.let { zoneId ->
        AlertDialog(
            onDismissRequest = { showDownloadDialogForZone = null },
            confirmButton = {
                Button({
                    zoneManager.startDownload(zoneId)
                    showDownloadDialogForZone = null
                }) {
                    Text("Download")
                }
            },
            title = { Text("Download Offline Map?") },
            text = { Text("You are viewing Zone $zoneId. Would you like to download the high-detail offline map and enable navigation in this area?") },
            dismissButton = {
                TextButton({ showDownloadDialogForZone = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    showDeleteDialogForZone?.let { zoneId ->
        AlertDialog(
            onDismissRequest = { showDeleteDialogForZone = null },
            confirmButton = {
                Button({
                    zoneManager.deleteZone(zoneId)
                    showDeleteDialogForZone = null
                }) {
                    Text("Delete")
                }
            },
            title = { Text("Delete Offline Map?") },
            text = { Text("Are you sure you want to delete or cancel the download for Zone $zoneId?") },
            dismissButton = {
                TextButton({ showDeleteDialogForZone = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ZoneCell(
    status: ZoneDownloadManager.ZoneStatus,
    progress: Float,
    onDownloadRequest: () -> Unit,
    onDeleteRequest: () -> Unit,
    modifier: Modifier
) {
    val backgroundColor = when (status) {
        ZoneDownloadManager.ZoneStatus.FINISHED -> Color.Green.copy(alpha = 0.4f)
        ZoneDownloadManager.ZoneStatus.DOWNLOADING -> Color.Yellow.copy(alpha = 0.4f)
        ZoneDownloadManager.ZoneStatus.NOT_STARTED -> Color.Red.copy(alpha = 0.4f)
    }

    Box(
        modifier = modifier
            .background(backgroundColor)
            .border(0.5.dp, Color.White.copy(alpha = 0.2f))
            .combinedClickable(
                onClick = {
                    if (status == ZoneDownloadManager.ZoneStatus.NOT_STARTED) {
                        onDownloadRequest()
                    }
                },
                onLongClick = {
                    if (status == ZoneDownloadManager.ZoneStatus.FINISHED || status == ZoneDownloadManager.ZoneStatus.DOWNLOADING) {
                        onDeleteRequest()
                    }
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        when (status) {
            ZoneDownloadManager.ZoneStatus.FINISHED -> {
                IconButton(onClick = onDeleteRequest) {
                    IconDelete(tint = Color.White)
                }
            }
            ZoneDownloadManager.ZoneStatus.DOWNLOADING -> {
                Text(
                    text = "${(progress * 100).toInt()}%",
                    color = Color.Black,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            ZoneDownloadManager.ZoneStatus.NOT_STARTED -> {
                // Empty, tapping triggers download
            }
        }
    }
}

private fun getZoneId(row: Int, col: Int): Int {
    var zoneId = 0
    for (i in 0 until 3) {
        val colBit = (col shr i) and 1
        val rowBit = (row shr i) and 1
        zoneId = zoneId or (colBit shl (2 * i))
        zoneId = zoneId or (rowBit shl (2 * i + 1))
    }
    return zoneId
}
