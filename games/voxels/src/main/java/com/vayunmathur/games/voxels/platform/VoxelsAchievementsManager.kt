package com.vayunmathur.games.voxels.platform

import android.content.Context
import com.vayunmathur.library.util.AchievementsManager

/**
 * Achievements in Voxels exist at two tiers.
 *
 * The **world** tier tracks one save, so a fresh world starts at zero and its counters mean what they
 * say. The **app** tier is "ever, in any world" — that's the one the Games Hub mirrors, and its keys
 * are unprefixed so it is byte-for-byte what every earlier build wrote.
 *
 * Nothing calls the two managers directly; [VoxelsAchievements] forwards to both so the ~30 call
 * sites in MainActivity stay one line each.
 */
class VoxelsAchievementsManager(
    context: Context,
    json: String,
    keyPrefix: String = "",
) : AchievementsManager(context, json, keyPrefix) {
    override fun checkExistingAchievements() {}
}

/** Two-tier Voxels achievements: per-world progress forwarded to the app-wide Games Hub tier. */
class VoxelsAchievements(context: Context, json: String, worldId: String) {
    /** The tier the Games Hub sees. Unprefixed, and shared by every world. */
    val app = VoxelsAchievementsManager(context, json)

    /** This world's own progress, namespaced by its directory name. */
    val world = VoxelsAchievementsManager(context, json, "world_$worldId:")

    /** Toasts come from the world tier: earning something again in a new world is worth showing. */
    val newAchievement get() = world.newAchievement
    fun dismissNotification() = world.dismissNotification()

    fun unlock(id: String) {
        world.onAchievementUnlocked(id)
        app.onAchievementUnlocked(id)
    }

    /**
     * `progress` is stored monotonically, which is what both tiers want: the world tier's counters
     * only ever grow within that world, and the app tier ends up holding the best across all worlds.
     */
    fun progress(id: String, value: Int) {
        world.onProgressUpdated(id, value)
        app.onProgressUpdated(id, value)
    }
}
