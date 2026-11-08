package com.vayunmathur.uniflix.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvChip
import com.vayunmathur.library.tv.TvDimens
import com.vayunmathur.library.tv.TvEmptyState
import com.vayunmathur.library.tv.TvGrid
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvLoading
import com.vayunmathur.library.tv.tvContentStart
import com.vayunmathur.library.tv.tvFullWidth
import com.vayunmathur.library.tv.tvGridHeader
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.uniflix.R
import com.vayunmathur.uniflix.data.TmdbImageUrl
import com.vayunmathur.uniflix.data.UniflixRepository
import com.vayunmathur.uniflix.data.watchKey
import com.vayunmathur.uniflix.domain.MediaDetail
import com.vayunmathur.uniflix.domain.MediaType
import com.vayunmathur.uniflix.platform.DetailUiState
import com.vayunmathur.uniflix.platform.DetailViewModel

/**
 * TV detail: two panes, no backdrop. Left (~35%): poster card, Play/Resume
 * primary (routes to Addons when none installed), My List toggle, and the
 * year • rating • runtime • type meta. Right: title, overview, season chips
 * + episodes as a 2-column grid of full-info cards, similar posters.
 */
@Composable
fun TvDetailPage(backStack: NavBackStack<TvRoute>, mediaType: String, tmdbId: Int) {
    val type = mediaTypeOf(mediaType)
    val vm: DetailViewModel = viewModel()
    LaunchedEffect(tmdbId, mediaType) { vm.load(type, tmdbId) }
    val state = vm.state
    val detail = state.detail

    val playFocus = remember { FocusRequester() }
    TvInitialFocus(playFocus, key = detail?.backdropPath ?: tmdbId)

    if (state.loading && detail == null) {
        TvLoading()
        return
    }
    if (detail == null) {
        TvEmptyState(
            title = stringResource(R.string.detail_failed),
            action = { TvButton(text = stringResource(UiR.string.back), onClick = { backStack.pop() }) },
        )
        return
    }
    Row(
        Modifier.fillMaxSize().padding(
            start = tvContentStart(),
            end = TvDimens.SafeHorizontal,
            top = TvDimens.SafeVertical,
            bottom = TvDimens.SafeVertical,
        ),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        DetailPane(
            detail = detail,
            state = vm.state,
            type = type,
            tmdbId = tmdbId,
            mediaType = mediaType,
            backStack = backStack,
            vm = vm,
            playFocus = playFocus,
            modifier = Modifier.fillMaxHeight().weight(0.35f).verticalScroll(rememberScrollState()),
        )
        BodyPane(
            detail = detail,
            state = vm.state,
            type = type,
            tmdbId = tmdbId,
            mediaType = mediaType,
            backStack = backStack,
            vm = vm,
            modifier = Modifier.fillMaxHeight().weight(0.65f),
        )
    }
}

/** Left pane: poster, primary actions, meta lines. */
@Composable
private fun DetailPane(
    detail: MediaDetail,
    state: DetailUiState,
    type: MediaType,
    tmdbId: Int,
    mediaType: String,
    backStack: NavBackStack<TvRoute>,
    vm: DetailViewModel,
    playFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AsyncImage(
            model = TmdbImageUrl.poster(detail.posterPath),
            contentDescription = detail.title,
            modifier = Modifier.width(220.dp).aspectRatio(2f / 3f),
            contentScale = ContentScale.Crop,
        )
        TvDetailPlayButton(
            state = state,
            detail = detail,
            type = type,
            tmdbId = tmdbId,
            mediaType = mediaType,
            backStack = backStack,
            vm = vm,
            playFocus = playFocus,
        )
        TvButton(
            text = stringResource(
                if (state.watchlisted) R.string.in_my_list else R.string.add_to_my_list,
            ),
            onClick = vm::toggleWatchlist,
        )
        if (state.providerMissing) {
            TvButton(
                text = stringResource(R.string.open_addon_manager),
                onClick = {
                    backStack.pop()
                    backStack.setLast(TvRoute.Main(TV_TAB_ADDONS))
                },
            )
        }
        DetailMetaLines(detail = detail)
        state.resolveError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        state.source?.let { source ->
            Text(
                text = source.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Year • rating • runtime • type lines, mirroring the phone detail meta. */
@Composable
private fun DetailMetaLines(detail: MediaDetail) {
    val rating = if (detail.voteAverage > 0) {
        stringResource(R.string.rating_value, detail.voteAverage)
    } else {
        null
    }
    val runtime = detail.runtimeMinutes?.let { stringResource(R.string.runtime_minutes, it) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        listOfNotNull(
            detail.year?.toString(),
            rating,
            runtime,
            typeName(detail.mediaType),
        ).forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Right pane: title, overview, seasons + episode grid, similar posters. */
@Composable
private fun BodyPane(
    detail: MediaDetail,
    state: DetailUiState,
    type: MediaType,
    tmdbId: Int,
    mediaType: String,
    backStack: NavBackStack<TvRoute>,
    vm: DetailViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repository = remember(context) { UniflixRepository.get(context) }
    val entries by repository.continueWatching.collectAsState(initial = emptyList())
    val progressByKey = remember(entries, tmdbId) {
        entries.filter {
            it.tmdbId == tmdbId && it.mediaType == type.name.lowercase()
        }.associate { it.key to resumeProgress(it.positionMs, it.durationMs) }
    }
    val episodesTitle = stringResource(R.string.episodes)
    val similarTitle = stringResource(R.string.more_like_this)
    TvGrid(columns = 6, modifier = modifier) {
        tvFullWidth(key = "title") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = detail.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (detail.overview.isNotBlank()) {
                    Text(
                        text = detail.overview,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (detail.mediaType == MediaType.TV && detail.seasons.isNotEmpty()) {
            tvFullWidth(key = "seasons") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    detail.seasons.filter { it.seasonNumber > 0 }.forEach { season ->
                        TvChip(
                            label = season.name.ifBlank {
                                stringResource(R.string.season_name, season.seasonNumber)
                            },
                            selected = season.seasonNumber == state.selectedSeason,
                            onClick = { vm.selectSeason(type, tmdbId, season.seasonNumber) },
                        )
                    }
                }
            }
            if (state.episodes.isNotEmpty()) {
                tvGridHeader(key = "hdr-episodes", title = episodesTitle)
                episodeItems(
                    key = "episodes",
                    vm = vm,
                    type = type,
                    tmdbId = tmdbId,
                    mediaType = mediaType,
                    backStack = backStack,
                    progressByKey = progressByKey,
                )
            }
        }
        if (detail.recommendations.isNotEmpty()) {
            tvGridHeader(key = "hdr-similar", title = similarTitle)
            items(detail.recommendations, key = ::mediaKey) { item ->
                MediaPosterCard(
                    item = item,
                    onOpen = {
                        backStack.add(
                            TvRoute.Detail(item.mediaType.name.lowercase(), item.tmdbId),
                        )
                    },
                )
            }
        }
        tvFullWidth(key = "end-anchor") {}
    }
}

private fun LazyGridScope.episodeItems(
    key: String,
    vm: DetailViewModel,
    type: MediaType,
    tmdbId: Int,
    mediaType: String,
    backStack: NavBackStack<TvRoute>,
    progressByKey: Map<String, Float?>,
) {
    items(vm.state.episodes, key = { "$key-${it.episodeNumber}" }, span = { GridItemSpan(3) }) { episode ->
        TvEpisodeCard(
            episode = episode,
            progress = progressByKey[
                watchKey(
                    tmdbId, type.name.lowercase(),
                    episode.seasonNumber, episode.episodeNumber,
                ),
            ],
            onPlay = {
                vm.resolve(type, tmdbId, episode.seasonNumber, episode.episodeNumber)
                backStack.add(
                    TvRoute.Watch(
                        mediaType, tmdbId,
                        episode.seasonNumber, episode.episodeNumber,
                    ),
                )
            },
        )
    }
}
