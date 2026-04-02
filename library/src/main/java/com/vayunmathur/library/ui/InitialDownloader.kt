package com.vayunmathur.library.ui

import android.content.Intent
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
import com.vayunmathur.library.services.DownloadService
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.round

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

    // Launch the Foreground Service immediately when this screen is first composed
    LaunchedEffect(Unit) {
        val intent = Intent(context, DownloadService::class.java).apply {
            putExtra("urls", filesToDownload.map { it.first }.toTypedArray())
            putExtra("fileNames", filesToDownload.map { it.second }.toTypedArray())
        }
        context.startForegroundService(intent)
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
                items(filesToDownload) { (url, fileName, desc) ->
                    // Each item observes its own specific progress and speed from DataStore
                    val progress by ds.doubleFlow("progress_$fileName").collectAsState(0.0)
                    val speedMbps by ds.doubleFlow("speed_$fileName").collectAsState(0.0)
                    val isDone by ds.booleanFlow("done_$fileName").collectAsState(false)

                    FileProgressItem(
                        fileName = fileName,
                        progress = progress,
                        speedMbps = speedMbps,
                        isDone = isDone
                    )
                }
            }
        }
    }
}

@Composable
fun FileProgressItem(
    fileName: String,
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
                fileName,
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