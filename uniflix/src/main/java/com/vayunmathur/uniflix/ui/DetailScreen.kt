package com.vayunmathur.uniflix.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.ErrorState
import com.vayunmathur.library.ui.friendlyErrorMessage
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.uniflix.data.TmdbImageUrl
import com.vayunmathur.uniflix.data.UniflixRepository
import com.vayunmathur.uniflix.domain.MediaItem
import com.vayunmathur.uniflix.domain.MediaType
import com.vayunmathur.uniflix.platform.DetailUiState

/** Detail: hero art, overview, seasons/episodes, watchlist, resolve, notes. */
@Composable
fun DetailScreen(
    state: DetailUiState,
    onBack: () -> Unit,
    onSeasonChange: (Int) -> Unit,
    onToggleWatchlist: () -> Unit,
    onPlay: (season: Int, episode: Int) -> Unit,
    onResolve: (season: Int, episode: Int) -> Unit,
    onAddNote: (String, Int) -> Unit,
    onOpenDetail: (MediaItem) -> Unit,
    onOpenAddons: () -> Unit,
    onRetry: () -> Unit = {},
) {
    val detail = state.detail
    AppScaffold(
        title = detail?.title ?: "Detail",
        onNavigateBack = onBack,
    ) { padding ->
        DesktopMaxWidthContainer(Modifier.padding(padding)) {
            if (state.loading && detail == null) {
                CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                return@DesktopMaxWidthContainer
            }
            if (detail == null) {
                ErrorState(
                    title = "Detail failed to load",
                    retryLabel = "Retry",
                    onRetry = onRetry,
                )
                return@DesktopMaxWidthContainer
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AsyncImage(
                    model = TmdbImageUrl.backdrop(detail.backdropPath),
                    contentDescription = detail.title,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth,
                )
                if (detail.mediaType == MediaType.MOVIE) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AsyncImage(
                            model = TmdbImageUrl.poster(detail.posterPath),
                            contentDescription = detail.title,
                            modifier = Modifier.width(120.dp).aspectRatio(2f / 3f),
                            contentScale = ContentScale.Crop,
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(detail.overview, style = MaterialTheme.typography.bodyMedium)
                            detail.year?.let { Text("Year: $it") }
                            detail.runtimeMinutes?.let { Text("Runtime: $it min") }
                        }
                    }
                } else {
                    Text(detail.overview, style = MaterialTheme.typography.bodyMedium)
                    detail.year?.let { Text("Year: $it") }
                    detail.runtimeMinutes?.let { Text("Runtime: $it min") }
                }
                OutlinedButton(onClick = onToggleWatchlist, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.watchlisted) "Remove from watchlist" else "Add to watchlist")
                }
                if (detail.mediaType == MediaType.TV) {
                    SeasonDropdown(
                        seasons = detail.seasons,
                        selectedSeason = state.selectedSeason,
                        onSelect = onSeasonChange,
                    )
                    state.episodes.forEach { episode ->
                        EpisodeCard(episode = episode, onPlay = {
                            onResolve(episode.seasonNumber, episode.episodeNumber)
                            onPlay(episode.seasonNumber, episode.episodeNumber)
                        })
                    }
                } else {
                    Button(
                        onClick = {
                            onResolve(0, 0)
                            onPlay(0, 0)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (state.resolving) "Resolving…" else "Play") }
                }
                if (state.providerMissing) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "No stream addons installed — playback needs at least one addon.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Button(onClick = onOpenAddons) { Text("Open addon manager") }
                        }
                    }
                }
                state.resolveError?.let { error ->
                    Text("Resolve failed: ${friendlyErrorMessage(error)}", style = MaterialTheme.typography.bodySmall)
                }
                state.source?.let { source ->
                    Text("Resolved: ${source.title}", style = MaterialTheme.typography.bodySmall)
                }
                MediaRow("More like this", detail.recommendations, onOpenDetail)
                NoteSection(
                    tmdbId = detail.tmdbId,
                    mediaType = detail.mediaType,
                    onAddNote = onAddNote,
                )
            }
        }
    }
}

@Composable
private fun NoteSection(
    tmdbId: Int,
    mediaType: MediaType,
    onAddNote: (String, Int) -> Unit,
) {
    val context = LocalContext.current
    val repository = remember(context) { UniflixRepository.get(context) }
    val notes by repository.notesFor(tmdbId, mediaType.name.lowercase()).collectAsState(initial = emptyList())
    var draft by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Notes", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("Add a note") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = {
            onAddNote(draft, 0)
            draft = ""
        }, enabled = draft.isNotBlank()) { Text("Save note") }
        notes.forEach { note ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(note.body, modifier = Modifier.padding(12.dp))
            }
        }
    }
}
