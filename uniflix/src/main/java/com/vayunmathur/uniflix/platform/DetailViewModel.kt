package com.vayunmathur.uniflix.platform

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.uniflix.data.AddonPrefs
import com.vayunmathur.uniflix.data.AddonStreamProvider
import com.vayunmathur.uniflix.data.JsStreamProvider
import com.vayunmathur.uniflix.data.ContinueWatchingEntry
import com.vayunmathur.uniflix.data.TitleNote
import com.vayunmathur.uniflix.data.TmdbApi
import com.vayunmathur.uniflix.data.UniflixRepository
import com.vayunmathur.uniflix.data.WatchlistEntry
import com.vayunmathur.uniflix.data.watchKey
import com.vayunmathur.uniflix.domain.Episode
import com.vayunmathur.uniflix.domain.AddonKind
import com.vayunmathur.uniflix.domain.InstalledAddon
import com.vayunmathur.uniflix.domain.JsPluginManifest
import com.vayunmathur.uniflix.domain.MediaDetail
import com.vayunmathur.uniflix.domain.MediaType
import com.vayunmathur.uniflix.domain.PluginRegistry
import com.vayunmathur.uniflix.domain.ResolutionResult
import com.vayunmathur.uniflix.domain.StreamProvider
import com.vayunmathur.uniflix.domain.VideoSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Builds the live [PluginRegistry] from installed addons. Public
 * implementations stay pure-HTTP; a private native-plugin overlay can swap
 * this factory to also register its own provider.
 */
fun interface RegistryFactory {
    suspend fun current(repository: UniflixRepository, prefs: AddonPrefs): PluginRegistry

    companion object {
        val HttpAddons = RegistryFactory { repository, prefs ->
            val entities = repository.enabledAddons.first()
            val defaultId = prefs.defaultAddonDbId.first()
            PluginRegistry.ordered(
                entities = entities.map {
                    InstalledAddon(
                        dbId = it.dbId,
                        addonId = it.addonId,
                        baseUrl = it.baseUrl,
                        name = it.name,
                        version = it.version,
                        priority = it.priority,
                    )
                },
                defaultDbId = defaultId,
                // Manifests were validated at install/refresh time; playback
                // uses only the stored base URL + name, no network fetch here.
                build = { installed ->
                    AddonStreamProvider(baseUrl = installed.baseUrl, displayName = installed.name)
                },
            )
        }

        /** Installed addons of both kinds: HTTP URL addons + JS plugins. */
        val AllInstalled = RegistryFactory { repository, prefs ->
            val entities = repository.enabledAddons.first()
            val defaultId = prefs.defaultAddonDbId.first()
            PluginRegistry.ordered(
                entities = entities.map {
                    InstalledAddon(
                        dbId = it.dbId,
                        addonId = it.addonId,
                        baseUrl = it.baseUrl,
                        name = it.name,
                        version = it.version,
                        priority = it.priority,
                        kind = it.kind,
                        jsSource = it.jsSource,
                    )
                },
                defaultDbId = defaultId,
                build = { installed ->
                    if (installed.kind == AddonKind.JS) {
                        runCatching {
                            val manifest = JsPluginManifest.parse(installed.jsSource)
                            JsStreamProvider(manifest = manifest, displayName = installed.name)
                        }.getOrNull()
                    } else {
                        AddonStreamProvider(baseUrl = installed.baseUrl, displayName = installed.name)
                    }
                },
            )
        }
    }
}

/** Detail: TMDB detail + seasons, watchlist toggle, provider resolution, local notes. */
data class DetailUiState(
    val loading: Boolean = false,
    val detail: MediaDetail? = null,
    val episodes: List<Episode> = emptyList(),
    val selectedSeason: Int = 1,
    val watchlisted: Boolean = false,
    val source: VideoSource? = null,
    val resolving: Boolean = false,
    val providerMissing: Boolean = false,
    /** Healthy servers for the in-player server picker. */
    val servers: List<String> = emptyList(),
    /** Server pinned by the picker; null means the provider's default. */
    val selectedServer: String? = null,
    /** Last resolve failure, surfaced in the player instead of a bare spinner. */
    val resolveError: String? = null,
    /** Saved position to start from; Navigation fills this before resolve. */
    val startPositionMs: Long = 0L,
)

class DetailViewModel(app: Application) : AndroidViewModel(app) {
    private val api = TmdbApi()
    private val repository = UniflixRepository.get(app)
    private val addonPrefs = AddonPrefs(app)

    companion object {
        private const val TAG = "DetailViewModel"
    }

    /** Builds the live registry from installed addons; override in tests. */
    var registryFactory: RegistryFactory = RegistryFactory.AllInstalled

    /** Back-compat hook for tests: bypasses installed addons with a fixed set. */
    var streamProviders: List<StreamProvider>? = null

    var state by mutableStateOf(DetailUiState())
        private set

    fun notesFor(tmdbId: Int, mediaType: MediaType) =
        repository.notesFor(tmdbId, mediaType.name.lowercase())

    fun load(mediaType: MediaType, tmdbId: Int) {
        viewModelScope.launch {
            state = state.copy(loading = true)
            runCatching {
                val detail = when (mediaType) {
                    MediaType.MOVIE -> api.movieDetail(tmdbId)
                    MediaType.TV -> api.tvDetail(tmdbId)
                }
                val watchlisted = repository.isWatchlisted(
                    watchKey(tmdbId, mediaType.name.lowercase()),
                )
                val episodes = if (mediaType == MediaType.TV) {
                    val season = detail.seasons.firstOrNull { it.seasonNumber > 0 }?.seasonNumber ?: 1
                    api.seasonEpisodes(tmdbId, season)
                } else {
                    emptyList()
                }
                state = state.copy(
                    loading = false,
                    detail = detail,
                    episodes = episodes,
                    selectedSeason = episodes.firstOrNull()?.seasonNumber ?: 1,
                    watchlisted = watchlisted,
                )
            }.onFailure {
                state = state.copy(loading = false)
                Log.error(TAG, "Detail failed to load: ${it.message}", it)
                AppMessages.show("Detail failed to load: ${it.message}")
            }
        }
    }

    fun selectSeason(mediaType: MediaType, tmdbId: Int, season: Int) {
        viewModelScope.launch {
            runCatching {
                state = state.copy(selectedSeason = season, episodes = api.seasonEpisodes(tmdbId, season))
            }.onFailure {
                AppMessages.show("Season failed to load: ${it.message}")
                Log.error(TAG, "Season failed to load: ${it.message}", it)
            }
        }
    }

    fun toggleWatchlist() {
        val detail = state.detail ?: return
        viewModelScope.launch {
            val key = watchKey(detail.tmdbId, detail.mediaType.name.lowercase())
            if (state.watchlisted) {
                repository.removeWatchlist(key)
                state = state.copy(watchlisted = false)
            } else {
                repository.upsertWatchlist(
                    WatchlistEntry(
                        key = key,
                        tmdbId = detail.tmdbId,
                        mediaType = detail.mediaType.name.lowercase(),
                        title = detail.title,
                        posterPath = detail.posterPath,
                        addedAt = System.currentTimeMillis(),
                    ),
                )
                state = state.copy(watchlisted = true)
            }
        }
    }

    /** Resolve via installed addons: default first, then priority fallbacks. */
    fun resolve(mediaType: MediaType, tmdbId: Int, season: Int = 0, episode: Int = 0) {
        viewModelScope.launch {
            state = state.copy(resolving = true, resolveError = null, providerMissing = false)
            val registry = currentRegistry()
            when (val result = registry.resolve(tmdbId, mediaType, season, episode, state.selectedServer)) {
                is ResolutionResult.Success -> {
                    state = state.copy(resolving = false, source = result.source)
                }
                ResolutionResult.NoPluginsEnabled -> {
                    state = state.copy(resolving = false, providerMissing = true)
                }
                is ResolutionResult.AllFailed -> {
                    val message = result.errorsByAddon.values.firstOrNull() ?: "Unknown error"
                    state = state.copy(resolving = false, resolveError = message)
                    Log.error(TAG, "Stream failed to resolve: $message")
                    AppMessages.show("Stream failed to resolve: $message")
                }
            }
        }
    }

    /** Healthy servers for the in-player server picker. */
    fun loadServers() {
        viewModelScope.launch {
            runCatching {
                state = state.copy(servers = currentRegistry().servers())
            }.onFailure {
                Log.error(TAG, "Servers failed to load: ${it.message}", it)
            }
        }
    }

    private suspend fun currentRegistry(): PluginRegistry {
        val override = streamProviders
        if (override != null) {
            return PluginRegistry(override.mapIndexed { index, provider -> "test-$index" to provider })
        }
        return registryFactory.current(repository, addonPrefs)
    }

    /** Saved resume position for this title/episode, for the player to start from. */
    suspend fun startPosition(mediaType: MediaType, tmdbId: Int, season: Int, episode: Int): Long {
        return runCatching {
            repository.positionFor(watchKey(tmdbId, mediaType.name.lowercase(), season, episode))
        }.getOrDefault(0L)
    }

    /** Pin a server and re-resolve the current title against it. */
    fun selectServer(mediaType: MediaType, tmdbId: Int, season: Int, episode: Int, server: String) {
        if (state.selectedServer == server) return
        state = state.copy(selectedServer = server, source = null)
        resolve(mediaType, tmdbId, season, episode)
    }

    fun addNote(mediaType: MediaType, tmdbId: Int, body: String, score: Int) {
        if (body.isBlank()) return
        viewModelScope.launch {
            repository.upsertNote(
                TitleNote(
                    tmdbId = tmdbId,
                    mediaType = mediaType.name.lowercase(),
                    body = body.trim(),
                    score = score,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun deleteNote(note: TitleNote) {
        viewModelScope.launch { repository.deleteNote(note) }
    }

    fun savePosition(
        mediaType: MediaType,
        tmdbId: Int,
        title: String,
        posterPath: String?,
        season: Int,
        episode: Int,
        positionMs: Long,
        durationMs: Long,
    ) {
        viewModelScope.launch {
            repository.savePosition(
                ContinueWatchingEntry(
                    key = watchKey(tmdbId, mediaType.name.lowercase(), season, episode),
                    tmdbId = tmdbId,
                    mediaType = mediaType.name.lowercase(),
                    title = title,
                    posterPath = posterPath,
                    season = season,
                    episode = episode,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun userLists() = repository.userLists
}
