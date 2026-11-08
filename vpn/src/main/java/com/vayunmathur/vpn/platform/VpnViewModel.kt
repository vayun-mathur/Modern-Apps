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
import com.vayunmathur.vpn.data.toEntity
import com.vayunmathur.vpn.data.toModel
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
import java.io.IOException

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
        viewModelScope.launch(Dispatchers.IO) { dao.delete(config.toEntity()) }
    }

    fun upsert(config: VpnConfig, onSaved: ((Long) -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val newId = dao.upsert(config.toEntity())
            onSaved?.let { withContext(Dispatchers.Main) { it(newId) } }
        }
    }

    /**
     * The only way to create a new tunnel — open a WireGuard .conf file.
     * Parses [Interface] + [Peer] using the same tiny wg-quick parser the Rust side (gotatun/mullvad) expects.
     * Derives public key via Rust X25519 if native .so is loaded; otherwise stores empty pubkey.
     */
    fun importFromUri(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                try {
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    context.contentResolver.takePersistableUriPermission(uri, flags)
                } catch (expected: SecurityException) {
                    Log.status("VpnVM", "persist permission denied for $uri", expected)
                }
                val text = context.contentResolver.openInputStream(uri)?.use {
                    it.bufferedReader().readText()
                } ?: run {
                    _status.value = "Failed to read file"
                    return@launch
                }
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

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val POLL_RUNNING_MS = 500L
    }
}

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
