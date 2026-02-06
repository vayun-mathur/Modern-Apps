package com.vayunmathur.maps.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.maps.Route
import com.vayunmathur.maps.ZoneDownloadManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadedMapsPage(backStack: NavBackStack<Route>) {
    val context = LocalContext.current
    val zoneManager = remember { ZoneDownloadManager(context) }
    val downloadedMaps by zoneManager.getDownloadedZonesFlow().collectAsState(initial = emptyList())
    val downloadingZones by zoneManager.getDownloadingZonesFlow().collectAsState(initial = emptyMap())
    Scaffold(topBar = {
        TopAppBar(title = { Text("Downloaded Maps") }, navigationIcon = {
            IconNavigation(backStack)
        })
    }) { paddingValues ->
        Column(Modifier.padding(paddingValues)) {
            LazyColumn() {
                item {
                    Text("Downloading Zones:", Modifier.padding(horizontal = 16.dp))
                }
                items(downloadingZones.entries.toList(), {it.key}) {
                    ListItem({
                        Text("Zone ${it.key}")
                    }, trailingContent = {
                        Text("${(it.value * 100).toInt()}%")
                    })
                }
                item {
                    Text("Downloaded Zones:", Modifier.padding(horizontal = 16.dp))
                }
                items(downloadedMaps, {it}) {
                    ListItem({
                        Text("Zone $it")
                    }, trailingContent = {
                        IconButton({
                            zoneManager.deleteZone(it)
                        }) {
                            Text("Delete")
                        }
                    })
                }
            }
        }
    }
}