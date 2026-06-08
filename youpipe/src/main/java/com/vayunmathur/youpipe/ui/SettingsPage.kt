package com.vayunmathur.youpipe.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.MAIN_BOTTOM_BAR_ITEMS
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.util.YouPipeViewModel
import com.vayunmathur.youpipe.util.YouPipeViewModel.Companion.ALL_SPONSOR_CATEGORIES
import com.vayunmathur.youpipe.util.YouPipeViewModel.Companion.SPONSOR_CATEGORY_LABELS

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(
    backStack: NavBackStack<Route>,
    ypvm: YouPipeViewModel,
) {
    val sponsorBlockEnabled by ypvm.sponsorBlockEnabled.collectAsState()
    val sponsorBlockCategories by ypvm.sponsorBlockCategories.collectAsState()
    val deArrowEnabled by ypvm.deArrowEnabled.collectAsState()
    val isLoading by ypvm.isImporting.collectAsState()
    val progress by ypvm.importProgress.collectAsState()

    val youtubeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) ypvm.importYouTubeTakeout(uri)
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) ypvm.exportSubscriptions(uri)
    }

    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) ypvm.restoreSubscriptions(uri)
    }

    val newPipeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) ypvm.importNewPipe(uri)
    }

    Scaffold(
        topBar = { TopAppBar({ Text(stringResource(R.string.title_settings)) }) },
        bottomBar = { BottomNavBar(backStack, MAIN_BOTTOM_BAR_ITEMS, Route.Settings) }
    ) { paddingValues ->
        if (!isLoading) {
            LazyColumn(Modifier.padding(paddingValues)) {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.label_sponsorblock)) },
                        supportingContent = { Text(stringResource(R.string.label_sponsorblock_description)) },
                        trailingContent = {
                            Switch(
                                checked = sponsorBlockEnabled,
                                onCheckedChange = { ypvm.setSponsorBlockEnabled(it) }
                            )
                        }
                    )
                }
                if (sponsorBlockEnabled) {
                    ALL_SPONSOR_CATEGORIES.forEach { category ->
                        item(key = "sb_$category") {
                            val label = SPONSOR_CATEGORY_LABELS[category] ?: category
                            val checked = category in sponsorBlockCategories
                            ListItem(
                                headlineContent = { Text(label) },
                                trailingContent = {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = { ypvm.toggleSponsorBlockCategory(category) }
                                    )
                                },
                                modifier = Modifier.padding(start = 16.dp)
                            )
                        }
                    }
                }
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.label_dearrow)) },
                        supportingContent = { Text(stringResource(R.string.label_dearrow_description)) },
                        trailingContent = {
                            Switch(
                                checked = deArrowEnabled,
                                onCheckedChange = { ypvm.setDeArrowEnabled(it) }
                            )
                        }
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.label_backup_restore)) }
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.label_export_youpipe)) },
                        modifier = Modifier.padding(start = 16.dp).clickable {
                            exportLauncher.launch("youpipe_subscriptions.json")
                        }
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.label_restore_youpipe)) },
                        modifier = Modifier.padding(start = 16.dp).clickable {
                            restoreLauncher.launch("application/json")
                        }
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.label_import_newpipe)) },
                        modifier = Modifier.padding(start = 16.dp).clickable {
                            newPipeLauncher.launch("application/json")
                        }
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.label_import_youtube)) },
                        modifier = Modifier.padding(start = 16.dp).clickable {
                            youtubeLauncher.launch("application/zip")
                        }
                    )
                }
            }
        } else {
            Box(Modifier.fillMaxSize()) {
                CircularProgressIndicator({ progress }, modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}
