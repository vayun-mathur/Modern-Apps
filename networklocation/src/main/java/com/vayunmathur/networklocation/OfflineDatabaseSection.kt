package com.vayunmathur.networklocation

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.downloadservice.FileProgressItem
import com.vayunmathur.library.downloadservice.ModelDownloadItem
import com.vayunmathur.library.downloadservice.ModelDownloadWorker
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.DataStoreUtils

/**
 * Offline-database section of the provider's status screen: what is on disk, and a control to
 * fetch what is not.
 *
 * The download is user-initiated rather than automatic because it is many gigabytes, and
 * unmetered-only so it never lands on a cellular bill. Nothing here gates the provider - the two
 * framework services keep running throughout, but with no store present every beacon lookup
 * misses, so no position is reported until the beacon stores arrive. Geocoding is likewise
 * unavailable until the geocoder database arrives.
 */
@Composable
fun OfflineDatabaseSection(modifier: Modifier = Modifier, onReady: () -> Unit = {}) {
    val context = LocalContext.current
    val ds = remember { DataStoreUtils.getInstance(context) }

    val databases = remember { offlineDatabaseEntries() }
    var requested by remember { mutableStateOf(false) }
    // Presence is re-read whenever a download's progress moves: the worker publishes 1.0 only
    // after the finished file has been renamed into place, so the last tick sees it on disk.
    val progress = databases.map { (name, _, _) -> ds.doubleFlow("progress_$name").collectAsState(0.0).value }
    val allPresent = remember(requested, progress) { OfflineDatabases.allPresent(context) }
    LaunchedEffect(allPresent) {
        if (allPresent) onReady()
    }

    Column(modifier) {
        Text("Offline databases", style = MaterialTheme.typography.titleMedium)

        if (allPresent) {
            Text(
                "All offline databases are installed. Positioning and geocoding work without " +
                    "a network connection.",
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            return@Column
        }

        OfflineDatabaseBlurb()
        DatabaseProgressRows(context, ds, databases)
        DownloadDatabasesButton(
            context = context,
            databases = databases,
            requested = requested,
            onRequested = { requested = true },
        )
    }
}

private fun offlineDatabaseEntries() = listOf(
    Triple(OfflineDatabases.GEOCODER, "Offline geocoder", "addresses and place lookup"),
    Triple(OfflineDatabases.WIFI, "Wi-Fi beacon store", "offline Wi-Fi positioning"),
    Triple(OfflineDatabases.CELL, "Cell beacon store", "offline cell positioning"),
)

@Composable
private fun OfflineDatabaseBlurb() {
    Text(
        "The offline databases are large and are downloaded separately to keep the system " +
            "image small. Until they are installed, positioning and geocoding are " +
            "unavailable - nothing is looked up over the network. Downloads only run on " +
            "Wi-Fi and resume if interrupted.",
        modifier = Modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun DatabaseProgressRows(
    context: Context,
    ds: DataStoreUtils,
    databases: List<Triple<String, String, String>>,
) {
    for ((name, label, _) in databases) {
        val progress by ds.doubleFlow("progress_$name").collectAsState(0.0)
        val speedMbps by ds.doubleFlow("speed_$name").collectAsState(0.0)
        FileProgressItem(
            label = label,
            progress = progress,
            speedMbps = speedMbps,
            isDone = OfflineDatabases.isPresent(context, name),
        )
    }
}

@Composable
private fun DownloadDatabasesButton(
    context: Context,
    databases: List<Triple<String, String, String>>,
    requested: Boolean,
    onRequested: () -> Unit,
) {
    Button(
        onClick = {
            // Databases in a superseded format are dead weight - several gigabytes no reader
            // will open again - so reclaim the space before pulling their replacements down.
            OfflineDatabases.pruneStale(context)
            ModelDownloadWorker.enqueueTo(
                context = context,
                models = databases.map { (name, label, purpose) ->
                    ModelDownloadItem(
                        url = OfflineDatabases.urlFor(name),
                        fileName = name,
                        description = "$label — $purpose",
                        sha256 = OfflineDatabases.sha256For(name),
                    )
                },
                targetDir = OfflineDatabases.dir(context),
                requireUnmetered = true,
            )
            onRequested()
        },
        enabled = !requested,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
    ) {
        Text(if (requested) "Downloading over Wi-Fi…" else "Download offline databases")
    }
}

