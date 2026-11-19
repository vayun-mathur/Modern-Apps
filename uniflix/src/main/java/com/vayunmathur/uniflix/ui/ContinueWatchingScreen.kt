package com.vayunmathur.uniflix.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.Text
import com.vayunmathur.uniflix.data.ContinueWatchingEntry
import com.vayunmathur.uniflix.data.UniflixRepository
import com.vayunmathur.uniflix.domain.MediaItem
import com.vayunmathur.uniflix.domain.MediaType

/** Resume rail: saved positions, newest first. */
@Composable
fun ContinueWatchingScreen(onBack: () -> Unit, onResume: (ContinueWatchingEntry) -> Unit) {
    val context = LocalContext.current
    val repository = remember(context) { UniflixRepository.get(context) }
    val entries by repository.continueWatching.collectAsState(initial = emptyList())

    AppScaffold(
        title = "Continue watching",
        onNavigateBack = onBack,
    ) { padding ->
        DesktopMaxWidthContainer(Modifier.padding(padding)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (entries.isEmpty()) {
                    Text("Nothing in progress yet.")
                    return@Column
                }
                entries.forEach { entry ->
                    val mediaItem = MediaItem(
                        tmdbId = entry.tmdbId,
                        mediaType = if (entry.mediaType == "tv") MediaType.TV else MediaType.MOVIE,
                        title = entry.title,
                        posterPath = entry.posterPath,
                    )
                    MediaCard(
                        item = mediaItem,
                        onOpen = {
                            onResume(entry)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
