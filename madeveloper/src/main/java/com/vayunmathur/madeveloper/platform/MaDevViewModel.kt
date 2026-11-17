package com.vayunmathur.madeveloper.platform

import android.app.Application
import android.app.PendingIntent
import android.os.Build
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.WebSocketHandshakeException
import com.vayunmathur.madeveloper.data.DevRegistrar
import com.vayunmathur.madeveloper.data.GitHostRepository
import com.vayunmathur.madeveloper.data.DevSocketTransport
import com.vayunmathur.madeveloper.data.WebSocketDevTransport
import com.vayunmathur.madeveloper.domain.CatalogApp
import com.vayunmathur.madeveloper.domain.DevMessage
import com.vayunmathur.madeveloper.domain.DevRegistration
import com.vayunmathur.madeveloper.domain.DevProtocol
import com.vayunmathur.madeveloper.domain.GitFile
import com.vayunmathur.madeveloper.domain.GitIssue
import com.vayunmathur.madeveloper.domain.GitPr
import com.vayunmathur.madeveloper.domain.GitRepo
import com.vayunmathur.madeveloper.domain.InstallPhase
import com.vayunmathur.madeveloper.domain.InstalledAppInfo
import com.vayunmathur.madeveloper.domain.PendingInstall
import com.vayunmathur.madeveloper.domain.RepoBrowser
import com.vayunmathur.madeveloper.domain.RepoBrowserState
import com.vayunmathur.madeveloper.domain.RepoDetail
import com.vayunmathur.madeveloper.domain.TestsStatus
import com.vayunmathur.madeveloper.domain.TreeEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** UI state for the git, catalog and push areas. */
data class MaDevUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val repos: List<GitRepo> = emptyList(),
    val tests: TestsStatus? = null,
    val issues: List<GitIssue> = emptyList(),
    val prs: List<GitPr> = emptyList(),
    val catalog: List<CatalogApp> = emptyList(),
    val repoDetails: Map<String, RepoDetail> = emptyMap(),
    val browsers: Map<String, RepoBrowserState> = emptyMap(),
    val repoError: String? = null,
    val connected: Boolean = false,
    val pairingCode: String = "",
    val paired: Boolean = false,
    /** Registered but the server has not approved this device yet (socket refused 403). */
    val awaitingApproval: Boolean = false,
    val pending: List<PendingInstall> = emptyList(),
)

/** Holds Developer state: git pages, catalog, push socket and installs. */
class MaDevViewModel(app: Application) : AndroidViewModel(app) {
    // Test seams (internal, production defaults): the host repository, how
    // the push socket is opened, and how a pushed APK's bytes are fetched.
    // They exist so the state machine below can run off-device; the app
    // itself always takes the defaults.
    internal var repo: GitHostRepository = GitHostRepository()
    internal var transportFactory: suspend (String, String?) -> DevSocketTransport =
        { url, token -> WebSocketDevTransport.connect(url, token) }
    internal var deviceRegistrar: suspend (String) -> DevRegistration =
        { deviceName -> DevRegistrar().register(deviceName) }
    internal var apkFetcher: suspend (String) -> Pair<Int, ByteArray> =
        { url -> NetworkClient.performRequestBytes(url) }
    private val pairing = PairingStore(app)
    private var transport: DevSocketTransport? = null
    private var reconnectAttempt = 0

    // Paired stays false until the socket actually connects: holding a token
    // only proves registration, not that the owner approved this device.
    var state by mutableStateOf(MaDevUiState(pairingCode = pairing.pairingCode() ?: ""))
        private set

    /** Loads home data and starts the push socket. */
    fun loadHome() {
        if (state.repos.isNotEmpty()) return
        refresh()
        connectPush()
    }

    /** Reloads repositories, CI, issues and pull requests. */
    fun refresh() {
        state = state.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val repos = repo.repos()
                val tests = repo.tests()
                state = state.copy(
                    loading = false, repos = repos, tests = tests,
                    issues = repo.issues(), prs = repo.prs(),
                )
            } catch (e: java.io.IOException) {
                state = state.copy(loading = false, error = e.message ?: "Could not reach git.maproj.org")
            }
        }
    }

    /** Loads the read-only build catalog. */
    fun loadCatalog() {
        if (state.catalog.isNotEmpty()) return
        viewModelScope.launch {
            try { state = state.copy(catalog = repo.catalog()) }
            catch (e: java.io.IOException) { state = state.copy(error = e.message ?: "Could not load the catalog") }
        }
    }

    /** Loads repo detail (branches + recent commits) and opens its browser. */
    fun loadRepo(name: String) {
        if (state.browsers.containsKey(name)) return
        viewModelScope.launch {
            try {
                val detail = repo.repo(name)
                state = state.copy(
                    repoDetails = state.repoDetails + (name to detail),
                    browsers = state.browsers + (name to RepoBrowser.open(detail)),
                    repoError = null,
                )
            } catch (e: java.io.IOException) {
                state = state.copy(repoError = e.message ?: "Could not load $name")
            }
        }
    }

    /** The browser state for [name], if its detail has loaded. */
    fun browser(name: String): RepoBrowserState? = state.browsers[name]

    /** Switches [name] to [branch]; resets tree path and commits, then reloads. */
    fun selectBranch(name: String, branch: String) {
        val current = state.browsers[name] ?: return
        updateBrowser(RepoBrowser.selectBranch(current, branch))
        loadCommits(name, loadMore = false)
    }

    /** Navigates the tree browser for [name] to [path]. */
    fun navigateTree(name: String, path: String) {
        val current = state.browsers[name] ?: return
        updateBrowser(RepoBrowser.navigateTo(current, path))
    }

    /** Loads the first commits page, or appends the next when [loadMore]. */
    fun loadCommits(name: String, loadMore: Boolean) {
        val browser = state.browsers[name] ?: return
        if (loadMore && browser.commitsExhausted) return
        viewModelScope.launch {
            try {
                val page = repo.commits(
                    name, browser.branch, GitHostRepository.COMMITS_PAGE_SIZE,
                    if (loadMore) browser.nextCommitCursor else null,
                )
                val current = state.browsers[name] ?: return@launch
                updateBrowser(
                    if (loadMore) {
                        RepoBrowser.commitsAppended(current, page, GitHostRepository.COMMITS_PAGE_SIZE)
                    } else {
                        RepoBrowser.commitsLoaded(current, page, GitHostRepository.COMMITS_PAGE_SIZE)
                    },
                )
            } catch (e: java.io.IOException) {
                state = state.copy(repoError = e.message ?: "Could not load commits")
            }
        }
    }

    private fun updateBrowser(browser: RepoBrowserState) {
        state = state.copy(browsers = state.browsers + (browser.repoName to browser))
    }

    /** Tree entries for [repoName] on [branch] at [path]. */
    suspend fun tree(repoName: String, branch: String, path: String): List<TreeEntry> =
        repo.tree(repoName, branch, path)

    /** File at [path] in [repoName] on [branch]. */
    suspend fun file(repoName: String, branch: String, path: String): GitFile =
        repo.file(repoName, branch, path)

    /** Finds a repo by display name. */
    fun repoByName(name: String): GitRepo? = state.repos.firstOrNull { it.name == name }

    // ---- Push socket ----

    /** Registers on first need, then connects the push socket, reconnecting with backoff. */
    fun connectPush() {
        viewModelScope.launch {
            while (true) {
                try {
                    ensureRegistered()
                    val socket = transportFactory(GitHostRepository.DEV_WS_URL, pairing.token())
                    transport = socket
                    reconnectAttempt = 0
                    state = state.copy(connected = true, paired = true, awaitingApproval = false)
                    socket.messages.collect { message -> onServerMessage(message, socket) }
                } catch (e: java.io.IOException) {
                    Log.status(TAG, "dev push connect failed (attempt $reconnectAttempt): ${e.message}", e)
                    state = state.copy(
                        awaitingApproval = (e as? WebSocketHandshakeException)?.statusCode == HTTP_FORBIDDEN,
                    )
                }
                transport = null
                state = state.copy(connected = false)
                delay(DevProtocol.backoffMs(reconnectAttempt++))
            }
        }
    }

    /**
     * Registers this device once and persists the server-issued credentials.
     * A legacy locally generated code is stored without a token, so "no token"
     * covers both first launch and that migration: register fresh either way.
     */
    private suspend fun ensureRegistered() {
        if (!pairing.token().isNullOrBlank()) {
            if (state.pairingCode.isBlank()) {
                state = state.copy(pairingCode = pairing.pairingCode() ?: "")
            }
            return
        }
        val registration = deviceRegistrar(deviceName())
        pairing.saveRegistration(registration.deviceId, registration.pushToken, registration.pairingCode)
        state = state.copy(pairingCode = registration.pairingCode, paired = false)
    }

    /** The user-visible device name sent to `/dev/register`. */
    private fun deviceName(): String {
        val model = listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
            .joinToString(" ").trim()
        return model.ifBlank { "Android device" }.take(MAX_DEVICE_NAME)
    }

    private suspend fun onServerMessage(message: DevMessage, socket: DevSocketTransport) {
        when (message) {
            DevMessage.Ping -> socket.send("{\"type\":\"pong\"}")
            DevMessage.ListApps -> socket.send(DevProtocol.listAppsReply(installedApps()))
            is DevMessage.Install -> onPushInstall(
                PendingInstall(message.pkg, message.versionCode, message.apkUrl, message.sha256, message.sizeBytes),
            )
            is DevMessage.Unknown -> Unit
        }
    }

    private fun installedApps(): List<InstalledAppInfo> {
        val context = getApplication<Application>()
        return context.packageManager.getInstalledPackages(0)
            .filter { it.packageName.startsWith("com.vayunmathur.") }
            .map { InstalledAppInfo(it.packageName, it.longVersionCode) }
    }

    // ---- Pushed installs ----

    private fun onPushInstall(install: PendingInstall) {
        state = state.copy(pending = state.pending.filterNot { it.pkg == install.pkg } + install)
        InstallNotifications.showPending(getApplication(), install)
        downloadAndVerify(install)
    }

    private fun downloadAndVerify(install: PendingInstall) {
        viewModelScope.launch {
            updatePending(DevProtocol.transition(install, InstallPhase.DOWNLOADING, 0f))
            try {
                val bytes = withContext(Dispatchers.IO) {
                    val (status, data) = apkFetcher(install.apkUrl)
                    if (status !in 200..299) throw java.io.IOException("Download failed: HTTP $status")
                    data
                }
                val verifying = currentPending(install.pkg) ?: install
                updatePending(DevProtocol.transition(verifying, InstallPhase.VERIFYING, 1f))
                if (!DevProtocol.verifySha256(bytes, install.sha256)) {
                    throw java.io.IOException("APK hash does not match the pushed sha256")
                }
                val file = withContext(Dispatchers.IO) {
                    val name = "push-" + install.pkg + ".apk"
                    File(getApplication<Application>().cacheDir, name).apply { writeBytes(bytes) }
                }
                val ready = DevProtocol.transition(currentPending(install.pkg) ?: install, InstallPhase.READY, 1f)
                updatePending(ready.copy())
                InstallNotifications.showReady(getApplication(), ready)
            } catch (e: java.io.IOException) {
                val current = currentPending(install.pkg) ?: install
                state = state.copy(pending = state.pending.map { item ->
                    if (item.pkg == install.pkg) item.copy(phase = InstallPhase.FAILED, error = e.message) else item
                })
            }
        }
    }

    /** User-confirmed install: opens the PackageInstaller session for a ready push. */
    fun installPending(pkg: String) {
        val pending = currentPending(pkg) ?: return
        if (pending.phase != InstallPhase.READY) return
        val file = File(getApplication<Application>().cacheDir, "push-$pkg.apk")
        if (!file.exists()) return
        updatePending(DevProtocol.transition(pending, InstallPhase.INSTALLING, 1f))
        runCatching { commitInstall(pkg, file) }.onFailure {
            state = state.copy(pending = state.pending.map { item ->
                if (item.pkg == pkg) item.copy(phase = InstallPhase.READY, error = it.message) else item
            })
        }
    }

    private fun commitInstall(pkg: String, file: File) {
        val context = getApplication<Application>()
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setSize(file.length())
            setAppPackageName(pkg)
        }
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            session.openWrite(file.name, 0, file.length()).use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallStatusReceiver::class.java).setAction(InstallStatusReceiver.ACTION)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val pendingIntent = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pendingIntent.intentSender)
        } finally {
            session.close()
        }
    }

    private fun currentPending(pkg: String): PendingInstall? = state.pending.firstOrNull { it.pkg == pkg }

    private fun updatePending(install: PendingInstall) {
        state = state.copy(pending = state.pending.map { if (it.pkg == install.pkg) install else it })
    }

    /** SHA-256 helper kept for the download path's diagnostics. */
    internal fun shaOf(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Whether the system will show an install prompt for this app. */
    fun canInstall(): Boolean = getApplication<Application>().packageManager.canRequestPackageInstalls()

    private companion object {
        const val TAG = "MaDevViewModel"
        const val HTTP_FORBIDDEN = 403
        const val MAX_DEVICE_NAME = 120
    }
}
