package com.vayunmathur.youpipe.ui

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconBackup
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconRestore
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.MAIN_BOTTOM_BAR_ITEMS
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.data.HistoryVideo
import com.vayunmathur.youpipe.data.Subscription
import com.vayunmathur.youpipe.data.SubscriptionCategory
import com.vayunmathur.youpipe.util.getChannelInfoFromURL
import com.vayunmathur.youpipe.util.setupHourlyTask
import com.vayunmathur.youpipe.util.videoURLtoID
import java.util.zip.ZipInputStream
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsPage(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel) {
    val subscriptions by viewModel.data<Subscription>().collectAsState()
    val subscriptionCategoryPairs by viewModel.data<SubscriptionCategory>().collectAsState()
    val categories = subscriptionCategoryPairs.map { it.category }.distinct()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }

    val youtubeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                isLoading = true
                progress = 0f
                try {
                    val zipInputStream = ZipInputStream(context.contentResolver.openInputStream(uri))
                    var entry = zipInputStream.nextEntry
                    val subs = mutableListOf<Subscription>()
                    val history = mutableListOf<HistoryVideo>()
                    
                    while (entry != null) {
                        if (entry.name.endsWith("subscriptions/subscriptions.csv")) {
                            val content = zipInputStream.readBytes().decodeToString()
                            val lines = content.lines().drop(1) // Header
                            val total = lines.size
                            lines.forEachIndexed { index, line ->
                                if (line.isNotBlank()) {
                                    val parts = line.split(",")
                                    if (parts.size >= 2) {
                                        val url = parts[1]
                                        try {
                                            val channelInfo = getChannelInfoFromURL(url)
                                            subs.add(channelInfo.toSubscription())
                                        } catch (e: Exception) {
                                            Log.e("SubscriptionsPage", "Error fetching channel info for $url", e)
                                        }
                                    }
                                }
                                progress = (index + 1).toFloat() / total
                            }
                        } else if (entry.name.endsWith("history/watch-history.json")) {
                            val jsonString = zipInputStream.readBytes().decodeToString()
                            val jsonArray = Json.parseToJsonElement(jsonString).jsonArray
                            jsonArray.forEach { element ->
                                try {
                                    val title = element.jsonObject["title"]?.jsonPrimitive?.content?.removePrefix("Watched ") ?: ""
                                    val url = element.jsonObject["titleUrl"]?.jsonPrimitive?.content ?: ""
                                    val time = element.jsonObject["time"]?.jsonPrimitive?.content?.let { Instant.parse(it) } ?: Clock.System.now()
                                    val author = element.jsonObject["subtitles"]?.jsonArray?.firstOrNull()?.jsonObject?.get("name")?.jsonPrimitive?.content ?: ""
                                    
                                    if (url.contains("watch?v=")) {
                                        val videoID = videoURLtoID(url)
                                        history.add(
                                            HistoryVideo(
                                                id = videoID,
                                                progress = 0,
                                                videoItem = VideoInfo(title, videoID, 0, 0, time, "", author),
                                                timestamp = time
                                            )
                                        )
                                    }
                                } catch (e: Exception) {
                                    Log.e("SubscriptionsPage", "Error parsing history item", e)
                                }
                            }
                        } else if (entry.name.endsWith("history/watch-history.html")) {
                            val html = zipInputStream.readBytes().decodeToString()
                            // Simple regex-based parsing for HTML takeout
                            val regex = Regex("<div class=\"content-cell mdl-cell mdl-cell--6-col mdl-typography--body-1\">Watched&nbsp;<a href=\"(.*?)\">(.*?)</a><br><a href=\"(.*?)\">(.*?)</a><br>(.*?)</div>", RegexOption.DOT_MATCHES_ALL)
                            val matches = regex.findAll(html)
                            matches.forEach { match ->
                                try {
                                    val url = match.groupValues[1]
                                    val title = match.groupValues[2]
                                    val author = match.groupValues[4]
                                    
                                    if (url.contains("watch?v=")) {
                                        val videoID = videoURLtoID(url)
                                        history.add(
                                            HistoryVideo(
                                                id = videoID,
                                                progress = 0,
                                                videoItem = VideoInfo(title, videoID, 0, 0, Clock.System.now(), "", author),
                                                timestamp = Clock.System.now()
                                            )
                                        )
                                    }
                                } catch (e: Exception) {
                                    Log.e("SubscriptionsPage", "Error parsing HTML history item", e)
                                }
                            }
                        }
                        entry = zipInputStream.nextEntry
                    }
                    
                    if (subs.isNotEmpty()) viewModel.upsertAll(subs)
                    if (history.isNotEmpty()) viewModel.upsertAll(history)
                    
                } catch (e: Exception) {
                    Log.e("SubscriptionsPage", "Error importing YouTube Takeout", e)
                }
                isLoading = false
                setupHourlyTask(context)
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val subs = viewModel.getAll<Subscription>()
                    val json = Json.encodeToString(subs)
                    context.contentResolver.openOutputStream(uri)?.use { 
                        it.write(json.toByteArray())
                    }
                } catch (e: Exception) {
                    Log.e("SubscriptionsPage", "Error exporting subscriptions", e)
                }
            }
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                isLoading = true
                try {
                    val json = context.contentResolver.openInputStream(uri)!!.bufferedReader().readText()
                    val subs = Json.decodeFromString<List<Subscription>>(json)
                    viewModel.replaceAll(subs)
                } catch (e: Exception) {
                    Log.e("SubscriptionsPage", "Error restoring subscriptions", e)
                }
                isLoading = false
                setupHourlyTask(context)
            }
        }
    }

    val newPipeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                isLoading = true
                progress = 0f
                try {
                    val jsonString = context.contentResolver.openInputStream(uri)!!.bufferedReader().readText()
                    val json = Json.parseToJsonElement(jsonString).jsonObject
                    val subsArray = json["subscriptions"]?.jsonArray
                    if (subsArray != null) {
                        val total = subsArray.size
                        val subs = mutableListOf<Subscription>()
                        subsArray.forEachIndexed { index, element ->
                            try {
                                var url = element.jsonObject["url"]?.jsonPrimitive?.content ?: ""
                                if (!url.startsWith("http")) {
                                    url = "https://$url"
                                }
                                val channelInfo = getChannelInfoFromURL(url)
                                subs.add(channelInfo.toSubscription())
                            } catch (e: Exception) {
                                Log.e("SubscriptionsPage", "Error importing channel", e)
                            }
                            progress = (index + 1).toFloat() / total
                        }
                        viewModel.replaceAll(subs)
                    }
                } catch (e: Exception) {
                    Log.e("SubscriptionsPage", "Error importing NewPipe subscriptions", e)
                }
                isLoading = false
                setupHourlyTask(context)
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar({Text(stringResource(R.string.title_subscriptions))}, actions = {
            if(!isLoading) {
                IconButton({
                    exportLauncher.launch("youpipe_subscriptions.json")
                }) {
                    IconBackup()
                }
                
                var showRestoreMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton({
                        showRestoreMenu = true
                    }) {
                        IconRestore()
                    }
                    DropdownMenu(expanded = showRestoreMenu, onDismissRequest = { showRestoreMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Restore from YouPipe") },
                            onClick = {
                                showRestoreMenu = false
                                restoreLauncher.launch("application/json")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Import NewPipe subscriptions") },
                            onClick = {
                                showRestoreMenu = false
                                newPipeLauncher.launch("application/json")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Import from YouTube") },
                            onClick = {
                                showRestoreMenu = false
                                youtubeLauncher.launch("application/zip")
                            }
                        )
                    }
                }
            }
        })
    }, bottomBar = { BottomNavBar(backStack, MAIN_BOTTOM_BAR_ITEMS, Route.SubscriptionsPage) }) { paddingValues ->
        if(!isLoading) {
            LazyColumn(Modifier.padding(paddingValues)) {
                item {
                    ListItem({
                        Text(stringResource(R.string.label_groups))
                    }, trailingContent = {
                        IconButton({
                            backStack.add(Route.CreateSubscriptionCategory(null))
                        }) {
                            IconAdd()
                        }
                    })
                }
                item {
                    ListItem({
                        Text(stringResource(R.string.label_all_subscriptions))
                    }, Modifier.clickable {
                        backStack.add(Route.SubscriptionVideosPage(null))
                    })
                }
                items(categories) {
                    ListItem({
                        Text(it)
                    }, Modifier.clickable {
                        backStack.add(Route.SubscriptionVideosPage(it))
                    }, trailingContent = {
                        IconButton({
                            backStack.add(Route.CreateSubscriptionCategory(it))
                        }) {
                            IconEdit()
                        }
                    })
                }
                item {
                    ListItem({ Text(stringResource(R.string.label_channels)) })
                }
                items(subscriptions) {
                    ListItem({
                        Text(it.name)
                    }, Modifier.clickable {
                        backStack.add(Route.ChannelPage(it.channelID))
                    }, {}, {}, {
                        AsyncImage(
                            model = it.avatarURL,
                            contentDescription = null,
                            Modifier.size(24.dp).clip(CircleShape)
                        )
                    })
                }
            }
        } else {
            Box(Modifier.fillMaxSize()) {
                CircularProgressIndicator({progress}, modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}
