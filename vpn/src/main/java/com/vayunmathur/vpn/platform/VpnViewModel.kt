package com.vayunmathur.vpn.platform

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import com.vayunmathur.library.log.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vayunmathur.vpn.data.AppUsageSummary
import com.vayunmathur.vpn.data.ConnectionLogDao
import com.vayunmathur.vpn.data.DomainBytesSummary
import com.vayunmathur.vpn.data.DomainCountSummary
import com.vayunmathur.vpn.data.VpnConfig
import com.vayunmathur.vpn.data.VpnConfigDao
import com.vayunmathur.vpn.data.WgConfigParser
import com.vayunmathur.vpn.data.endpointHostOf
import com.vayunmathur.vpn.data.MavpnBundleStore
import com.vayunmathur.vpn.data.bundleIdFor
import com.vayunmathur.vpn.data.toEntity
import com.vayunmathur.vpn.data.toGeoServerOrNull
import com.vayunmathur.vpn.data.toModel
import com.vayunmathur.vpn.data.toVpnConfigs
import com.vayunmathur.vpn.domain.GeoCountry
import com.vayunmathur.vpn.domain.MavpnBundle
import com.vayunmathur.vpn.domain.MavpnParser
import com.vayunmathur.vpn.domain.buildGeoCountries
import com.vayunmathur.vpn.service.AppResolver
import com.vayunmathur.vpn.service.ConnectionTracker
import com.vayunmathur.vpn.service.VpnTunnelService
import com.vayunmathur.vpn.util.VpnNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.IOException

/** View model for the vpn screen: holds UI state and handles its actions. */
class VpnViewModel(
    application: Application,
    private val dao: VpnConfigDao,
    private val logDao: ConnectionLogDao,
) : AndroidViewModel(application) {

    val configs: StateFlow<List<VpnConfig>> =
        dao.flowAll().map { list -> list.map { it.toModel() } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val _connectingId = MutableStateFlow<Long?>(null)
    val connectingId: StateFlow<Long?> = _connectingId.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    // --- Logging leaderboards ---
    val topAppsFlow: StateFlow<List<AppUsageSummary>> = logDao.flowTopApps()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val domainsByCountFlow: StateFlow<List<DomainCountSummary>> = logDao.flowDomainsByCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val domainsByBytesFlow: StateFlow<List<DomainBytesSummary>> = logDao.flowDomainsByBytes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /** Providers of imported `.mavpn` bundles, from the same config list the tunnels tab shows. */
    val importedProviders: StateFlow<List<String>> =
        configs.map { list -> list.map { it.provider }.filter { it.isNotBlank() }.distinct().sorted() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /** Imported servers grouped countries → cities → servers for the geo picker routes. */
    val geoCountries: StateFlow<List<GeoCountry>> =
        configs.map { list -> buildGeoCountries(list.mapNotNull { it.toGeoServerOrNull() }) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    init {
        viewModelScope.launch {
            while (true) {
                delay(POLL_RUNNING_MS)
                if (!VpnTunnelService.isRunning) _connectingId.value = null
            }
        }
        backfillAppNames()
    }

    /**
     * Rows logged before the app could see other packages stored a bare UID as their label.
     * Now that they resolve, give those rows their real name instead of making the user wipe logs.
     */
    private fun backfillAppNames() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val resolver = AppResolver(getApplication<Application>())
                for (uid in logDao.unnamedUids()) {
                    val app = resolver.resolveUid(uid)
                    val pkg = app.packageName ?: continue
                    logDao.nameUid(uid, pkg, app.appLabel)
                }
            }.onFailure { Log.status("VpnVM", "backfillAppNames", it) }
        }
    }

    /**
     * Connects [config], requesting system VPN consent first when needed.
     *
     * When [VpnService.prepare] returns a consent intent, the tunnel cannot
     * start yet: the intent is handed to [onPermissionNeeded] so the caller
     * can launch it through the Activity Result API, and the caller retries
     * this same method after the user grants consent (at which point
     * prepare() returns null and the tunnel starts directly).
     */
    fun startVpn(config: VpnConfig, onPermissionNeeded: (Intent) -> Unit) {
        val ctx = getApplication<Application>()
        val permissionIntent = VpnService.prepare(ctx)
        if (permissionIntent != null) {
            _status.value = "Granting VPN permission…"
            onPermissionNeeded(permissionIntent)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            dao.touch(config.id, System.currentTimeMillis())
            withContext(Dispatchers.Main) {
                val svcIntent = Intent(ctx, VpnTunnelService::class.java).apply {
                    action = VpnTunnelService.ACTION_CONNECT
                    putExtra(VpnTunnelService.EXTRA_CONFIG_JSON, Json.encodeToString(config))
                }
                _connectingId.value = config.id
                ctx.startService(svcIntent)
                _status.value = "Connecting to ${config.name}…"
            }
        }
    }

    fun onVpnPermissionDenied() {
        _status.value = "VPN permission denied"
    }

    fun stopVpn() {
        val ctx = getApplication<Application>()
        val svcIntent = Intent(ctx, VpnTunnelService::class.java).apply {
            action = VpnTunnelService.ACTION_DISCONNECT
        }
        ctx.startService(svcIntent)
        _connectingId.value = null
        _status.value = "Disconnected"
    }

    fun delete(config: VpnConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            // The database row goes first and is authoritative: the bundle's
            // files are removed only once no config references the bundle any
            // more, and a failed file cleanup never undoes the delete.
            dao.delete(config.toEntity())
            if (config.bundleId.isBlank()) return@launch
            val bundleStillUsed = dao.getAll().any { it.bundleId == config.bundleId }
            if (!bundleStillUsed) {
                runCatching {
                    MavpnBundleStore.delete(getApplication(), config.bundleId)
                }.onFailure { Log.status("VpnVM", "bundle file cleanup failed", it) }
            }
        }
    }

    fun upsert(config: VpnConfig, onSaved: ((Long) -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val newId = dao.upsert(config.toEntity())
            onSaved?.let { withContext(Dispatchers.Main) { it(newId) } }
        }
    }

    /**
     * Creates tunnels from a picked file: a `.mavpn` bundle (ZIP magic or
     * `.mavpn` name) or a single WireGuard `.conf`.
     * Parses [Interface] + [Peer] using the same tiny wg-quick parser the Rust side (gotatun/mullvad) expects.
     * Derives public key via Rust X25519 if native .so is loaded; otherwise stores empty pubkey.
     */
    fun importFromUri(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Only the schemes the import contract can legitimately deliver:
                // SAF/OpenDocument and ACTION_VIEW both hand over content://,
                // and file:// from a file manager (see the manifest VIEW filter,
                // which declares content/file only). Anything else is rejected
                // before a stream is opened.
                if (uri.scheme != "content" && uri.scheme != "file") {
                    _status.value = "Import failed: unsupported scheme ${uri.scheme}"
                    return@launch
                }
                try {
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    context.contentResolver.takePersistableUriPermission(uri, flags)
                } catch (expected: SecurityException) {
                    Log.status("VpnVM", "persist permission denied for $uri", expected)
                }
                val bytes = context.contentResolver.openInputStream(uri)?.use { readBounded(it) } ?: run {
                    _status.value = "Failed to read file"
                    return@launch
                }
                if (isBundle(bytes, uri)) {
                    importBundle(context, bytes)
                    return@launch
                }
                val text = bytes.decodeToString()
                val imp = WgConfigParser.parse(text).getOrElse {
                    _status.value = "Import failed: ${it.message}"
                    return@launch
                }
                val derivedPub = runCatching {
                    VpnNative.derivePublicKey(imp.privateKey)
                }.getOrNull() ?: ""
                val nameFromFile = uri.lastPathSegment
                    ?.substringBeforeLast('.')
                    ?.takeIf { it.isNotBlank() }
                    ?: endpointHostOf(imp.peerEndpoint).ifBlank { "Imported Tunnel" }
                val model = VpnConfig(
                    name = nameFromFile,
                    privateKey = imp.privateKey,
                    publicKey = derivedPub,
                    address = imp.address,
                    dns = imp.dns,
                    mtu = imp.mtu,
                    peerPublicKey = imp.peerPublicKey,
                    peerPresharedKey = imp.peerPresharedKey,
                    peerAllowedIPs = imp.peerAllowedIps,
                    peerEndpoint = imp.peerEndpoint,
                    peerKeepalive = imp.peerKeepalive,
                )
                dao.upsert(model.toEntity())
                _status.value = "Imported ${model.name} from .conf"
            } catch (expected: IOException) {
                Log.error("VpnVM", "importFromUri", expected)
                _status.value = "Import failed: ${expected.message}"
            } catch (expected: SecurityException) {
                Log.error("VpnVM", "importFromUri", expected)
                _status.value = "Import failed: ${expected.message}"
            } catch (expected: IllegalStateException) {
                Log.error("VpnVM", "importFromUri", expected)
                _status.value = "Import failed: ${expected.message}"
            }
        }
    }

    private fun isBundle(bytes: ByteArray, uri: Uri): Boolean =
        uri.lastPathSegment?.endsWith(".mavpn", ignoreCase = true) == true ||
            (bytes.size >= ZIP_MAGIC.size && ZIP_MAGIC.indices.all { bytes[it] == ZIP_MAGIC[it] })

    /**
     * Validates a `.mavpn` bundle through the existing WireGuard parser,
     * stores it in app-private storage, and upserts every server as a normal
     * selectable config. Re-importing the same bundle updates its servers in
     * place (matched by bundle id + server id) instead of duplicating them.
     * Config contents are never logged; failures report only the reason.
     */
    private suspend fun importBundle(context: Context, bytes: ByteArray) {
        val bundle = MavpnParser.parse(bytes, WgConfigParser::parse).getOrElse {
            _status.value = "Import failed: ${it.message}"
            return
        }
        val bundleId = bundleIdFor(bundle.manifest.provider, bundle.servers.map { it.entry.id })
        runCatching {
            MavpnBundleStore.save(context, bundleId, bundle, manifestJson(bundle))
        }.onFailure {
            _status.value = "Import failed: could not store bundle"
            return
        }
        val existing = dao.getAll().map { it.toModel() }
        val models = bundle.toVpnConfigs(bundleId, existing) { privateKey ->
            runCatching { VpnNative.derivePublicKey(privateKey) }.getOrNull() ?: ""
        }
        models.forEach { dao.upsert(it.toEntity()) }
        _status.value = "Imported ${models.size} servers from ${bundle.manifest.provider}"
    }

    private fun manifestJson(bundle: MavpnBundle<*>): String =
        buildJsonObject {
            put("format", bundle.manifest.format)
            put("provider", bundle.manifest.provider)
            putJsonArray("servers") {
                bundle.manifest.servers.forEach { server ->
                    addJsonObject {
                        put("id", server.id)
                        put("country", server.country)
                        put("city", server.city)
                        put("file", server.file)
                        server.endpoint?.let { put("endpoint", it) }
                    }
                }
            }
        }.toString()

    fun deleteAllLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            logDao.deleteAll()
            ConnectionTracker.getOrCreate().clear()
        }
        _status.value = "Logs cleared"
    }

    fun clearStatus() { _status.value = null }

    @Composable
    fun configState(id: Long, default: () -> VpnConfig = { VpnConfig() }): VpnConfig {
        val list by configs.collectAsStateWithLifecycle()
        return list.firstOrNull { it.id == id } ?: default()
    }

    /** Shared constants and helpers for VpnViewModel. */
    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val POLL_RUNNING_MS = 500L

        /** WireGuard configs and .mavpn bundles are small; anything larger is not one. */
        private const val MAX_IMPORT_BYTES = 1_048_576
        private val ZIP_MAGIC = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4)

        /**
         * Reads at most [MAX_IMPORT_BYTES] from [input]; one byte over throws
         * [IOException], which the caller reports through its existing
         * import-failed path, instead of buffering an unbounded stream.
         */
        private fun readBounded(input: java.io.InputStream): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (out.size() + read > MAX_IMPORT_BYTES) {
                    throw IOException("File too large (over $MAX_IMPORT_BYTES bytes)")
                }
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }
}

/** Creates vpn view model instances. */
class VpnViewModelFactory(
    private val application: Application,
    private val configDao: VpnConfigDao,
    private val logDao: ConnectionLogDao,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(VpnViewModel::class.java))
        return VpnViewModel(application, configDao, logDao) as T
    }
}
