// PACKAGE STRUCTURE EXCEPTION (JNI): FQN frozen for native RegisterNatives/symbol mangling
// NOTE: file lives under platform/ but package is ...kart.util to match the native symbol
@file:Suppress("InvalidPackageDeclaration")
package com.vayunmathur.games.kart.util

import com.vayunmathur.library.log.Log

/**
 * The engine bridge, shaped like `VoxelsNative.onMoveInput`.
 *
 * Native calls are no-ops behind [isAvailable] = false (no `libkart_engine`
 * on this device), and the game runs on [com.vayunmathur.games.kart.domain.PlaceholderRace]
 * instead. The signatures below are the contract the Rust engine implements,
 * so the Kotlin side never changes shape when native lands - only this object's body does.
 *
 * Surface lifecycle mirrors voxels: the view calls surfaceCreated/Changed/
 * Destroyed, the engine spawns its own 60 Hz render thread and ticks the race
 * there. Drive samples feed per seat per tick; the race itself (seats, AI,
 * items, laps) is started with the grid order and read back as standings JSON
 * for the HUD, so the placeholder and native stay swappable.
 */
@Suppress("TooManyFunctions")
object KartNative {
    /** False until `libkart_engine` ships. The game checks this once per surface, like voxels. */
    var isAvailable: Boolean = try {
        System.loadLibrary("kart_engine")
        nativeInit()
        Log.status("KartNative", "libkart_engine loaded")
        true
    } catch (t: Throwable) {
        Log.status("KartNative", "libkart_engine absent - running the placeholder race")
        false
    }
        // Internal so host tests can force the placeholder race on machines with the engine.
        internal set

    /**
     * Start (or restart) the native race. `playerIds` is the grid in seat order;
     * the engine fills the rest with AI. No-op without the library.
     */
    fun startRace(playerIds: List<String>) {
        if (!isAvailable) return
        nativeStartRace(playerIds.joinToString(","))
    }

    /** One player's control for this tick: throttle/steering/brake normalized, drift/item edges. */
    fun onDriveInput(player: Int, throttle: Float, steering: Float, brake: Float) {
        if (!isAvailable) return
        nativeOnDriveInput(player, throttle, steering, brake)
    }

    fun setDrift(player: Int, held: Boolean) {
        if (!isAvailable) return
        nativeSetDrift(player, held)
    }

    fun useItem(player: Int) {
        if (!isAvailable) return
        nativeUseItem(player)
    }

    /** Snapshot for the HUD/standings: JSON `[{player,lap,progress,place}]`. */
    fun getStandingsJson(): String = if (isAvailable) nativeGetStandingsJson() else "[]"

    /** Surface lifecycle, called by KartSurfaceView. No-ops without the library. */
    fun surfaceCreated(surface: android.view.Surface) {
        if (!isAvailable) return
        nativeSurfaceCreated(surface)
    }

    fun surfaceChanged(width: Int, height: Int) {
        if (!isAvailable) return
        nativeSurfaceChanged(width, height)
    }

    fun surfaceDestroyed() {
        if (!isAvailable) return
        nativeSurfaceDestroyed()
    }

    fun onDestroy() {
        if (!isAvailable) return
        nativeOnDestroy()
    }

    private external fun nativeInit(): Boolean
    private external fun nativeStartRace(playerIdsCsv: String)
    private external fun nativeOnDriveInput(player: Int, throttle: Float, steering: Float, brake: Float)
    private external fun nativeSetDrift(player: Int, held: Boolean)
    private external fun nativeUseItem(player: Int)
    private external fun nativeGetStandingsJson(): String
    private external fun nativeSurfaceCreated(surface: android.view.Surface)
    private external fun nativeSurfaceChanged(width: Int, height: Int)
    private external fun nativeSurfaceDestroyed()
    private external fun nativeOnDestroy()
}
