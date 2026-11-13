// PACKAGE STRUCTURE EXCEPTION (JNI): FQN frozen for native RegisterNatives/symbol mangling
// NOTE: file lives under platform/ but package is ...voxels.util to match the native symbol
@file:Suppress("InvalidPackageDeclaration")
package com.vayunmathur.games.voxels.util

import com.vayunmathur.library.log.Log
import android.view.Surface

// JNI bridge: one extern fun per native entry point (count is inherent to the
// engine surface), and loadLibrary must catch Throwable (UnsatisfiedLinkError).
@Suppress("TooManyFunctions", "TooGenericExceptionCaught")
object VoxelsNative {
    val isAvailable: Boolean = try {
        System.loadLibrary("voxels_engine")
        Log.status("VoxelsNative", "libvoxels_engine loaded")
        true
    } catch (t: Throwable) {
        Log.error("VoxelsNative", "System.loadLibrary(voxels_engine) failed", t)
        false
    }
    // Starts loading the world on the engine thread and returns at once; poll nativeLoadState.
    external fun nativeInit(filesDir: String, seed: Int): Boolean
    // 0 idle, 1 generating, 2 meshing, 3 ready (see ui/LoadingOverlay.kt LoadPhase).
    external fun nativeLoadState(): Int
    // Meshing progress 0..1 for the loading overlay.
    external fun nativeLoadProgress(): Float
    external fun surfaceCreated(surface: Surface)
    external fun surfaceChanged(width: Int, height: Int)
    external fun surfaceDestroyed()
    external fun nativeOnDestroy()
    // Writes player, chests and edited chunks now (called from onStop; the engine also autosaves).
    external fun nativeSave()
    // Pause menu: stops single-player simulation (frames keep drawing) and releases held input.
    external fun nativeSetPaused(paused: Boolean)
    external fun onMoveInput(moveX: Float, moveY: Float)
    external fun onLookInput(lookYawRate: Float, lookPitchRate: Float)
    // Camera turn-speed multiplier from the settings sheet (0.25..3).
    external fun setLookSensitivity(sensitivity: Float)
    external fun setJump(held: Boolean)
    external fun setFlyDown(held: Boolean)
    external fun setSneak(on: Boolean)
    external fun toggleFly()
    external fun breakBlockAt(x: Float, y: Float): Boolean
    // 0 = nothing, 1 = placed, 11 crafting table, 12 furnace, 13 jukebox, 14 blast furnace,
    // 20 villager trade, 30 chest opened, 41 portal lit.
    external fun placeBlockAt(x: Float, y: Float): Int
    external fun selectSlot(slot: Int)
    external fun moveItem(from: Int, to: Int)
    external fun giveBlock(id: Int)
    external fun craft(recipe: Int): Boolean
    // Stonecutter: one block in, one shape out.
    external fun cut(idx: Int): Boolean
    external fun getCutsJson(): String
    external fun trade(index: Int): Boolean
    external fun getRecipesJson(): String
    external fun getTradesJson(): String
    external fun getInventoryJson(): String
    external fun getDebugJson(): String
    external fun getStatsJson(): String
    external fun getHealthJson(): String
    // Furnace: recipe catalog, live job state, and start/stop.
    external fun getSmeltingJson(): String
    external fun getSmeltJson(): String
    external fun getAmbienceJson(): String
    external fun startSmelt(recipe: Int, blast: Boolean): Boolean
    external fun stopSmelt()
    // Chest containers, keyed by the block the player last opened.
    external fun getContainerJson(): String
    external fun containerTake(idx: Int): Boolean
    external fun containerPut(idx: Int): Boolean
    external fun closeContainer()
    // Blessings: permanent attunements bound from charms in the inventory.
    external fun getBlessingsJson(): String
    external fun getBlessingCatalogJson(): String
    external fun attuneBlessing(idx: Int): Boolean
    external fun releaseBlessing(slot: Int): Boolean
    // --- Online multiplayer bridge (see net.rs). Transport lives in Kotlin (VoxelsSync). ---
    // role: 0 offline, 1 host, 2 client.
    external fun nativeSetRole(role: Int)
    // This device's sync id, stamped onto outbound PlayerTransform frames.
    external fun nativeSetDevice(device: String)
    // Kotlin -> Rust: feed a decrypted, already-authorized NetMsg (or JSON array of them).
    external fun netPushInbound(json: String): Boolean
    // Rust -> Kotlin: pull queued outbound NetMsgs as a JSON array to encrypt + relay.
    external fun netDrainOutbound(): String
    // Remote players for the HUD/avatars: [{device,x,y,z,yaw}].
    external fun getPeersJson(): String
    // Client → host: ask for the currently-open chest's contents (host replies with ContainerSync).
    external fun netRequestContainer()
}
