package com.vayunmathur.maps

import android.app.DownloadManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.maps.data.AmenityDatabase
import com.vayunmathur.maps.data.buildAmenityDatabase
import com.vayunmathur.maps.ui.DownloadedMapsPage
import com.vayunmathur.maps.ui.MapPage
import com.vayunmathur.maps.ui.SearchPage
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
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
        ensurePmtilesReady(this)
        setContent {
            DynamicTheme {
                val dbSetup by ds.booleanFlow("dbSetupComplete").collectAsState(false)
                if(dbSetup) {
                    val db = remember { buildAmenityDatabase(this@MainActivity) }
                    Navigation(ds, db)
                } else {
                    DatabaseSetup(ds)
                }
            }
        }
    }
}

@Composable
fun DatabaseSetup(ds: DataStoreUtils) {
    val context = LocalContext.current
    val progress by ds.doubleFlow("downloadProgress").collectAsState(0.0)
    LaunchedEffect(Unit) {
        val id = downloadOsmData(context, "https://data.vayunmathur.com/amenities.db")
        while(progress != 1.0) {
            ds.setDouble("downloadProgress", getProgress(context, id))
            delay(100)
        }
        ds.setBoolean("dbSetupComplete", true)
    }
    Scaffold() { paddingValues ->
        Box(Modifier.padding(paddingValues).fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(Modifier.padding(16.dp)) {
                Text((progress*100).toString())
            }
        }
    }
}

@Serializable
sealed interface Route: NavKey {
    @Serializable
    data object MapPage: Route
    @Serializable
    data object DownloadedMapsPage: Route

    @Serializable
    data class SearchPage(val idx: Int?, val east: Double, val west: Double, val north: Double, val south: Double): Route
}

@Composable
fun Navigation(ds: DataStoreUtils, db: AmenityDatabase, viewModel: SelectedFeatureViewModel = viewModel()) {
    val backStack = rememberNavBackStack<Route>(Route.MapPage)
    MainNavigation(backStack) {
        entry<Route.MapPage> {
            MapPage(backStack, viewModel, ds, db)
        }
        entry<Route.DownloadedMapsPage> {
            DownloadedMapsPage(backStack)
        }
        entry<Route.SearchPage> {
            SearchPage(backStack, viewModel, db, it.idx, it.east, it.west, it.north, it.south)
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
        return ((bytesDownloaded).toDouble() / bytesTotal)
    }
    return 0.0
}

fun downloadOsmData(context: Context, url: String): Long {
    val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    val fileName = "amenities.db"

    // 1. Check if the download is already in progress or completed
    val query = DownloadManager.Query()
    val cursor = downloadManager.query(query)

    if (cursor != null) {
        while (cursor.moveToNext()) {
            val title = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE))
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))

            // Check by title or destination (Title is more reliable for filtering here)
            if (title == "OSM Data Update") {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))

                // If it's running, pending, or successful, return the existing ID
                if (status == DownloadManager.STATUS_RUNNING ||
                    status == DownloadManager.STATUS_PENDING ||
                    status == DownloadManager.STATUS_SUCCESSFUL) {
                    cursor.close()
                    return id
                }
            }
        }
        cursor.close()
    }

    // 2. If no active/completed download found, start a new one
    val request = DownloadManager.Request(url.toUri())
        .setTitle("OSM Data Update")
        .setDescription("Downloading 2.5GB tag database...")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalFilesDir(context, null, fileName)
        .setAllowedOverMetered(false)
        .setRequiresCharging(false)

    return downloadManager.enqueue(request)
}