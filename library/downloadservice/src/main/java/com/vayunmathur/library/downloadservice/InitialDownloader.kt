package com.vayunmathur.library.downloadservice

import android.app.DownloadManager
import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.round
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun InitialDownloadChecker(
    ds: DataStoreUtils,
    filesToDownload: List<Triple<String, String, String>>,
    mainPage: @Composable () -> Unit
) {
    val dbSetup by ds.booleanFlow("dbSetupComplete").collectAsState(false)
    if (dbSetup) {
        mainPage()
    } else {
        InitialDownloadScreen(ds, filesToDownload)
    }
}

@Composable
fun InitialDownloadScreen(
    ds: DataStoreUtils,
    filesToDownload: List<Triple<String, String, String>>
) {
    val context = LocalContext.current

    // Enqueue with Android's DownloadManager and poll it for progress, writing
    // the same DataStore keys the UI below observes.
    LaunchedEffect(Unit) {
        runDownloads(context, ds, filesToDownload)
    }

    Scaffold { paddingValues ->
        Column(
            Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .padding(24.dp)
        ) {
            Text(
                "Initializing System",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Downloading required components for this app.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray
            )

            Spacer(Modifier.height(32.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(filesToDownload, key = { it.second }) { (_, fileName, desc) ->
                    // Each item observes its own specific progress and speed from DataStore
                    val progress by ds.doubleFlow("progress_$fileName").collectAsState(0.0)
                    val speedMbps by ds.doubleFlow("speed_$fileName").collectAsState(0.0)
                    val isDone by ds.booleanFlow("done_$fileName").collectAsState(false)

                    FileProgressItem(
                        label = desc,
                        progress = progress,
                        speedMbps = speedMbps,
                        isDone = isDone
                    )
                }
            }
        }
    }
}

private class Active(
    val fileName: String,
    val id: Long,
    var lastBytes: Long,
    var lastTime: Long,
)

/**
 * Drive the initial downloads with Android's [DownloadManager]. Each file is
 * enqueued (reusing a still-valid prior request id stored in DataStore so we
 * resume across process restarts), then the manager is polled to publish
 * `progress_*` / `speed_*` / `done_*` and finally `dbSetupComplete`.
 */
private suspend fun runDownloads(
    context: Context,
    ds: DataStoreUtils,
    files: List<Triple<String, String, String>>,
) = withContext(Dispatchers.IO) {
    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    val active = mutableListOf<Active>()
    for ((url, fileName, _) in files) {
        if (ds.getBoolean("done_$fileName", false)) continue
        val existingId = ds.getLong("dlid_$fileName") ?: 0L
        val id = if (existingId > 0L && isQueryable(dm, existingId)) {
            existingId
        } else {
            enqueue(dm, context, ds, url, fileName)
        }
        active += Active(fileName, id, 0L, System.currentTimeMillis())
    }

    while (active.isNotEmpty()) {
        val query = DownloadManager.Query().setFilterById(*active.map { it.id }.toLongArray())
        dm.query(query)?.use { c ->
            val idIdx = c.getColumnIndex(DownloadManager.COLUMN_ID)
            val statusIdx = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val soFarIdx = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalIdx = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val seen = mutableSetOf<Long>()
            while (c.moveToNext()) {
                val id = c.getLong(idIdx)
                seen += id
                val a = active.firstOrNull { it.id == id } ?: continue
                val status = c.getInt(statusIdx)
                val soFar = c.getLong(soFarIdx)
                val total = c.getLong(totalIdx)
                val now = System.currentTimeMillis()
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        ds.setBoolean("done_${a.fileName}", true)
                        ds.setDouble("progress_${a.fileName}", 1.0)
                        ds.setDouble("speed_${a.fileName}", 0.0)
                        active.remove(a)
                    }
                    DownloadManager.STATUS_FAILED -> {
                        // Forget the id so it is re-enqueued on the next entry.
                        ds.setLong("dlid_${a.fileName}", 0L)
                        active.remove(a)
                    }
                    else -> {
                        val progress = if (total > 0) soFar.toDouble() / total else 0.0
                        ds.setDouble("progress_${a.fileName}", progress)
                        val dt = (now - a.lastTime) / 1000.0
                        if (dt > 0) {
                            val speedMbps = ((soFar - a.lastBytes) * 8.0) / 1_000_000.0 / dt
                            ds.setDouble("speed_${a.fileName}", speedMbps.coerceAtLeast(0.0))
                            a.lastBytes = soFar
                            a.lastTime = now
                        }
                    }
                }
            }
            // Rows that vanished from the manager (e.g. cleared) — drop & retry later.
            active.removeAll { it.id !in seen }
        }
        if (active.isEmpty()) break
        delay(700)
    }

    if (files.all { ds.getBoolean("done_${it.second}", false) }) {
        ds.setBoolean("dbSetupComplete", true)
    }
}

private suspend fun enqueue(
    dm: DownloadManager,
    context: Context,
    ds: DataStoreUtils,
    url: String,
    fileName: String,
): Long {
    // DownloadManager fails if the destination already exists; clear any stale partial.
    File(context.getExternalFilesDir(null), fileName).takeIf { it.exists() }?.delete()
    val request = DownloadManager.Request(url.toUri()).apply {
        setTitle(fileName)
        setDescription("Downloading required components")
        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        setDestinationInExternalFilesDir(context, null, fileName)
        setAllowedOverMetered(true)
        setAllowedOverRoaming(true)
    }
    val id = dm.enqueue(request)
    ds.setLong("dlid_$fileName", id)
    return id
}

private fun isQueryable(dm: DownloadManager, id: Long): Boolean {
    dm.query(DownloadManager.Query().setFilterById(id))?.use { return it.moveToFirst() }
    return false
}

@Composable
fun FileProgressItem(
    label: String,
    progress: Double,
    speedMbps: Double,
    isDone: Boolean
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.toFloat(),
        label = "smooth_progress"
    )

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1.0f),
                maxLines = 1
            )

            if (!isDone && progress > 0) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.extraSmall
                ) {
                    Text(
                        text = "${speedMbps.round(1)} Mbps",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = if (isDone) "Completed" else "Downloading...",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray
            )

            Text(
                text = "${(progress * 100).round(1)}%",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(8.dp))

        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            strokeCap = StrokeCap.Round,
            color = if (isDone) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary
        )
    }
}
