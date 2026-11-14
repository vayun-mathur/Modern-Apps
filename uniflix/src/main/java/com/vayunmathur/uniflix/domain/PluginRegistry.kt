package com.vayunmathur.uniflix.domain

/** Outcome of resolving a title through the installed addon set. */
sealed interface ResolutionResult {
    /** A playable source, with the addon that supplied it. */
    data class Success(val source: VideoSource, val addonId: String) : ResolutionResult

    /** No addons installed or none enabled — UI links to the addon manager. */
    data object NoPluginsEnabled : ResolutionResult

    /** Every enabled addon failed; per-addon messages for the error badge. */
    data class AllFailed(val errorsByAddon: Map<String, String>) : ResolutionResult
}

/**
 * Resolution order over installed addons: the default first, then the rest by
 * priority. Pure (no Android) so fallback order is unit-testable.
 *
 * Each entry pairs the addon's stable id with its [StreamProvider]; the
 * registry tries them in order and records per-addon failures.
 */
class PluginRegistry(
    private val providers: List<Pair<String, StreamProvider>>,
) {
    val isEmpty: Boolean get() = providers.isEmpty()

    suspend fun resolve(
        tmdbId: Int,
        mediaType: MediaType,
        season: Int = 0,
        episode: Int = 0,
        server: String? = null,
    ): ResolutionResult {
        if (providers.isEmpty()) return ResolutionResult.NoPluginsEnabled
        val errors = linkedMapOf<String, String>()
        for ((addonId, provider) in providers) {
            val source = runCatching {
                provider.resolve(tmdbId, mediaType, season, episode, server)
            }.getOrElse {
                errors[addonId] = it.message ?: "Unknown error"
                null
            } ?: continue
            return ResolutionResult.Success(source, addonId)
        }
        return ResolutionResult.AllFailed(errors)
    }

    suspend fun alternates(
        tmdbId: Int,
        mediaType: MediaType,
        season: Int = 0,
        episode: Int = 0,
        maxTotal: Int = 20,
    ): List<VideoSource> {
        val out = mutableListOf<VideoSource>()
        for ((_, provider) in providers) {
            if (out.size >= maxTotal) break
            val rest = runCatching {
                provider.alternates(tmdbId, mediaType, season, episode)
            }.getOrDefault(emptyList())
            out += rest.take(maxTotal - out.size)
        }
        return out
    }

    suspend fun servers(): List<String> {
        val out = linkedSetOf<String>()
        for ((_, provider) in providers) {
            runCatching { out += provider.servers() }
        }
        return out.toList()
    }

    /** Shared constants and helpers for PluginRegistry. */
    companion object {
        /**
         * Orders enabled addon entities: the default id first, then priority.
         * Providers are built per-entity by [build]; unbuildable entries (bad
         * manifest cache) are skipped, not fatal.
         */
        fun ordered(
            entities: List<InstalledAddon>,
            defaultDbId: Long?,
            build: (InstalledAddon) -> StreamProvider?,
        ): PluginRegistry {
            val sorted = entities.sortedWith(
                compareBy<InstalledAddon> { if (it.dbId == defaultDbId) 0 else 1 }
                    .thenBy { it.priority }
                    .thenBy { it.dbId },
            )
            return PluginRegistry(sorted.mapNotNull { entity ->
                val provider = runCatching { build(entity) }.getOrNull() ?: return@mapNotNull null
                entity.addonId to provider
            })
        }
    }
}

/** Minimal installed-addon view the registry needs (Room entity maps to this). */
data class InstalledAddon(
    val dbId: Long,
    val addonId: String,
    val baseUrl: String,
    val name: String,
    val version: String = "",
    val priority: Int = 0,
    /** `http` for URL addons, `js` for installable JavaScript plugins. */
    val kind: String = AddonKind.HTTP,
    /** Full plugin file source; only set when [kind] is [AddonKind.JS]. */
    val jsSource: String = "",
)

/** Addon kinds: URL (Stremio HTTP) vs installable JavaScript plugin. */
object AddonKind {
    const val HTTP = "http"
    const val JS = "js"
}
