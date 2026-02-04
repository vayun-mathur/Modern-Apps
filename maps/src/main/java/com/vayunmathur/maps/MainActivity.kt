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
import com.vayunmathur.maps.ui.MapPage
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val ds = DataStoreUtils.getInstance(this)
        setContent {
            DynamicTheme {
                val downloadedState by ds.getLongState("downloaded")
                val isDownloaded = downloadedState == 2L
                if(isDownloaded) {
                    MapPage()
                } else {
                    DownloadPage(ds)
                }
            }
        }
    }
}

@Composable
fun DownloadPage(ds: DataStoreUtils) {
    val context = LocalContext.current
    val downloaded by ds.getLongState("downloaded")
    Scaffold() { paddingValues ->
        Box(Modifier.padding(paddingValues).fillMaxSize()) {
            if(downloaded == 0L) {
                Button({
                    val onDownloadComplete = object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                            println(id)
                            if (id != -1L) {
                                // Success! Now you can initialize your MappedByteBuffer
                                Toast.makeText(context, "OSM Data Ready", Toast.LENGTH_SHORT).show()
                                runBlocking {
                                    ds.setLong("downloaded", 2L) // done
                                }
                            }
                        }
                    }
                    val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        context.registerReceiver(
                            onDownloadComplete,
                            filter,
                            Context.RECEIVER_NOT_EXPORTED // This is the magic flag
                        )
                    } else {
                        context.registerReceiver(onDownloadComplete, filter)
                    }

                    downloadOsmData(context, "https://api.vayunmathur.com/maps/amenities", ds)
                }, Modifier.align(Alignment.Center)) {
                    Text("Download")
                }
            } else {
                var progress by remember { mutableStateOf(0.0) }
                LaunchedEffect(Unit) {
                    val downloadID = ds.getLong("downloadID")!!
                    while(true) {
                        progress = getProgress(context, downloadID)
                        if(progress == 1.0) {
                            ds.setLong("downloaded", 2L)
                        }
                        delay(100)
                    }
                }
                Text("Downloading: ${progress*100}%", Modifier.align(Alignment.Center))
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

fun downloadOsmData(context: Context, url: String, ds: DataStoreUtils) {
    val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    val request = DownloadManager.Request(url.toUri())
        .setTitle("OSM Data Update")
        .setDescription("Downloading 2.5GB tag database...")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        // Save to app-specific external storage (no permission needed)
        .setDestinationInExternalFilesDir(context, null, "amenities_indexed.bin")
        .setAllowedOverMetered(false) // Recommended for a 2.5GB file!
        .setRequiresCharging(false)

    val downloadId = downloadManager.enqueue(request)

    runBlocking {
        ds.setLong("downloaded", 1L)
        ds.setLong("downloadID", downloadId)
    }
}