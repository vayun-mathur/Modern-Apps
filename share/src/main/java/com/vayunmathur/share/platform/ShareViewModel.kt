package com.vayunmathur.share.platform

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.vayunmathur.library.log.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.share.R
import com.vayunmathur.share.platform.airdrop.AirDropSender
import com.vayunmathur.share.platform.discovery.DiscoverySource
import com.vayunmathur.share.platform.discovery.NearbyDevice
import com.vayunmathur.share.platform.receive.ShareReceiveController
import com.vayunmathur.share.platform.transfer.ShareTransferService
import com.vayunmathur.share.network.transport.Connection
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ShareVM"

/**
 * ViewModel for the Share app's **send** flow.
 *
 * Everyone-mode only (no Google account, so no contact certificates):
 *  - Discovery: browse `_FC9F5ED42C8A._tcp` (resolves host/port) and scan `0xFEF3`, then merge
 *    by endpointId. Connecting needs host/port, which only the mDNS leg supplies.
 *
 * Owns no session state. The `TcpTransport` and the discovery managers come from
 * [ShareReceiveController], which outlives every Activity: one endpoint id and one
 * endpoint-info blob are shared by the BLE advertisement, the mDNS record and
 * `CONNECTION_REQUEST`, and a device that advertises one identity and dials out under another
 * is hung up on (`p000\each.java:2092-2097`).
 */
class ShareViewModel(
    application: Application,
) : AndroidViewModel(application), ShareActions {

    private val appContext: Context get() = getApplication()

    private val nsd get() = ShareReceiveController.nsd(appContext)
    private val ble get() = ShareReceiveController.ble(appContext)
    private val transport get() = ShareReceiveController.transport(appContext)
    private val airDrop get() = ShareReceiveController.airDrop(appContext)

    val localName: String get() = ShareReceiveController.localName

    fun setLocalName(name: String) = ShareReceiveController.setLocalName(appContext, name)

    // --- Send: discovery + outgoing URIs --------------------------------

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<NearbyDevice>>(emptyList())

    private val _outgoingUris = MutableStateFlow<List<Uri>>(emptyList())
    val outgoingUris: StateFlow<List<Uri>> = _outgoingUris.asStateFlow()

    private val _outgoingDisplayNames = MutableStateFlow<List<String>>(emptyList())

    private val _activeConnection = MutableStateFlow<Connection?>(null)

    /** Held for the whole dial-and-send, so one transfer cannot be started twice. */
    private val connecting = java.util.concurrent.atomic.AtomicBoolean(false)

    val sendUiState: StateFlow<SendUiState> =
        combine(
            _discoveredDevices,
            _isScanning,
            _activeConnection,
            _outgoingUris,
            _outgoingDisplayNames,
        ) { devices, scanning, active, uris, names ->
            SendUiState(
                discoveredDevices = devices,
                isScanning = scanning,
                outgoingUris = uris,
                outgoingDisplayNames = names,
                activeConnection = active,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SendUiState())

    private var scanNsdJob: Job? = null
    private var scanBleJob: Job? = null
    private var scanAirDropJob: Job? = null

    // Broad catches are deliberate: discovery flows and the transport throw
    // undocumented RuntimeExceptions (not just IOException), which read as
    // "scan/connect failed" rather than a ViewModel crash.
    @Suppress("TooGenericExceptionCaught")
    override fun startScan() {
        if (_isScanning.value) return
        _isScanning.value = true
        _discoveredDevices.value = emptyList()
        nsd.clearDiscoveredDevices()
        scanNsdJob = viewModelScope.launch {
            try {
                // Browse _FC9F5ED42C8A._tcp for WIFI_LAN endpoints (host/port + name).
                nsd.discover().collect { dev ->
                    _discoveredDevices.value = mergeDevice(_discoveredDevices.value, dev)
                }
            } catch (e: Exception) {
                Log.status(TAG, "NSD discover error", e)
            }
        }
        scanBleJob = viewModelScope.launch {
            try {
                // Scan GATT 0xFEF3 for Nearby Connections BleAdvertisements.
                ble.scan().collect { dev ->
                    _discoveredDevices.value = mergeDevice(_discoveredDevices.value, dev)
                }
            } catch (e: Exception) {
                Log.status(TAG, "BLE scan error", e)
            }
        }
        scanAirDropJob = viewModelScope.launch {
            try {
                // _airdrop._tcp over Mosey's AWDL link when the HAL exists, else over Wi-Fi.
                airDrop.browse().collect { dev ->
                    _discoveredDevices.value = mergeDevice(_discoveredDevices.value, dev)
                }
            } catch (e: Exception) {
                Log.status(TAG, "AirDrop browse error", e)
            }
        }
    }

    private fun mergeDevice(current: List<NearbyDevice>, incoming: NearbyDevice): List<NearbyDevice> {
        val idx = current.indexOfFirst { it.endpointId == incoming.endpointId }
        return if (idx >= 0) {
            val existing = current[idx]
            // Both legs now carry the peer's advertised name, but the mDNS record is the
            // one that also supplies host/port, so prefer it and treat BLE as a tiebreak.
            val bestName = when {
                incoming.source == DiscoverySource.Nsd && incoming.endpointName.isNotBlank() -> incoming.endpointName
                existing.source == DiscoverySource.Nsd && existing.endpointName.isNotBlank() -> existing.endpointName
                else -> incoming.endpointName.ifBlank { existing.endpointName }
            }
            val merged = existing.copy(
                endpointName = bestName,
                serviceId = incoming.serviceId ?: existing.serviceId,
                host = incoming.host ?: existing.host,
                port = incoming.port ?: existing.port,
                source = if (incoming.source != existing.source) DiscoverySource.Both else existing.source,
                serviceName = incoming.serviceName ?: existing.serviceName,
                extra = incoming.extra ?: existing.extra,
            )
            current.toMutableList().also { it[idx] = merged }
        } else {
            current + incoming
        }
    }

    override fun stopScan() {
        scanNsdJob?.cancel()
        scanBleJob?.cancel()
        scanAirDropJob?.cancel()
        scanNsdJob = null
        scanBleJob = null
        scanAirDropJob = null
        nsd.stopDiscovery()
        ble.stopScan()
        _isScanning.value = false
    }

    @Suppress("TooGenericExceptionCaught")
    override fun connectToDevice(device: NearbyDevice) {
        if (device.source == DiscoverySource.AirDrop) {
            sendViaAirDrop(device)
            return
        }
        val host = device.host
        val port = device.port
        if (host == null || port == null) {
            Log.status(TAG, "cannot connect to ${device.endpointName}: no host/port (needs the mDNS browse)")
            return
        }
        val uris = _outgoingUris.value
        if (uris.isEmpty()) {
            // Connecting with nothing staged streams nothing and idles until the peer's 60 s
            // accept timeout, which reads as a broken app rather than an empty selection.
            AppMessages.show(appContext.getString(R.string.share_nothing_selected))
            return
        }
        // A second tap while the first dial is still in flight would open another socket and
        // another native session, leak the first, and leave the peer with two half-sessions.
        if (!connecting.compareAndSet(false, true)) return
        // A scan competes with the transfer for the same radios, and the device is chosen.
        stopScan()
        viewModelScope.launch {
            try {
                // A rename never re-advertised while hidden, so refresh the identity the
                // CONNECTION_REQUEST will carry before dialling.
                ShareReceiveController.refreshLocalIdentity(appContext)
                val conn = transport.connect(host, port)
                _activeConnection.value = conn
                ShareTransferService.startSendMode(appContext)
                sendUrisOver(conn, uris)
            } catch (e: Exception) {
                Log.status(TAG, "connect to ${device.endpointName} failed", e)
            } finally {
                connecting.set(false)
            }
        }
    }

    override fun setOutgoingUris(uris: List<Uri>) {
        _outgoingUris.value = uris
        viewModelScope.launch(Dispatchers.IO) {
            _outgoingDisplayNames.value = uris.map { resolveDisplayName(appContext, it) ?: it.toString() }
        }
    }

    /** Entry from the share-sheet popup's intent — handles ACTION_SEND / SEND_MULTIPLE. */
    fun handleShareIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                }
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (uri != null) setOutgoingUris(listOf(uri))
                else if (!text.isNullOrBlank()) stageTextAsFile(text)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    // The deprecated overload is erased-generic: any app can send an
                    // ArrayList<String> in this extra, and the first element use would
                    // throw ClassCastException. Keep only real Uris.
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                        ?.filterIsInstance<Uri>()
                }
                if (!uris.isNullOrEmpty()) setOutgoingUris(uris)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun stageTextAsFile(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val f = File(appContext.cacheDir, "share_send/shared_text.txt")
                f.parentFile?.mkdirs()
                f.writeText(text)
                val uri = Uri.fromFile(f)
                withContext(Dispatchers.Main) { setOutgoingUris(listOf(uri)) }
            } catch (e: Exception) {
                Log.status(TAG, "stageTextAsFile failed", e)
            }
        }
    }

    /**
     * AirDrop has no long-lived session to show, so the outcome is reported as a message: Ask,
     * then a single Upload, then done.
     */
    private fun sendViaAirDrop(device: NearbyDevice) {
        val uris = _outgoingUris.value
        if (uris.isEmpty()) {
            AppMessages.show(appContext.getString(R.string.share_nothing_selected))
            return
        }
        if (!connecting.compareAndSet(false, true)) return
        stopScan()
        viewModelScope.launch {
            val files = withContext(Dispatchers.IO) { uris.mapNotNull { uriToTempFile(appContext, it) } }
            try {
                if (files.isEmpty()) return@launch
                ShareTransferService.startSendMode(appContext)
                AppMessages.show(appContext.getString(R.string.share_airdrop_waiting, device.endpointName))
                val result = airDrop.send(device, files) { _, _ -> }
                val message = when (result) {
                    AirDropSender.Result.Sent -> appContext.getString(R.string.share_airdrop_sent, device.endpointName)
                    AirDropSender.Result.Declined ->
                        appContext.getString(R.string.share_airdrop_declined, device.endpointName)
                    is AirDropSender.Result.Failed ->
                        appContext.getString(R.string.share_airdrop_failed, result.reason)
                }
                AppMessages.show(message)
            } finally {
                files.forEach { it.delete() }
                ShareTransferService.stop(appContext)
                connecting.set(false)
            }
        }
    }

    private suspend fun sendUrisOver(conn: Connection, uris: List<Uri>) = withContext(Dispatchers.IO) {
        val files = uris.mapNotNull { uri -> uriToTempFile(appContext, uri) }
        if (files.isEmpty()) return@withContext
        try {
            transport.sendFiles(conn, files)
        } finally {
            // The staging copies exist only for the duration of the send.
            files.forEach { it.delete() }
        }
    }

    override fun clearOutgoing() {
        _outgoingUris.value = emptyList()
        _outgoingDisplayNames.value = emptyList()
    }

    override fun disconnect(connection: Connection) {
        viewModelScope.launch {
            transport.disconnect(connection)
            if (_activeConnection.value === connection) _activeConnection.value = null
            // The send is over; the service stays up only if receiving still wants it.
            ShareTransferService.stop(appContext)
        }
    }

    override fun onCleared() {
        // Only the scan is this ViewModel's to stop: the transport, the discovery managers and
        // every live session belong to ShareReceiveController and must outlive the Activity.
        stopScan()
    }
}

/** Share View Model Factory: a application implementation for share view model factory. */
class ShareViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ShareViewModel::class.java)) {
            "Unknown ViewModel class $modelClass"
        }
        return ShareViewModel(application) as T
    }
}

// Broad catches are deliberate: content-resolver reads throw undocumented
// RuntimeExceptions (not just IOException/SecurityException) on bad providers.
@Suppress("TooGenericExceptionCaught")
internal fun resolveDisplayName(context: Context, uri: Uri): String? {
    if (uri.scheme == "file") return File(uri.path ?: "").name.takeIf { it.isNotBlank() }
    return try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }
}

/** Largest shared file [uriToTempFile] will stage (100 MB). */
internal const val MAX_SHARED_FILE_BYTES = 100L * 1024 * 1024

@Suppress("TooGenericExceptionCaught")
internal fun uriToTempFile(context: Context, uri: Uri): File? {
    return try {
        val name = resolveDisplayName(context, uri) ?: "share_file_${System.currentTimeMillis()}"
        val safeName = name.replace("/", "_").replace("\\", "_")
        val dest = File(context.cacheDir, "share_send/$safeName")
        dest.parentFile?.mkdirs()
        if (uri.scheme == "file") {
            val src = File(uri.path ?: "")
            if (src.exists() && src.isFile) {
                // Copying a file onto itself truncates it, and shared text is staged here.
                if (src.canonicalPath == dest.canonicalPath) return src
                if (src.length() > MAX_SHARED_FILE_BYTES) {
                    Log.status(TAG, "uriToTempFile skipped oversized file (> $MAX_SHARED_FILE_BYTES bytes): $uri")
                    return null
                }
                src.copyTo(dest, overwrite = true)
                return dest
            }
        }
        // Size pre-check, enforced again while streaming below because a
        // provider may omit or understate its declared size.
        declaredSharedSize(context, uri)?.let { size ->
            if (size > MAX_SHARED_FILE_BYTES) {
                Log.status(TAG, "uriToTempFile skipped oversized file ($size bytes): $uri")
                return null
            }
        }
        var oversized = false
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { out ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var copied = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    copied += read
                    if (copied > MAX_SHARED_FILE_BYTES) {
                        oversized = true
                        break
                    }
                    out.write(buffer, 0, read)
                }
            }
        }
        if (oversized) {
            // Never leave a truncated or oversized staging copy behind.
            dest.delete()
            Log.status(TAG, "uriToTempFile skipped oversized file (> $MAX_SHARED_FILE_BYTES bytes): $uri")
            return null
        }
        if (dest.exists() && dest.length() > 0) dest else null
    } catch (e: Exception) {
        Log.status(TAG, "uriToTempFile failed for $uri", e)
        null
    }
}

/** Declared size of a shared [Uri] from its provider, or null when unknown. */
private fun declaredSharedSize(context: Context, uri: Uri): Long? {
    if (uri.scheme != "content") return null
    return try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            val index = c.getColumnIndex(OpenableColumns.SIZE)
            if (index != -1 && c.moveToFirst()) c.getLong(index).takeIf { it >= 0 } else null
        }
    } catch (_: Exception) {
        null
    }
}
