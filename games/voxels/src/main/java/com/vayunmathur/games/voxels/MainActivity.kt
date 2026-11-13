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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.vayunmathur.games.voxels.data.GameSettingsStore
import com.vayunmathur.games.voxels.data.WorldMode
import com.vayunmathur.games.voxels.domain.BackTarget
import com.vayunmathur.games.voxels.domain.NetStatus
import com.vayunmathur.games.voxels.network.JoinRequests
import com.vayunmathur.games.voxels.network.MultiplayerPump
import com.vayunmathur.games.voxels.network.VoxelsSync
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
import com.vayunmathur.games.voxels.ui.SettingsSheet
import com.vayunmathur.games.voxels.ui.StonecutterOverlay
import com.vayunmathur.games.voxels.ui.JoinRequestChip
import com.vayunmathur.games.voxels.ui.OnlineHud
import com.vayunmathur.games.voxels.ui.TradeOverlay
import com.vayunmathur.games.voxels.ui.UseButton
import com.vayunmathur.games.voxels.ui.VoxelSurfaceView
import com.vayunmathur.games.voxels.ui.VoxelsTheme
import com.vayunmathur.games.voxels.platform.VoxelsAchievements
import com.vayunmathur.games.voxels.util.VoxelsNative
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import kotlin.math.PI

// Queued world-tap kinds (engine_part1.rs ACTION_*).
private const val ACTION_PLACE = 0
private const val ACTION_ACT = 1

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
        // Absent for worlds launched by older menus: those are creative, as they always were.
        val creative = intent.getStringExtra("world_mode") != WorldMode.SURVIVAL
        if (VoxelsNative.isAvailable) {
            // Put the engine in host/client mode before the engine thread starts ticking.
            if (online) try { VoxelsNative.nativeSetRole(netRole) } catch (_: Exception) {}
            try { VoxelsNative.nativeSetCreative(creative) } catch (_: Exception) {}
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
                // Player settings (gear button). The debug readout is now one of them, off by default.
                var settings by remember { mutableStateOf(GameSettingsStore.load(this@MainActivity)) }
                var settingsOpen by remember { mutableStateOf(false) }
                LaunchedEffect(settings) {
                    com.vayunmathur.games.voxels.platform.MusicFx.setVolume(settings.musicVolume)
                    com.vayunmathur.games.voxels.platform.SoundFx.volume = settings.sfxVolume
                    if (VoxelsNative.isAvailable) try { VoxelsNative.setLookSensitivity(settings.lookSensitivity) } catch (_: Exception) {}
                    GameSettingsStore.save(this@MainActivity, settings)
                }
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
                var netStatus by remember { mutableStateOf<NetStatus>(NetStatus.Connecting) }
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
                // Size of the world view in px; the Use button aims at its centre (the crosshair).
                var worldSize by remember { mutableStateOf(IntSize.Zero) }
                // Results of queued world taps: sounds, and the menus an interactive block opens.
                LaunchedEffect(Unit) {
                    while (isActive && VoxelsNative.isAvailable) {
                        val results = try { JSONArray(VoxelsNative.drainActionResults()) } catch (_: Exception) { JSONArray() }
                        for (i in 0 until results.length()) {
                            val r = results.optJSONArray(i) ?: continue
                            val kind = r.optInt(0)
                            val code = r.optInt(1)
                            // Mine/attack/use: a sound when something happened. Place/interact below.
                            if (kind == ACTION_ACT) {
                                if (code == 1) com.vayunmathur.games.voxels.platform.SoundFx.playBreak()
                            } else when (code) {
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
                        }
                        delay(16)
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

                // Host only: poll for join requests to this world while playing, so the host can let a
                // friend in without quitting to the menu.
                val scope = rememberCoroutineScope()
                var joinRequests by remember { mutableStateOf<List<VoxelsSync.JoinRequest>>(emptyList()) }
                LaunchedEffect(online, netRole) {
                    if (!online || netRole != 1) return@LaunchedEffect
                    while (isActive) {
                        joinRequests = JoinRequests.refresh(this@MainActivity, acceptInvites = false)
                            .filter { it.worldId == sharedWorldId }
                        delay(JoinRequests.POLL_MS)
                    }
                }

                // --- Online multiplayer pump (network/MultiplayerPump.kt): bridges the Rust net
                // queues to the encrypted relay and reports connection state + who is here. ---
                LaunchedEffect(online) {
                    if (!online || !VoxelsNative.isAvailable) return@LaunchedEffect
                    MultiplayerPump.run(
                        context = this@MainActivity,
                        params = MultiplayerPump.Params(sharedWorldId, worldKeyB64, ownerDevice, netRole, worldName),
                        onStatus = { netStatus = it },
                        onPeers = { peersJson = it },
                    )
                }

                // "Paused" is real: the engine stops simulating and every held control is released,
                // so closing the menu never resumes a walk or a turn that was in progress.
                // The settings sheet also holds the world still while it's open.
                LaunchedEffect(paused, settingsOpen) {
                    if (!VoxelsNative.isAvailable) return@LaunchedEffect
                    try {
                        VoxelsNative.nativeSetPaused(paused || settingsOpen)
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
                        stonecutter = stonecutterOpen, inventory = inventoryOpen, settings = settingsOpen, paused = paused,
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
                        BackTarget.SETTINGS -> settingsOpen = false
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

                    // Fullscreen world interaction: a drag that starts on the right half looks around
                    // (swipe by default, or the floating stick); tap places a block, hold mines/attacks
                    // (repeating) — on either half of the screen, with any finger, even while looking.
                    if (VoxelsNative.isAvailable) {
                        FloatingLookJoystick(
                            modifier = Modifier.fillMaxSize().onSizeChanged { worldSize = it },
                            onLookRate = { ry, rp ->
                                if (!paused) try { VoxelsNative.onLookInput(ry, if (settings.invertY) -rp else rp) } catch (_: Exception) {}
                            },
                            // Swipe-look: the camera follows the finger; a swipe across the whole
                            // view width turns 180 degrees (before the sensitivity setting).
                            swipeLook = !settings.stickLook,
                            onLookDelta = { dx, dy ->
                                val w = worldSize.width.coerceAtLeast(1).toFloat()
                                val k = PI.toFloat() / w
                                if (!paused) try {
                                    VoxelsNative.onLookDelta(-dx * k, (if (settings.invertY) -dy else dy) * k)
                                } catch (_: Exception) {}
                            },
                            // Taps are queued for the engine's next frame (never blocking on its lock);
                            // results come back through the drain loop below.
                            onPlace = { off -> if (!paused) try { VoxelsNative.queueAction(ACTION_PLACE, off.x, off.y) } catch (_: Exception) {} },
                            onBreak = { off -> if (!paused) try { VoxelsNative.queueAction(ACTION_ACT, off.x, off.y) } catch (_: Exception) {} }
                        )
                    }

                    // HUD layer, scaled by the HUD-size setting (a density override scales every dp in it)
                    // and kept inside the safe area, so display cutouts, rounded corners and transient
                    // system bars never cover the stick, buttons, hotbar or readouts. The hard-coded
                    // offsets below are now relative to that safe area.
                    val baseDensity = LocalDensity.current
                    CompositionLocalProvider(
                        LocalDensity provides Density(baseDensity.density * settings.hudScale, baseDensity.fontScale)
                    ) {
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
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

                        // Right-hand action cluster: Fly (creative) over Use, beside two stacked buttons
                        // (walking: Jump / Sneak, flying: Up / Down). It sits above the
                        // hotbar (8 dp slot padding + 52 dp slots + 12 dp offset) so Sneak no longer
                        // covers the last hotbar slots.
                        Row(
                            Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 88.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                // Creative only: flight has its own toggle (it used to hide behind a double-tap on Jump,
                                // which also fired by accident when jumping repeatedly).
                                if (creative) {
                                    HoldButton(
                                        label = stringResource(if (flying) R.string.land else R.string.fly),
                                        onPress = {
                                            if (VoxelsNative.isAvailable) try { VoxelsNative.toggleFly() } catch (_: Exception) {}
                                            flying = !flying
                                            if (flying) {
                                                sneaking = false
                                                if (VoxelsNative.isAvailable) try { VoxelsNative.setSneak(false) } catch (_: Exception) {}
                                            }
                                        },
                                    )
                                }
                                // Use/attack whatever is under the crosshair, repeating while held.
                                UseButton(label = stringResource(R.string.use)) {
                                    if (VoxelsNative.isAvailable && !paused && worldSize.width > 0) try {
                                        VoxelsNative.queueAction(ACTION_ACT, worldSize.width / 2f, worldSize.height / 2f)
                                    } catch (_: Exception) {}
                                }
                            }
                            Column(
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                HoldButton(
                                    label = if (flying) "Up" else "Jump",
                                    dimmed = !flying && sneaking,
                                    onPress = { if (VoxelsNative.isAvailable) try { VoxelsNative.setJump(true) } catch (_: Exception) {} },
                                    onRelease = { if (VoxelsNative.isAvailable) try { VoxelsNative.setJump(false) } catch (_: Exception) {} },
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
                                    IconButton(onClick = { settingsOpen = true }) { IconSettings() }
                                }
                            }
                            if (settings.showDebug) { DebugOverlay(debugJson = debugJson) }
                        }

                        newAchievement?.let { ach ->
                            Box(Modifier.align(Alignment.TopCenter).padding(top = 80.dp)) {
                                AchievementNotification(ach) { achievements?.dismissNotification() }
                            }
                        }

                            // Online HUD: players here, host presence, connection state, join/leave notices,
                        // and for the host, anyone asking to join right now.
                        if (online) {
                            Column(
                                Modifier.align(Alignment.TopEnd).padding(top = 36.dp, end = 12.dp),
                                horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                OnlineHud(status = netStatus, peersJson = peersJson, ownerDevice = ownerDevice, isHost = netRole == 1)
                                joinRequests.firstOrNull()?.let { req ->
                                    JoinRequestChip(
                                        player = req.name.ifBlank { req.requesterId.take(8) },
                                        onApprove = {
                                            joinRequests = joinRequests - req
                                            scope.launch { JoinRequests.approve(this@MainActivity, req) }
                                        },
                                        onDeny = {
                                            joinRequests = joinRequests - req
                                            JoinRequests.deny(this@MainActivity, req)
                                        },
                                    )
                                }
                            }
                        }
                    }
                    }

                    if (inventoryOpen && VoxelsNative.isAvailable) {
                        InventoryOverlay(
                            inventoryJson = inventoryJson, recipesJson = recipesJson,
                            blessingsJson = blessingsJson, blessingCatalogJson = blessingCatalogJson,
                            onClose = { inventoryOpen = false }, startTab = invStartTab, creative = creative,
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

                    if (settingsOpen) {
                        SettingsSheet(settings = settings, onChange = { settings = it.sanitized() }, onClose = { settingsOpen = false })
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
