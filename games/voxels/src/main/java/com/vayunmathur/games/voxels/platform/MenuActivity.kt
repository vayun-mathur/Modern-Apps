@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
// Game menu bootstrap: UI wiring lives here rather than ui/ for legacy reasons;
// same rationale as MainActivity's file-level suppressions above.
@file:Suppress("MagicNumber", "MaxLineLength", "LongMethod", "CyclomaticComplexMethod")

package com.vayunmathur.games.voxels.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.vayunmathur.games.voxels.MainActivity
import com.vayunmathur.games.voxels.R
import com.vayunmathur.games.voxels.ui.MenuScreen
import com.vayunmathur.games.voxels.ui.ShareOnlineDialog
import com.vayunmathur.games.voxels.ui.WorldCreatorScreen
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.library.util.rememberIsOnline
import com.vayunmathur.games.voxels.network.JoinRequests
import com.vayunmathur.games.voxels.network.VoxelsRoles
import com.vayunmathur.games.voxels.network.VoxelsSync
import com.vayunmathur.games.voxels.data.WorldInfo
import com.vayunmathur.games.voxels.data.WorldManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64
import kotlin.random.Random

// Launcher screen: lists saved worlds and hosts the world creator. Playing a world starts
// MainActivity (the game) with the world's save directory + seed passed as intent extras. Online
// worlds add the shared world id + AES key + role so the game can start VoxelsSync in host/client mode.
class MenuActivity : ComponentActivity() {
    // Bumped on each onResume so the world list reloads when returning from the game
    // (keeps last-played ordering fresh).
    private var resumeTick by mutableStateOf(0)
    // True while the menu is in the foreground; drives the inbox poll.
    private var menuResumed by mutableStateOf(false)

    /**
     * Pending `voxels://join/<worldId>?owner=<id>` link (worldId to ownerId), set on cold start and
     * via [onNewIntent] for warm starts, consumed by a Compose effect that sends the join request.
     */
    private val pendingJoinLink = mutableStateOf<Pair<String, String>?>(null)

    override fun onResume() {
        super.onResume()
        resumeTick++
        menuResumed = true
    }

    override fun onPause() {
        menuResumed = false
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTop: warm-start join links land here rather than a fresh onCreate.
        setIntent(intent)
        parseJoinLink(intent)?.let { pendingJoinLink.value = it }
    }

    /** Parses `voxels://join/<worldId>?owner=<ownerDeviceId>`; null for any other intent. */
    private fun parseJoinLink(intent: Intent?): Pair<String, String>? {
        val data = intent?.data ?: return null
        if (data.scheme != "voxels" || data.host != "join") return null
        val worldId = data.lastPathSegment?.takeIf { it.isNotBlank() } ?: return null
        val ownerId = data.getQueryParameter("owner")?.takeIf { it.isNotBlank() } ?: return null
        return worldId to ownerId
    }

    private fun localWorlds() = WorldManager.listWorlds(this).filter { !it.meta.online }
    private fun onlineWorldList() = WorldManager.onlineWorlds(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Cold start: a tapped voxels://join link arrives as this activity's launch intent.
        // singleTop only routes warm starts to onNewIntent, so without this the link is
        // silently dropped and no join request is ever sent.
        parseJoinLink(intent)?.let { pendingJoinLink.value = it }
        enableEdgeToEdge()
        setContent {
            // Standard app theme (Material You / system colors), matching the other apps — the menu
            // isn't part of the in-game world so it shouldn't use the green Voxels palette.
            DynamicTheme {
                val scope = rememberCoroutineScope()
                val online by rememberIsOnline()
                var creating by remember { mutableStateOf(false) }
                var worlds by remember { mutableStateOf(localWorlds()) }
                var onlineWorlds by remember { mutableStateOf(onlineWorldList()) }
                var deviceId by remember { mutableStateOf("") }
                var shareTarget by remember { mutableStateOf<WorldInfo?>(null) }
                var requests by remember { mutableStateOf<List<VoxelsSync.JoinRequest>>(emptyList()) }

                val ctx = this@MenuActivity
                fun reload() { worlds = localWorlds(); onlineWorlds = onlineWorldList() }

                // Pulls the inbox (from 0): folds invites into the shared-world list and surfaces
                // join requests for worlds this device owns (minus already-approved/denied ones).
                val refreshInbox: suspend () -> Unit = {
                    requests = JoinRequests.refresh(ctx, acceptInvites = true)
                    reload()
                }
                // Keep pulling while the menu is on screen, so new invites and join requests show up
                // without pressing Refresh.
                LaunchedEffect(menuResumed, online) {
                    while (menuResumed && online) {
                        delay(JoinRequests.POLL_MS)
                        runCatching { refreshInbox() }
                    }
                }

                LaunchedEffect(resumeTick) {
                    worlds = localWorlds()
                    onlineWorlds = onlineWorldList()
                }
                // Provision the sync identity in the background so the device id shows and invites
                // can be received even before hosting anything.
                LaunchedEffect(Unit) {
                    withContext(Dispatchers.IO) { runCatching { VoxelsSync.init(this@MenuActivity) } }
                    deviceId = VoxelsSync.deviceId
                    runCatching { refreshInbox() }
                }
                // A tapped voxels://join link sends a sealed join request to the host.
                LaunchedEffect(pendingJoinLink.value) {
                    val link = pendingJoinLink.value ?: return@LaunchedEffect
                    pendingJoinLink.value = null
                    val (worldId, ownerId) = link
                    toast(getString(R.string.requesting_access))
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            VoxelsSync.init(ctx)
                            val myName = "Player " + VoxelsSync.deviceId.takeLast(4)
                            VoxelsSync.sendJoinRequest(ownerId, worldId, VoxelsSync.deviceId, myName)
                        }.getOrDefault(false)
                    }
                    if (deviceId.isEmpty()) deviceId = VoxelsSync.deviceId
                    toast(getString(if (ok) R.string.join_request_sent else R.string.invite_failed))
                }

                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize()) {
                        if (creating) {
                            WorldCreatorScreen(
                                onBack = { creating = false },
                                onCreate = { name, seedText, mode ->
                                    val seed = WorldManager.resolveSeed(seedText)
                                    val world = WorldManager.createWorld(this@MenuActivity, name, seed, System.currentTimeMillis(), mode)
                                    reload()
                                    creating = false
                                    play(world)
                                }
                            )
                        } else {
                            MenuScreen(
                                worlds = worlds,
                                onlineWorlds = onlineWorlds,
                                deviceId = deviceId,
                                isOnline = online,
                                onPlay = { play(it) },
                                onDelete = { world ->
                                    WorldManager.deleteWorld(this@MenuActivity, world.id)
                                    reload()
                                },
                                onCreate = { creating = true },
                                onHostOnline = {
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) { runCatching { VoxelsSync.init(this@MenuActivity) }.getOrDefault(false) }
                                        if (!ok) { toast(getString(R.string.invite_failed)); return@launch }
                                        deviceId = VoxelsSync.deviceId
                                        val worldId = VoxelsSync.newWorldId()
                                        val keyB64 = Base64.encode(VoxelsSync.newWorldKey())
                                        val world = WorldManager.createOnlineWorld(
                                            this@MenuActivity, getString(R.string.host_online_world),
                                            Random.nextInt(), worldId, keyB64, deviceId, System.currentTimeMillis(),
                                        )
                                        reload()
                                        play(world)
                                    }
                                },
                                onRefresh = {
                                    scope.launch { refreshInbox() }
                                },
                                onCopyDeviceId = { copyToClipboard(deviceId) },
                                onShare = { shareTarget = it },
                                requests = requests,
                                onApprove = { req ->
                                    scope.launch {
                                        val ok = JoinRequests.approve(ctx, req)
                                        requests = requests.filterNot { it.worldId == req.worldId && it.requesterId == req.requesterId }
                                        toast(getString(if (ok) R.string.invite_sent else R.string.invite_failed))
                                    }
                                },
                                onDeny = { req ->
                                    JoinRequests.deny(ctx, req)
                                    requests = requests.filterNot { it.worldId == req.worldId && it.requesterId == req.requesterId }
                                },
                            )
                        }

                        shareTarget?.let { world ->
                            ShareOnlineDialog(
                                world = world,
                                onDismiss = { shareTarget = null },
                                onSend = { recipient ->
                                    shareTarget = null
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) {
                                            runCatching {
                                                VoxelsSync.init(this@MenuActivity)
                                                val key = Base64.decode(world.meta.keyB64)
                                                val sent = VoxelsSync.sendInvite(
                                                    recipient, world.meta.worldId, key,
                                                    world.meta.name, world.meta.seed, VoxelsRoles.EDITOR,
                                                    Base64.encode(VoxelsSync.publicBundle), deviceId,
                                                )
                                                // Publish the owner-signed roster so the host can gate edits.
                                                if (sent) VoxelsSync.recordMembers(
                                                    world.meta.worldId, key,
                                                    listOf(
                                                        VoxelsSync.Member(deviceId, "", VoxelsRoles.OWNER),
                                                        VoxelsSync.Member(recipient, "", VoxelsRoles.EDITOR),
                                                    ),
                                                )
                                                sent
                                            }.getOrDefault(false)
                                        }
                                        toast(getString(if (ok) R.string.invite_sent else R.string.invite_failed))
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun toast(msg: String) = AppMessages.show(msg)

    private fun copyToClipboard(text: String) {
        if (text.isEmpty()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("device id", text))
    }

    private fun play(world: WorldInfo) {
        WorldManager.touch(this, world.id, System.currentTimeMillis())
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra("world_dir", world.dir)
            putExtra("world_seed", world.meta.seed)
            putExtra("world_name", world.meta.name)
            putExtra("world_mode", world.meta.mode)
            if (world.meta.online) {
                putExtra("online", true)
                putExtra("world_id", world.meta.worldId)
                putExtra("world_key", world.meta.keyB64)
                putExtra("owner_device", world.meta.ownerDeviceId)
                // Owner hosts (authority = 1); everyone else joins as a client (2).
                putExtra("net_role", if (world.meta.role == VoxelsRoles.OWNER) 1 else 2)
            }
        })
    }
}
