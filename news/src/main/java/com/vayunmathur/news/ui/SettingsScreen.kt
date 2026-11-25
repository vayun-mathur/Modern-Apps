package com.vayunmathur.news.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.Text
import com.vayunmathur.news.R
import com.vayunmathur.news.platform.NewsViewModel

/**
 * News settings. The app had no settings screen before this (feed sort
 * lives on the feed, reader options in the reader); this is the natural
 * settings route following the app's Route/Navigation patterns, and it
 * currently holds the Local news location preference. Picking a location
 * is navigation ([com.vayunmathur.news.Route.LocalLocation]), not local
 * screen state.
 */
@Composable
fun SettingsScreen(
    viewModel: NewsViewModel,
    onBack: () -> Unit,
    onOpenLocalLocation: () -> Unit,
) {
    val localLocation by viewModel.localLocation.collectAsState()
    AppScaffold(
        title = stringResource(R.string.settings_title),
        onNavigateBack = onBack,
    ) { padding ->
        DesktopMaxWidthContainer(modifier = Modifier.padding(padding)) {
        Column(modifier = Modifier.fillMaxSize()) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.local_location_title)) },
                supportingContent = {
                    Text(localLocation ?: stringResource(R.string.local_location_unset))
                },
                modifier = Modifier.clickable { onOpenLocalLocation() },
            )
            Text(
                stringResource(R.string.settings_note),
                modifier = Modifier.padding(16.dp),
            )
        }
        }
    }
}
