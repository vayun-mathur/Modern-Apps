package com.vayunmathur.maps

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.maps.data.TagDatabase
import com.vayunmathur.maps.ui.MapPage
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File

fun ensurePmtilesReady(context: Context): String {
    val fileName = "world_z0-6.pmtiles"
    val outFile = File(context.filesDir, fileName)

    if (!outFile.exists()) {
        context.assets.open(fileName).use { input ->
            outFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }
    return "pmtiles://file://${outFile.absolutePath}"
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val ds = DataStoreUtils.getInstance(this)
        val db = buildDatabase<TagDatabase>();
        val localPath = ensurePmtilesReady(this)
        setContent {
            DynamicTheme {
                val downloadedState by ds.getLongState("downloaded")
                val isDownloaded = downloadedState == 2L
                if(isDownloaded) {
                    MapPage(ds, db)
                } else {
                    DownloadPage(ds, db)
                }
            }
        }
    }
}

@Composable
fun DownloadPage(ds: DataStoreUtils, db: TagDatabase) {
    val context = LocalContext.current
    val downloaded by ds.getLongState("downloaded")
    Scaffold() { paddingValues ->
        Box(Modifier.padding(paddingValues).fillMaxSize()) {
            if(downloaded == 0L) {
                Button({
                    downloadOsmData(context, "https://api.vayunmathur.com/maps/amenities", "https://api.vayunmathur.com/maps/search", ds)
                }, Modifier.align(Alignment.Center)) {
                    Text("Download")
                }
            } else {
                var progress by remember { mutableStateOf(0.0) }
                LaunchedEffect(Unit) {
                    val downloadID = ds.getLong("downloadID")!!
                    val downloadID2 = ds.getLong("downloadID2")!!
                    while(true) {
                        progress = getProgress(context, downloadID) + getProgress(context, downloadID2)
                        if(progress == 2.0) {
                            ds.setLong("downloaded", 2L)
                        }
                        delay(100)
                    }
                }
                Text("Downloading: ${progress/2*100}%", Modifier.align(Alignment.Center))
            }
        }
    }
}

fun getProgress(context: Context, downloadId: Long): Double {
    val q = DownloadManager.Query().setFilterById(downloadId)
    val cursor = (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).query(q)
    if (cursor.moveToFirst()) {
        val bytesDownloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
        val bytesTotal = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
        if (bytesTotal == 0L) return 0.0
        println(bytesDownloaded)
        println(bytesTotal)
        return ((bytesDownloaded).toDouble() / bytesTotal)
    }
    return 0.0
}

fun downloadOsmData(context: Context, url1: String, url2: String, ds: DataStoreUtils) {
    val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    val request = DownloadManager.Request(url1.toUri())
        .setTitle("OSM Data Update")
        .setDescription("Downloading 2.5GB tag database...")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        // Save to app-specific external storage (no permission needed)
        .setDestinationInExternalFilesDir(context, null, "amenities_indexed.bin")
        .setAllowedOverMetered(false) // Recommended for a 2.5GB file!
        .setRequiresCharging(false)


    val request2 = DownloadManager.Request(url2.toUri())
        .setTitle("OSM Data Update")
        .setDescription("Downloading 2.5GB tag database...")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        // Save to app-specific external storage (no permission needed)
        .setDestinationInExternalFilesDir(context, null, "names_only.bin")
        .setAllowedOverMetered(false) // Recommended for a 2.5GB file!
        .setRequiresCharging(false)

    val downloadId = downloadManager.enqueue(request)
    val downloadId2 = downloadManager.enqueue(request2)

    runBlocking {
        ds.setLong("downloaded", 1L)
        ds.setLong("downloadID", downloadId)
        ds.setLong("downloadID2", downloadId2)
    }
}