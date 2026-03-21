package com.vayunmathur.maps

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.InitialDownloadChecker
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.maps.data.AmenityDatabase
import com.vayunmathur.maps.data.buildAmenityDatabase
import com.vayunmathur.maps.ui.DownloadedMapsPage
import com.vayunmathur.maps.ui.MapPage
import com.vayunmathur.maps.ui.SearchPage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.maplibre.android.log.Logger
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
        println(OfflineRouter.checkFiles(this))
        Logger.setVerbosity(Logger.INFO)
        runBlocking {
//            File(getExternalFilesDir(null)!!, "edge_index.bin").delete()
//            File(getExternalFilesDir(null)!!, "edges.bin").delete()
//            ds.setBoolean("dbSetupComplete", false)
//            ds.setBoolean("done_edge_index.bin", false)
//            ds.setBoolean("done_edges.bin", false)
        }
//        finish()
        setContent {
            DynamicTheme {
                InitialDownloadChecker(ds, listOf(
                    Triple("https://data.vayunmathur.com/amenities.db", "amenities.db", "Downloading Amenity Database..."),
                    Triple("https://data.vayunmathur.com/nodes_lookup.bin", "nodes_lookup.bin", "Downloading Routing Nodes..."),
                    Triple("https://data.vayunmathur.com/nodes_spatial.bin", "nodes_spatial.bin", "Downloading Spatial Index..."),
                    Triple("https://data.vayunmathur.com/edges.bin", "edges.bin", "Downloading Routing Graph..."),
                    Triple("https://data.vayunmathur.com/edge_index.bin", "edge_index.bin", "Downloading Edge Index...")
                )) {
                    val db = remember { buildAmenityDatabase(this@MainActivity) }
                    Navigation(db)
                }
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
fun Navigation(db: AmenityDatabase, viewModel: SelectedFeatureViewModel = viewModel()) {
    val backStack = rememberNavBackStack<Route>(Route.MapPage)
    MainNavigation(backStack) {
        entry<Route.MapPage> {
            MapPage(backStack, viewModel, db)
        }
        entry<Route.DownloadedMapsPage> {
            DownloadedMapsPage(backStack)
        }
        entry<Route.SearchPage> {
            SearchPage(backStack, viewModel, db, it.idx, it.east, it.west, it.north, it.south)
        }
    }
}