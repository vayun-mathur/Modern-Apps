package com.vayunmathur.games.voxels.ui

import kotlinx.serialization.Serializable

/** One active status effect from the engine's health JSON. */
@Serializable
data class EffJson(val k: String = "", val amp: Int = 0, val t: Int = 0)

/** The engine's health/HUD JSON (see engine_part1.rs publish_ui). */
@Serializable
data class HealthJson(
    val hp: Float = 20f, val max: Float = 20f, val absorb: Float = 0f, val dead: Boolean = false,
    val estus: Int = 0, val effects: List<EffJson> = emptyList(), val boss: Float = -1f,
    val bossName: String = "", val elytra: Boolean = false, val gliding: Boolean = false,
)

/** Parses [json], falling back to defaults on anything malformed. */
internal fun parseHealth(json: String): HealthJson =
    try { voxelsJson.decodeFromString<HealthJson>(json) } catch (_: Exception) { HealthJson() }
