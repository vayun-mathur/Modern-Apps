package com.vayunmathur.uniflix.platform

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.ui.friendlyError
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.uniflix.data.AddonEntity
import com.vayunmathur.uniflix.data.AddonPrefs
import com.vayunmathur.uniflix.data.AddonStreamProvider
import com.vayunmathur.uniflix.data.JsStreamProvider
import com.vayunmathur.uniflix.data.NetworkClientBridge
import com.vayunmathur.uniflix.data.UniflixRepository
import com.vayunmathur.uniflix.domain.AddonKind
import com.vayunmathur.uniflix.domain.JsPluginManifest
import com.vayunmathur.uniflix.domain.ManifestError
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One addon row in the manager: entity plus whether it is the default. */
data class AddonRow(
    val entity: AddonEntity,
    val isDefault: Boolean,
)

private const val TAG = "AddonsViewModel"

/** Addon manager state: install by URL, enable/disable, default, priority, refresh. */
class AddonsViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = UniflixRepository.get(app)
    private val prefs = AddonPrefs(app)

    var adding by mutableStateOf(false)
        private set
    var refreshing by mutableStateOf(false)
        private set

    val addons = combine(repository.addons, prefs.defaultAddonDbId) { entities, defaultId ->
        entities.map { AddonRow(it, it.dbId == defaultId) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Fetches the manifest at [url], validates it, and stores the addon. */
    fun addByUrl(url: String) {
        if (url.isBlank()) return
        viewModelScope.launch {
            adding = true
            runCatching {
                val (base, manifest) = AddonStreamProvider.fetchManifest(url)
                val existing = repository.addonByManifestUrl("$base/manifest.json")
                val count = repository.addonCount()
                repository.upsertAddon(
                    (existing ?: AddonEntity(
                        manifestUrl = "$base/manifest.json",
                        baseUrl = base,
                        addonId = manifest.id,
                        name = manifest.name,
                        priority = count,
                    )).copy(
                        baseUrl = base,
                        addonId = manifest.id,
                        name = manifest.name,
                        version = manifest.version,
                        lastError = null,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                AppMessages.show("Added ${manifest.name}")
            }.onFailure {
                Log.error(TAG, "Add addon failed: ${it.message}", it)
                AppMessages.show(getApplication<Application>().friendlyError(it))
            }
            adding = false
        }
    }

    /** Validates [source] as a JS plugin and stores it. */
    fun addJsPlugin(source: String) {
        if (source.isBlank()) return
        viewModelScope.launch {
            adding = true
            runCatching {
                val manifest = JsPluginManifest.parse(source)
                val key = "js:${manifest.id}"
                val existing = repository.addonByManifestUrl(key)
                val count = repository.addonCount()
                repository.upsertAddon(
                    (existing ?: AddonEntity(
                        manifestUrl = key,
                        baseUrl = "",
                        addonId = manifest.id,
                        name = manifest.name,
                        priority = count,
                        kind = AddonKind.JS,
                    )).copy(
                        addonId = manifest.id,
                        name = manifest.name,
                        version = manifest.version,
                        kind = AddonKind.JS,
                        jsSource = source,
                        lastError = null,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                AppMessages.show("Added ${manifest.name}")
            }.onFailure {
                Log.error(TAG, "Add plugin failed: ${it.message}", it)
                AppMessages.show(getApplication<Application>().friendlyError(it))
            }
            adding = false
        }
    }

    fun remove(row: AddonRow) {
        viewModelScope.launch {
            repository.deleteAddon(row.entity.dbId)
            if (row.isDefault) prefs.setDefaultAddonDbId(null)
        }
    }

    fun setEnabled(row: AddonRow, enabled: Boolean) {
        viewModelScope.launch {
            repository.setAddonEnabled(row.entity.dbId, enabled)
        }
    }

    fun setDefault(row: AddonRow) {
        viewModelScope.launch {
            prefs.setDefaultAddonDbId(row.entity.dbId)
        }
    }

    fun clearDefault() {
        viewModelScope.launch { prefs.setDefaultAddonDbId(null) }
    }

    /** Swaps priorities with the neighbor above (lower number resolves first). */
    fun moveUp(rows: List<AddonRow>, row: AddonRow) {
        val index = rows.indexOfFirst { it.entity.dbId == row.entity.dbId }
        if (index <= 0) return
        swap(rows[index - 1], row)
    }

    /** Swaps priorities with the neighbor below. */
    fun moveDown(rows: List<AddonRow>, row: AddonRow) {
        val index = rows.indexOfFirst { it.entity.dbId == row.entity.dbId }
        if (index < 0 || index >= rows.lastIndex) return
        swap(row, rows[index + 1])
    }

    private fun swap(upper: AddonRow, lower: AddonRow) {
        viewModelScope.launch {
            repository.swapAddonPriorities(upper.entity, lower.entity)
        }
    }

    /** Re-fetches every installed manifest; per-addon failures land on that row's badge. */
    fun refreshAll() {
        viewModelScope.launch {
            refreshing = true
            val rows = addons.first()
            var failures = 0
            for (row in rows) {
                if (row.entity.kind == AddonKind.JS) continue
                runCatching {
                    val (base, manifest) = AddonStreamProvider.fetchManifest(row.entity.manifestUrl)
                    repository.upsertAddon(
                        row.entity.copy(
                            baseUrl = base,
                            addonId = manifest.id,
                            name = manifest.name,
                            version = manifest.version,
                            lastError = null,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                }.onFailure {
                    failures++
                    repository.setAddonError(row.entity.dbId, getApplication<Application>().friendlyError(it))
                }
            }
            refreshing = false
            val message = if (failures == 0) {
                "Refreshed ${rows.size} addons"
            } else {
                "$failures of ${rows.size} addons failed to refresh"
            }
            AppMessages.show(message)
        }
    }

    /** Re-fetches the manifest; updates identity, clears or sets [AddonEntity.lastError]. */
    fun refreshManifest(row: AddonRow) {
        if (row.entity.kind == AddonKind.JS) {
            AppMessages.show("Re-import the .js file to update ${row.entity.name}")
            return
        }
        viewModelScope.launch {
            refreshing = true
            runCatching {
                val (base, manifest) = AddonStreamProvider.fetchManifest(row.entity.manifestUrl)
                repository.upsertAddon(
                    row.entity.copy(
                        baseUrl = base,
                        addonId = manifest.id,
                        name = manifest.name,
                        version = manifest.version,
                        lastError = null,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                AppMessages.show("Refreshed ${manifest.name}")
            }.onFailure {
                val message = getApplication<Application>().friendlyError(it)
                repository.setAddonError(row.entity.dbId, message)
                AppMessages.show(message)
            }
            refreshing = false
        }
    }

    /** Re-resolves identity without changing priority/enabled; reports reachability. */
    fun testAddon(row: AddonRow) {
        if (row.entity.kind == AddonKind.JS) {
            testJsPlugin(row)
            return
        }
        viewModelScope.launch {
            runCatching {
                AddonStreamProvider.fetchManifest(row.entity.manifestUrl)
                repository.setAddonError(row.entity.dbId, null)
                AppMessages.show("${row.entity.name} is reachable")
            }.onFailure {
                val message = getApplication<Application>().friendlyError(it)
                repository.setAddonError(row.entity.dbId, message)
                if (it !is ManifestError) Log.error(TAG, "Test addon failed: ${it.message}", it)
                AppMessages.show(message)
            }
        }
    }

    /** Runs the plugin's `servers()` as a health probe; failures land on the row badge. */
    private fun testJsPlugin(row: AddonRow) {
        viewModelScope.launch {
            runCatching {
                val manifest = JsPluginManifest.parse(row.entity.jsSource)
                val provider = JsStreamProvider(
                    manifest = manifest,
                    displayName = row.entity.name,
                    configJson = row.entity.configJson,
                    network = NetworkClientBridge,
                )
                val servers = provider.servers()
                repository.setAddonError(row.entity.dbId, null)
                val detail = if (servers.isEmpty()) "no servers listed" else "${servers.size} server(s)"
                AppMessages.show("${row.entity.name} works ($detail)")
            }.onFailure {
                val message = getApplication<Application>().friendlyError(it)
                repository.setAddonError(row.entity.dbId, message)
                Log.error(TAG, "Test plugin failed: ${it.message}", it)
                AppMessages.show(message)
            }
        }
    }

    fun updateConfig(dbId: Long, config: String) {
        viewModelScope.launch {
            repository.setAddonConfig(dbId, config)
        }
    }
}
