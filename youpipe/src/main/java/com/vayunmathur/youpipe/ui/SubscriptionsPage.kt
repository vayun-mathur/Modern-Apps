package com.vayunmathur.youpipe.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import coil.compose.AsyncImage
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.youpipe.MAIN_BOTTOM_BAR_ITEMS
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.data.Subscription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.schabi.newpipe.extractor.ServiceList

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsPage(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel) {
    val subscriptions by viewModel.data<Subscription>().collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }

    val filePickerActivityContract = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
        it?.let {
            val content = Json.parseToJsonElement(context.contentResolver.openInputStream(it)!!.bufferedReader().readText()).jsonObject["subscriptions"]!!.jsonArray.map {
                it.jsonObject["url"]!!.jsonPrimitive.content
            }
            coroutineScope.launch(Dispatchers.IO) {
                progress = 0f
                isLoading = true
                val subs = content.mapIndexed { idx, url ->
                    val ex = ServiceList.YouTube.getChannelExtractor(url)
                    ex.fetchPage()
                    progress = (idx + 1).toFloat() / content.size
                    Subscription(name = ex.name, url = url, avatarURL = ex.avatars.first().url)
                }
                viewModel.replaceAll(subs)
                isLoading = false
            }
        }
    }
    Scaffold(topBar = {
        TopAppBar({Text("Subscriptions")}, actions = {
            if(!isLoading) {
                IconButton({
                    // save subscription to file
                }) {
                    Icon(painterResource(R.drawable.outline_backup_24), null)
                }
                IconButton({
                    // open file then
                    filePickerActivityContract.launch("application/json")
                }) {
                    Icon(painterResource(R.drawable.outline_settings_backup_restore_24), null)
                }
            }
        })
    }, bottomBar = { BottomNavBar(backStack, MAIN_BOTTOM_BAR_ITEMS, Route.SubscriptionsPage) }) { paddingValues ->
        if(!isLoading) {
            LazyColumn(Modifier.padding(paddingValues)) {
                item {
                    Text(
                        "Groups:",
                        Modifier.padding(start = 4.dp),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                item {
                    ListItem({
                        Text("All Subscriptions")
                    }, Modifier.clickable {
                        backStack.add(Route.SubscriptionVideosPage)
                    })
                }
                item {
                    Text(
                        "Channels:",
                        Modifier.padding(start = 4.dp),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                items(subscriptions) {
                    ListItem({
                        Text(it.name)
                    }, Modifier.clickable {
                        backStack.add(Route.ChannelPage(it.url))
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