@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
// Game bootstrap: 388-line onCreate wires engine/JNI/multiplayer/UI in one place,
// with tuning literals inline. Splitting working game glue risks behavior drift;
// exception/parameter/import rules below still apply to this file.
@file:Suppress("MagicNumber", "MaxLineLength", "LongMethod", "CyclomaticComplexMethod")

package com.vayunmathur.games.voxels

import com.vayunmathur.games.voxels.R
import androidx.compose.ui.res.stringResource
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.vayunmathur.games.voxels.domain.BackTarget
import com.vayunmathur.games.voxels.domain.OverlayFlags
import com.vayunmathur.games.voxels.domain.backTarget
import com.vayunmathur.games.voxels.ui.BossBar
import com.vayunmathur.games.voxels.ui.ChestOverlay
import com.vayunmathur.games.voxels.ui.DebugOverlay
import com.vayunmathur.games.voxels.ui.FloatingLookJoystick
import com.vayunmathur.games.voxels.ui.FurnaceOverlay
import com.vayunmathur.games.voxels.ui.GlideIndicator
import com.vayunmathur.games.voxels.ui.HealthOverlay
import com.vayunmathur.games.voxels.ui.HoldButton
import com.vayunmathur.games.voxels.ui.Hotbar
import com.vayunmathur.games.voxels.ui.InventoryOverlay
import com.vayunmathur.games.voxels.ui.InventoryState
import com.vayunmathur.games.voxels.ui.LoadPhase
import com.vayunmathur.games.voxels.ui.LoadingOverlay
import com.vayunmathur.games.voxels.ui.Joystick
import com.vayunmathur.games.voxels.ui.StonecutterOverlay
import com.vayunmathur.games.voxels.ui.TradeOverlay
import com.vayunmathur.games.voxels.ui.VoxelSurfaceView
import com.vayunmathur.games.voxels.ui.VoxelsTheme
import com.vayunmathur.games.voxels.platform.VoxelsAchievements
import com.vayunmathur.games.voxels.util.VoxelsNative
import com.vayunmathur.games.voxels.network.VoxelsSync
import com.vayunmathur.e2ee.Pqc
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.ui.AchievementNotification
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconMenu
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.GameHubComposeHook
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    // Pause-menu state lives on the activity so onStop can pause the game: coming back from Home or
    // the lock screen lands on the pause menu instead of straight into whatever was attacking.
    private var paused by mutableStateOf(false)

    // JNI boundary: native throws unchecked exceptions, all logged here.
    @Suppress("TooGenericExceptionCaught")
    private fun initEngine(worldDir: String, worldSeed: Int) {
        try { VoxelsNative.nativeInit(worldDir, worldSeed) } catch (e: RuntimeException) {
            Log.error("VoxelsMain", "nativeInit failed", e)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideSystemBars()
        // World to load: passed by MenuActivity. Fall back to a default world if launched directly.
        val worldDir = intent.getStringExtra("world_dir")
            ?: java.io.File(filesDir, "worlds/default").apply { mkdirs() }.absolutePath
        val worldSeed = intent.getIntExtra("world_seed", 0xB10CCA)
        // The world's directory name is its id (see WorldManager.createWorld). Deriving it here beats
        // widening the Intent contract MenuActivity already has.
        val worldId = java.io.File(worldDir).name
        // Online multiplayer extras (absent for local single-player worlds).
        val online = intent.getBooleanExtra("online", false)
        val netRole = intent.getIntExtra("net_role", 0)
        val sharedWorldId = intent.getStringExtra("world_id") ?: ""
        val worldKeyB64 = intent.getStringExtra("world_key") ?: ""
        val ownerDevice = intent.getStringExtra("owner_device") ?: ""
        val worldName = intent.getStringExtra("world_name") ?: ""
        if (VoxelsNative.isAvailable) {
            // Put the engine in host/client mode before the engine thread starts ticking.
            if (online) try { VoxelsNative.nativeSetRole(netRole) } catch (_: Exception) {}
            // Returns immediately: the world is generated and meshed on the engine thread behind
            // the loading overlay, never on the main thread.
            initEngine(worldDir, worldSeed)
        }
        com.vayunmathur.games.voxels.platform.SoundFx.init(this)
        setContent {
            VoxelsTheme {
                var inventoryJson by remember { mutableStateOf("""{"selected":0,"slots":[{"id":3,"count":64},{"id":2,"count":64},{"id":1,"count":64},{"id":4,"count":16},{"id":10,"count":32},{"id":6,"count":32},{"id":7,"count":16},{"id":8,"count":16},{"id":9,"count":16}]}""") }
                var debugJson by remember { mutableStateOf("Voxels Engine\nInitializing Vulkan...\nMatcha Atlas 64x64") }
                var healthJson by remember { mutableStateOf("{}") }
                var showDebug by remember { mutableStateOf(true) }
                var flying by remember { mutableStateOf(false) }
                var sneaking by remember { mutableStateOf(false) }
                var inventoryOpen by remember { mutableStateOf(false) }
                val activity = LocalActivity.current
                var invStartTab by remember { mutableStateOf(0) }
                var recipesJson by remember { mutableStateOf("[]") }
                var blessingsJson by remember { mutableStateOf("""{"slots":[]}""") }
                var blessingCatalogJson by remember { mutableStateOf("[]") }
                var smeltingJson by remember { mutableStateOf("[]") }
                var smeltJson by remember { mutableStateOf("{}") }
                var containerJson by remember { mutableStateOf("""{"slots":[]}""") }
                var furnaceOpen by remember { mutableStateOf(false) }
                var furnaceIsBlast by remember { mutableStateOf(false) }
                var chestOpen by remember { mutableStateOf(false) }
                var cutsJson by remember { mutableStateOf("[]") }
                var stonecutterOpen by remember { mutableStateOf(false) }
                var tradesJson by remember { mutableStateOf("{}") }
                var tradeOpen by remember { mutableStateOf(false) }
                // Online multiplayer HUD state.
                var netConnected by remember { mutableStateOf(false) }
                var peersJson by remember { mutableStateOf("[]") }
                LaunchedEffect(Unit) { if (VoxelsNative.isAvailable) try { recipesJson = VoxelsNative.getRecipesJson(); smeltingJson = VoxelsNative.getSmeltingJson(); blessingCatalogJson = VoxelsNative.getBlessingCatalogJson(); cutsJson = VoxelsNative.getCutsJson() } catch (_: Exception) {} }
                // World loading runs on the engine thread; poll it for the loading overlay. The phase
                // can drop back to meshing if the renderer ever has to be rebuilt.
                var loadPhase by remember { mutableIntStateOf(if (VoxelsNative.isAvailable) LoadPhase.GENERATING else LoadPhase.READY) }
                var loadProgress by remember { mutableFloatStateOf(0f) }
                LaunchedEffect(Unit) {
                    while (isActive && VoxelsNative.isAvailable) {
                        try {
                            loadPhase = VoxelsNative.nativeLoadState()
                            loadProgress = VoxelsNative.nativeLoadProgress()
                        } catch (_: Exception) {}
                        delay(if (loadPhase == LoadPhase.READY) 500 else 100)
                    }
                }
                var achievements by remember { mutableStateOf<VoxelsAchievements?>(null) }
                val newAchievement by (achievements?.newAchievement?.collectAsState() ?: remember { mutableStateOf(null) })

                LaunchedEffect(Unit) {
                    try {
                        val json = assets.open("achievements.json").bufferedReader().readText()
                        achievements = VoxelsAchievements(this@MainActivity, json, worldId)
                    } catch (_: Exception) {}
                }

                LaunchedEffect(Unit) {
                    var prevHp = 20f
                    // Footsteps and atmosphere cues arrive as monotonic counters; a change means the
                    // engine wants a sound. See ambience.rs.
                    var prevStep = -1
                    var prevCue = -1
                    while (isActive) {
                        if (VoxelsNative.isAvailable) {
                            try {
                                try {
                                    val amb = org.json.JSONObject(VoxelsNative.getAmbienceJson())
                                    val stepN = amb.optInt("stepN", prevStep)
                                    if (prevStep >= 0 && stepN != prevStep) {
                                        com.vayunmathur.games.voxels.platform.SoundFx.playStep(amb.optInt("stepMat", 0))
                                    }
                                    prevStep = stepN
                                    val cueN = amb.optInt("cueN", prevCue)
                                    if (prevCue >= 0 && cueN != prevCue) {
                                        when (amb.optInt("cueKind", 0)) {
                                            1 -> com.vayunmathur.games.voxels.platform.SoundFx.playCave()
                                            2 -> com.vayunmathur.games.voxels.platform.SoundFx.playStalk()
                                        }
                                    }
                                    prevCue = cueN
                                } catch (_: Exception) {}
                                inventoryJson = VoxelsNative.getInventoryJson()
                                debugJson = VoxelsNative.getDebugJson()
                                if (furnaceOpen) smeltJson = VoxelsNative.getSmeltJson()
                                if (chestOpen) containerJson = VoxelsNative.getContainerJson()
                                if (inventoryOpen) {
                                    blessingsJson = VoxelsNative.getBlessingsJson()
                                    // Recipes reveal themselves as the player picks up ingredients.
                                    recipesJson = VoxelsNative.getRecipesJson()
                                }
                                try {
                                    healthJson = VoxelsNative.getHealthJson()
                                    val hp = org.json.JSONObject(healthJson).optDouble("hp", prevHp.toDouble()).toFloat()
                                    if (hp < prevHp - 0.5f) {
                                        if (prevHp - hp > 6f) com.vayunmathur.games.voxels.platform.SoundFx.playExplode()
                                        else com.vayunmathur.games.voxels.platform.SoundFx.playHurt()
                                    }
                                    prevHp = hp
                                } catch (_: Exception) {}
                                try { flying = org.json.JSONObject(debugJson).optBoolean("flying", flying) } catch (_: Exception) {}
                                val stats = VoxelsNative.getStatsJson()
                                try {
                                    val obj = org.json.JSONObject(stats)
                                    val placed = obj.optInt("placed", 0)
                                    val broken = obj.optInt("broken", 0)
                                    val walked = obj.optInt("walked", 0)
                                    achievements?.let { mgr ->
                                        if (broken > 0) mgr.unlock("first_block")
                                        if (placed > 0) mgr.unlock("first_place")
                                        mgr.progress("builder_100", placed)
                                        mgr.progress("miner_100", broken)
                                        mgr.progress("explorer_100", walked)
                                        if (obj.optBoolean("night", false)) mgr.unlock("night_survivor")
                                        if (obj.optInt("depth", 128) <= 8) mgr.unlock("deep_diver")
                                        if (obj.optBoolean("silver", false)) mgr.unlock("silver_tongue")
                                        if (obj.optBoolean("steel", false)) mgr.unlock("steelworker")
                                        if (obj.optBoolean("adamant", false)) mgr.unlock("alchemist")
                                        if (obj.optBoolean("blessing", false)) mgr.unlock("blessed")
                                        if (obj.optBoolean("fullArmor", false)) mgr.unlock("fully_armed")
                                        if (obj.optBoolean("nether", false)) mgr.unlock("nether_bound")
                                        if (obj.optBoolean("end", false)) mgr.unlock("the_end")
                                        if (obj.optBoolean("dragon", false)) mgr.unlock("dragonslayer")
                                        if (obj.optBoolean("wither", false)) mgr.unlock("withering")
                                        if (obj.optInt("beacon", 0) >= 4) mgr.unlock("beacon_master")
                                        if (obj.optBoolean("elytra", false)) mgr.unlock("sky_bound")
                                        if (obj.optBoolean("maxHearts", false)) mgr.unlock("heart_of_gold")
                                        if (obj.optInt("attuned", 0) > 0) mgr.unlock("attuned")
                                        if (obj.optInt("attuned", 0) >= 3) mgr.unlock("pantheon")
                                        if (obj.optBoolean("traded", false)) mgr.unlock("first_trade")
                                        if (obj.optBoolean("trader", false)) mgr.unlock("master_trader")
                                        if (obj.optBoolean("sheared", false)) mgr.unlock("first_shear")
                                        if (obj.optBoolean("fished", false)) mgr.unlock("first_catch")
                                        if (obj.optBoolean("brushed", false)) mgr.unlock("first_dig")
                                        if (obj.optBoolean("harvested", false)) mgr.unlock("first_harvest")
                                        if (obj.optBoolean("rested", false)) mgr.unlock("first_rest")
                                        mgr.progress("recipes_50", obj.optInt("recipes", 0))
                                    }
                                } catch (_: Exception) {}
                            } catch (_: Exception) {}
                        }
                        delay(150)
                    }
                }

                // --- Online multiplayer pump: bridge the Rust net queues to the encrypted relay. ---
                // World-log ops are signed (SignedOp{author,sig,ops}) so peers can authenticate them;
                // player transforms ride ephemeral, unsigned presence. Confidentiality comes from the
                // world AES key (only members hold it); the relay only ever sees ciphertext.
                LaunchedEffect(online) {
                    if (!online || !VoxelsNative.isAvailable) return@LaunchedEffect
                    if (!VoxelsSync.init(this@MainActivity)) return@LaunchedEffect
                    val pumpScope = this
                    val myId = VoxelsSync.deviceId
                    try { VoxelsNative.nativeSetDevice(myId) } catch (_: Exception) {}
                    val key = runCatching { Base64.decode(worldKeyB64) }.getOrNull() ?: return@LaunchedEffect
                    val channel = "world:$sharedWorldId"
                    // Owner bundle: the host is the owner; a client fetches it once to verify host ops.
                    val ownerBundle: ByteArray? = if (netRole == 1) VoxelsSync.publicBundle else VoxelsSync.getKey(ownerDevice)
                    // Host: the authorized member set (all editors — no viewers) + each member's public
                    // bundle, so every client op is signature-verified, not just role-gated by id.
                    val roster = java.util.Collections.synchronizedSet(HashSet<String>())
                    val bundles = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
                    roster.add(ownerDevice); roster.add(myId)
                    VoxelsSync.publicBundle.let { bundles[myId] = it; bundles[ownerDevice] = it }
                    suspend fun refreshRoster() {
                        runCatching { VoxelsSync.fetchRoster(sharedWorldId, key, VoxelsSync.publicBundle) }.getOrNull()?.forEach { (id, _) ->
                            roster.add(id)
                            if (!bundles.containsKey(id)) VoxelsSync.getKey(id)?.let { bundles[id] = it }
                        }
                    }
                    if (netRole == 1) refreshRoster()
                    // Verify + gate an incoming signed op, then hand the inner NetMsg to the engine.
                    // Signed-op validation: each early return rejects one distinct
                    // failure (bad author, bad roster, bad signature). Splitting
                    // would scatter a single security check.
                    @Suppress("ReturnCount")
                    fun handleSignedOp(plain: String) {
                        try {
                            val so = JSONObject(plain)
                            val author = so.optString("author")
                            val sig = so.optString("sig")
                            val ops = so.optString("ops")
                            if (author.isEmpty() || ops.isEmpty() || author == myId) return
                            if (netRole == 2) {
                                // Client applies only owner-signed authoritative state.
                                if (author != ownerDevice) return
                                val ob = ownerBundle ?: return
                                val ok = runCatching { Pqc.verify(ob, ops.encodeToByteArray(), Base64.decode(sig)) }.getOrDefault(false)
                                if (!ok) return
                            } else {
                                // Host: author must be a rostered member AND the op must carry a valid
                                // signature from that member's bundle. On a cache miss, fetch it for
                                // next time and drop this op (the client resends as chunks stay dirty).
                                if (!roster.contains(author)) return
                                val ab = bundles[author]
                                if (ab == null) {
                                    pumpScope.launch(Dispatchers.IO) { runCatching { VoxelsSync.getKey(author) }.getOrNull()?.let { bundles[author] = it } }
                                    return
                                }
                                val ok = runCatching { Pqc.verify(ab, ops.encodeToByteArray(), Base64.decode(sig)) }.getOrDefault(false)
                                if (!ok) return
                            }
                            VoxelsNative.netPushInbound(ops)
                        } catch (_: Exception) {}
                    }
                    fun handlePresence(plain: String) {
                        try {
                            val e = JSONObject(plain)
                            if (e.optString("from") == myId) return
                            VoxelsNative.netPushInbound(e.getJSONObject("m").toString())
                        } catch (_: Exception) {}
                    }
                    VoxelsSync.startLive(
                        scope = this,
                        channel = channel,
                        onConnected = {
                            // A client announces itself (signed) so the host streams the world + snapshot.
                            if (netRole == 2) {
                                val joinOps = JSONObject().put("type", "Join").put("device", myId).put("name", worldName).toString()
                                val sig = Base64.encode(VoxelsSync.sign(joinOps.encodeToByteArray()))
                                val signed = JSONObject().put("author", myId).put("sig", sig).put("ops", joinOps).toString()
                                VoxelsSync.liveAppend(channel, key, listOf(signed))
                            }
                        },
                        onMessage = { raw ->
                            val msg = VoxelsSync.parseLive(raw)
                            if (msg != null) when (msg.t) {
                                "actions" -> for (b in msg.actions) {
                                    val plain = VoxelsSync.decrypt(key, b)
                                    if (plain != null) handleSignedOp(plain)
                                }
                                "presence" -> {
                                    val plain = VoxelsSync.decrypt(key, msg.data)
                                    if (plain != null) handlePresence(plain)
                                }
                            }
                        },
                    )
                    var rosterTick = 0
                    while (isActive) {
                        // Periodically re-read the roster so newly-invited editors are accepted mid-session.
                        rosterTick++
                        if (netRole == 1 && rosterTick % 125 == 0) refreshRoster()
                        val out = try { VoxelsNative.netDrainOutbound() } catch (_: Exception) { "[]" }
                        try {
                            val arr = JSONArray(out)
                            val ops = ArrayList<String>()
                            for (i in 0 until arr.length()) {
                                val o = arr.getJSONObject(i)
                                if (o.optString("type") == "PlayerTransform") {
                                    val env = JSONObject().put("from", myId).put("m", o).toString()
                                    VoxelsSync.sendPresence(channel, key, env)
                                } else {
                                    val opsJson = o.toString()
                                    val sig = Base64.encode(VoxelsSync.sign(opsJson.encodeToByteArray()))
                                    ops.add(JSONObject().put("author", myId).put("sig", sig).put("ops", opsJson).toString())
                                }
                            }
                            if (ops.isNotEmpty()) VoxelsSync.liveAppend(channel, key, ops)
                        } catch (_: Exception) {}
                        netConnected = VoxelsSync.isLive
                        peersJson = try { VoxelsNative.getPeersJson() } catch (_: Exception) { "[]" }
                        delay(80)
                    }
                }

                // "Paused" is real: the engine stops simulating and every held control is released,
                // so closing the menu never resumes a walk or a turn that was in progress.
                LaunchedEffect(paused) {
                    if (!VoxelsNative.isAvailable) return@LaunchedEffect
                    try {
                        VoxelsNative.nativeSetPaused(paused)
                        if (paused) {
                            VoxelsNative.onMoveInput(0f, 0f)
                            VoxelsNative.onLookInput(0f, 0f)
                            VoxelsNative.setJump(false)
                            VoxelsNative.setFlyDown(false)
                        }
                    } catch (_: Exception) {}
                }

                // Back closes the top-most overlay or opens the pause menu; it never quits the world.
                BackHandler {
                    val open = OverlayFlags(
                        chest = chestOpen, trade = tradeOpen, furnace = furnaceOpen,
                        stonecutter = stonecutterOpen, inventory = inventoryOpen, paused = paused,
                    )
                    when (backTarget(open)) {
                        BackTarget.CHEST -> {
                            chestOpen = false
                            try { VoxelsNative.closeContainer() } catch (_: Exception) {}
                        }
                        BackTarget.TRADE -> tradeOpen = false
                        BackTarget.FURNACE -> furnaceOpen = false
                        BackTarget.STONECUTTER -> stonecutterOpen = false
                        BackTarget.INVENTORY -> inventoryOpen = false
                        BackTarget.RESUME -> paused = false
                        BackTarget.PAUSE -> paused = true
                    }
                }

                // The Hub only ever sees the app-level tier: its contract is (gameId, achievementId)
                // with no room for a world.
                GameHubComposeHook("voxels", achievements?.app)

                Box(Modifier.fillMaxSize()) {
                    if (VoxelsNative.isAvailable) {
                        AndroidView(factory = { ctx ->
                            // No opaque background: a SurfaceView shows its Vulkan layer through a
                            // transparent hole in the window. Painting the view black covered that hole.
                            VoxelSurfaceView(ctx)
                        }, modifier = Modifier.fillMaxSize())
                    } else {
                        Box(Modifier.fillMaxSize().background(Color(0xFF0E1A0F)), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.rust_lib_missing_vulkan_unavailable), color = Color.White)
                        }
                    }

                    // Fullscreen world interaction + floating look: drag on the right half looks around;
                    // tap places a block and long-press breaks one — on either half of the screen.
                    if (VoxelsNative.isAvailable) {
                        FloatingLookJoystick(
                            modifier = Modifier.fillMaxSize(),
                            onLookRate = { ry, rp -> if (!paused) try { VoxelsNative.onLookInput(ry, rp) } catch (_: Exception) {} },
                            onPlace = { off -> try {
                                val code = VoxelsNative.placeBlockAt(off.x, off.y)
                                when (code) {
                                    1 -> com.vayunmathur.games.voxels.platform.SoundFx.playPlace()
                                    11 -> { invStartTab = 2; inventoryOpen = true } // crafting table
                                    12, 14 -> { // furnace / blast furnace
                                        furnaceIsBlast = code == 14
                                        smeltJson = try { VoxelsNative.getSmeltJson() } catch (_: Exception) { "{}" }
                                        furnaceOpen = true
                                    }
                                    13 -> { // jukebox: play the held disc (or stop)
                                        val inv = try { com.vayunmathur.games.voxels.ui.voxelsJson.decodeFromString<com.vayunmathur.games.voxels.ui.InventoryState>(inventoryJson) } catch (_: Exception) { null }
                                        val held = inv?.slots?.getOrNull(inv.selected)?.id ?: 0
                                        com.vayunmathur.games.voxels.platform.MusicFx.toggle(this@MainActivity, com.vayunmathur.games.voxels.ui.discTrack[held])
                                    }
                                    15 -> stonecutterOpen = true // stonecutter
                                    // Every villager has its own profession and level, so the stall is
                                    // read fresh from the one that was tapped.
                                    20 -> {
                                        tradesJson = try { VoxelsNative.getTradesJson() } catch (_: Exception) { "{}" }
                                        tradeOpen = true
                                    }
                                    30 -> { // chest
                                        containerJson = try { VoxelsNative.getContainerJson() } catch (_: Exception) { """{"slots":[]}""" }
                                        chestOpen = true
                                        // Online clients mirror host state: ask for the real contents.
                                        if (online) try { VoxelsNative.netRequestContainer() } catch (_: Exception) {}
                                        com.vayunmathur.games.voxels.platform.SoundFx.playPlace()
                                    }
                                    41 -> com.vayunmathur.games.voxels.platform.SoundFx.playPlace() // ignited a portal
                                }
                            } catch (_: Exception) {} },
                            onBreak = { off -> try { if (VoxelsNative.breakBlockAt(off.x, off.y)) com.vayunmathur.games.voxels.platform.SoundFx.playBreak() } catch (_: Exception) {} }
                        )
                    }

                    // Edge swipes over the stick (and over the right-hand look strip below) belong to
                    // the game, not to the system Back gesture.
                    Box(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 96.dp).systemGestureExclusion()) {
                        Joystick(modifier = Modifier, isLook = false,
                            onMove = { x, y -> if (VoxelsNative.isAvailable && !paused) try { VoxelsNative.onMoveInput(x, y) } catch (_: Exception) {} },
                            onLookDelta = { _, _ -> })
                    }

                    // Android honours at most 200 dp of exclusion per edge; spend it where look swipes
                    // start. This Box has no pointer input, so touches still reach the look layer.
                    Box(Modifier.align(Alignment.CenterEnd).width(40.dp).height(200.dp).systemGestureExclusion())

                    // Two stacked action buttons where the look joystick used to be. Walking: Jump / Sneak.
                    // Flying: Up / Down. Double-tap the top button to toggle flying.
                    Column(
                        Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 40.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        HoldButton(
                            label = if (flying) "Up" else "Jump",
                            dimmed = !flying && sneaking,
                            onPress = { if (VoxelsNative.isAvailable) try { VoxelsNative.setJump(true) } catch (_: Exception) {} },
                            onRelease = { if (VoxelsNative.isAvailable) try { VoxelsNative.setJump(false) } catch (_: Exception) {} },
                            onDoubleTap = {
                                if (VoxelsNative.isAvailable) try { VoxelsNative.toggleFly() } catch (_: Exception) {}
                                flying = !flying
                                if (flying) {
                                    sneaking = false
                                    if (VoxelsNative.isAvailable) try { VoxelsNative.setSneak(false) } catch (_: Exception) {}
                                }
                            }
                        )
                        if (flying) {
                            HoldButton(
                                label = stringResource(R.string.down),
                                onPress = { if (VoxelsNative.isAvailable) try { VoxelsNative.setFlyDown(true) } catch (_: Exception) {} },
                                onRelease = { if (VoxelsNative.isAvailable) try { VoxelsNative.setFlyDown(false) } catch (_: Exception) {} }
                            )
                        } else {
                            HoldButton(
                                label = if (sneaking) "Sneaking" else "Sneak",
                                dimmed = sneaking,
                                onPress = {
                                    sneaking = !sneaking
                                    if (VoxelsNative.isAvailable) try { VoxelsNative.setSneak(sneaking) } catch (_: Exception) {}
                                }
                            )
                        }
                    }

                    // Health/effects HUD just above the hotbar.
                    Box(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 78.dp)) {
                        HealthOverlay(healthJson)
                    }
                    // Boss health bar (Ender Dragon) at the top.
                    Box(Modifier.align(Alignment.TopCenter).padding(top = 70.dp)) {
                        BossBar(healthJson)
                    }
                    // Elytra glide indicator, above the hotbar.
                    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 92.dp)) {
                        GlideIndicator(healthJson)
                    }

                    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) {
                        Hotbar(inventoryJson = inventoryJson,
                            onSelect = { slot -> if (VoxelsNative.isAvailable) try { VoxelsNative.selectSlot(slot) } catch (_: Exception) {} },
                            onOpenInventory = { invStartTab = 0; inventoryOpen = true })
                    }

                    Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = 36.dp, start = 12.dp, end = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                IconButton(onClick = { paused = true }) { IconMenu() }
                                IconButton(onClick = { showDebug = !showDebug }) { IconSettings() }
                            }
                        }
                        if (showDebug) { DebugOverlay(debugJson = debugJson) }
                    }

                    newAchievement?.let { ach ->
                        Box(Modifier.align(Alignment.TopCenter).padding(top = 80.dp)) {
                            AchievementNotification(ach) { achievements?.dismissNotification() }
                        }
                    }

                    // Online HUD: how many players are here, plus a connection-lost indicator.
                    if (online) {
                        val peerCount = remember(peersJson) { runCatching { JSONArray(peersJson).length() }.getOrDefault(0) }
                        Box(Modifier.align(Alignment.TopEnd).padding(top = 36.dp, end = 12.dp)) {
                            Text(
                                stringResource(R.string.players_online, peerCount + 1),
                                color = Color.White,
                                modifier = Modifier.background(Color(0x66000000)).padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                        if (!netConnected) {
                            Box(Modifier.align(Alignment.TopCenter).padding(top = 8.dp)) {
                                Text(
                                    stringResource(R.string.connection_lost),
                                    color = Color.White,
                                    modifier = Modifier.background(Color(0xCCB00020)).padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }

                    if (inventoryOpen && VoxelsNative.isAvailable) {
                        InventoryOverlay(
                            inventoryJson = inventoryJson, recipesJson = recipesJson,
                            blessingsJson = blessingsJson, blessingCatalogJson = blessingCatalogJson,
                            onClose = { inventoryOpen = false }, startTab = invStartTab,
                            worldAchievements = achievements?.world,
                        )
                    }

                    if (tradeOpen && VoxelsNative.isAvailable) {
                        TradeOverlay(tradesJson = tradesJson, onClose = { tradeOpen = false })
                    }

                    if (furnaceOpen && VoxelsNative.isAvailable) {
                        FurnaceOverlay(
                            smeltingJson = smeltingJson, smeltJson = smeltJson,
                            isBlast = furnaceIsBlast, onClose = { furnaceOpen = false }
                        )
                    }

                    if (chestOpen && VoxelsNative.isAvailable) {
                        ChestOverlay(containerJson = containerJson, inventoryJson = inventoryJson, onClose = {
                            chestOpen = false
                            try { VoxelsNative.closeContainer() } catch (_: Exception) {}
                        })
                    }

                    if (stonecutterOpen && VoxelsNative.isAvailable) {
                        StonecutterOverlay(cutsJson = cutsJson, inventoryJson = inventoryJson,
                            onClose = { stonecutterOpen = false })
                    }

                    if (loadPhase != LoadPhase.READY && VoxelsNative.isAvailable) {
                        LoadingOverlay(phase = loadPhase, progress = loadProgress)
                    }

                    if (paused) {
                        Box(
                            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
                                .pointerInput(Unit) { detectTapGestures { } }, // swallow taps behind the menu
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Text(stringResource(R.string.paused), style = MaterialTheme.typography.headlineMedium, color = Color.White)
                                Button(onClick = { paused = false }, modifier = Modifier.width(240.dp)) { Text(stringResource(R.string.resume)) }
                                Button(onClick = {
                                    // onDestroy() saves the world and tears down the engine; finishing
                                    // returns to MenuActivity (still on the back stack).
                                    activity?.finish()
                                }, modifier = Modifier.width(240.dp)) { Text(stringResource(R.string.save_quit_to_menu)) }
                            }
                        }
                    }
                }
            }
        }
    }

    // Immersive sticky: the game owns the whole screen; a swipe from an edge peeks the bars briefly.
    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Dialogs, the shade and app switching bring the bars back; hide them again on return.
        if (hasFocus) hideSystemBars()
    }

    // Music follows the screen: it starts/resumes when the game is visible and pauses on Home,
    // the lock screen or another app (calls are handled by audio focus inside MusicFx).
    override fun onStart() {
        super.onStart()
        com.vayunmathur.games.voxels.platform.MusicFx.onForeground(this)
    }

    // A backgrounded game can be killed at any moment without onDestroy, so persist on every stop.
    override fun onStop() {
        paused = true
        com.vayunmathur.games.voxels.platform.MusicFx.onBackground()
        if (VoxelsNative.isAvailable) try { VoxelsNative.nativeSave() } catch (_: Exception) {}
        super.onStop()
    }

    override fun onDestroy() {
        com.vayunmathur.games.voxels.platform.MusicFx.stop()
        VoxelsSync.stopLive()
        if (VoxelsNative.isAvailable) {
            try { VoxelsNative.nativeSetRole(0) } catch (_: Exception) {}
            try { VoxelsNative.nativeOnDestroy() } catch (_: Exception) {}
        }
        super.onDestroy()
    }
}
