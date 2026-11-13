package com.vayunmathur.games.voxels.data

import android.content.Context
import androidx.core.content.edit

/** Player preferences from the in-game settings sheet. */
data class GameSettings(
    /** Multiplier on camera turn speed, [MIN_SENSITIVITY]..[MAX_SENSITIVITY]. */
    val lookSensitivity: Float = 1f,
    /** Pushing the look control up looks down (flight-stick style). */
    val invertY: Boolean = false,
    /** Music volume, 0..1. */
    val musicVolume: Float = 1f,
    /** Sound-effects volume, 0..1. */
    val sfxVolume: Float = 1f,
    /** Size of the on-screen controls and HUD, [MIN_HUD_SCALE]..[MAX_HUD_SCALE]. */
    val hudScale: Float = 1f,
    /** Show the engine's debug readout (fps, position, chunk counts). Off by default. */
    val showDebug: Boolean = false,
    /** Look with the old floating rate stick instead of swiping (swipe-to-look is the default). */
    val stickLook: Boolean = false,
) {
    /** Bounds for the sliders. */
    companion object {
        /** Slowest camera. */
        const val MIN_SENSITIVITY = 0.25f
        /** Fastest camera. */
        const val MAX_SENSITIVITY = 3f
        /** Smallest HUD. */
        const val MIN_HUD_SCALE = 0.75f
        /** Largest HUD. */
        const val MAX_HUD_SCALE = 1.5f
    }

    /** The same settings with every value forced into its valid range. */
    fun sanitized(): GameSettings = copy(
        lookSensitivity = lookSensitivity.coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY),
        musicVolume = musicVolume.coerceIn(0f, 1f),
        sfxVolume = sfxVolume.coerceIn(0f, 1f),
        hudScale = hudScale.coerceIn(MIN_HUD_SCALE, MAX_HUD_SCALE),
    )
}

/** Persists [GameSettings] in a small app-private preferences file shared by every world. */
object GameSettingsStore {
    private const val FILE = "voxels_settings"

    /** Loads saved settings, or the defaults on first run. */
    fun load(ctx: Context): GameSettings {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val d = GameSettings()
        return GameSettings(
            lookSensitivity = p.getFloat("look_sensitivity", d.lookSensitivity),
            invertY = p.getBoolean("invert_y", d.invertY),
            musicVolume = p.getFloat("music_volume", d.musicVolume),
            sfxVolume = p.getFloat("sfx_volume", d.sfxVolume),
            hudScale = p.getFloat("hud_scale", d.hudScale),
            showDebug = p.getBoolean("show_debug", d.showDebug),
            stickLook = p.getBoolean("stick_look", d.stickLook),
        ).sanitized()
    }

    /** Saves [s] (asynchronously). */
    fun save(ctx: Context, s: GameSettings) {
        val v = s.sanitized()
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
            putFloat("look_sensitivity", v.lookSensitivity)
            putBoolean("invert_y", v.invertY)
            putFloat("music_volume", v.musicVolume)
            putFloat("sfx_volume", v.sfxVolume)
            putFloat("hud_scale", v.hudScale)
            putBoolean("show_debug", v.showDebug)
            putBoolean("stick_look", v.stickLook)
        }
    }
}
