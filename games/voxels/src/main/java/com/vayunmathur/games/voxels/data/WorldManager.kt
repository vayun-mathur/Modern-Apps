package com.vayunmathur.games.voxels.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.vayunmathur.games.voxels.network.VoxelsRoles
import com.vayunmathur.games.voxels.platform.VoxelsAchievementsManager
import java.io.File
import kotlin.random.Random

// On-disk metadata for a single world, stored as `world.json` inside the world's directory.
// The `online` fields are absent on legacy single-player saves (defaults keep them loading).
@Serializable
data class WorldMeta(
    val name: String,
    val seed: Int,
    val created: Long,
    val lastPlayed: Long,
    // --- Online multiplayer (see VoxelsSync). Empty/false on local single-player worlds. ---
    val online: Boolean = false,
    val worldId: String = "",
    val ownerDeviceId: String = "",
    val role: String = "",
    val keyB64: String = "",
    // Game mode, picked at creation. Worlds saved before modes existed have no field and load as
    // creative, which is exactly how they always played.
    val mode: String = WorldMode.CREATIVE,
)

/** World game modes ([WorldMeta.mode]). */
object WorldMode {
    /** Flight and the free block catalog. */
    const val CREATIVE = "creative"
    /** No flight, no catalog, and a new world starts with an empty inventory. */
    const val SURVIVAL = "survival"
}

// A world plus its resolved id (directory name) and absolute save path. Not persisted directly.
data class WorldInfo(
    val id: String,
    val dir: String,
    val meta: WorldMeta,
)

object WorldManager {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private fun worldsRoot(ctx: Context): File = File(ctx.filesDir, "worlds").apply { mkdirs() }

    // All worlds, most-recently-played first.
    fun listWorlds(ctx: Context): List<WorldInfo> {
        val root = worldsRoot(ctx)
        val dirs = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val metaFile = File(dir, "world.json")
            if (!metaFile.exists()) return@mapNotNull null
            try {
                val meta = json.decodeFromString<WorldMeta>(metaFile.readText())
                WorldInfo(id = dir.name, dir = dir.absolutePath, meta = meta)
            } catch (_: Exception) { null }
        }.sortedByDescending { it.meta.lastPlayed }
    }

    // Resolve a free-text seed field to an Int: a number is used directly; other text is hashed;
    // blank produces a fresh random seed.
    fun resolveSeed(text: String): Int {
        val t = text.trim()
        if (t.isEmpty()) return Random.nextInt()
        t.toIntOrNull()?.let { return it }
        t.toLongOrNull()?.let { return it.toInt() }
        return t.hashCode()
    }

    fun createWorld(ctx: Context, name: String, seed: Int, now: Long, mode: String = WorldMode.CREATIVE): WorldInfo {
        val root = worldsRoot(ctx)
        // Unique directory id derived from the creation time (worlds can share display names).
        var id = "w_$now"
        var i = 1
        while (File(root, id).exists()) { id = "w_${now}_$i"; i++ }
        val dir = File(root, id).apply { mkdirs() }
        val displayName = name.trim().ifEmpty { "New World" }
        val meta = WorldMeta(name = displayName, seed = seed, created = now, lastPlayed = now, mode = mode)
        File(dir, "world.json").writeText(json.encodeToString(WorldMeta.serializer(), meta))
        return WorldInfo(id = id, dir = dir.absolutePath, meta = meta)
    }

    // Online worlds hosted or shared with this device. Local single-player worlds are excluded.
    fun onlineWorlds(ctx: Context): List<WorldInfo> = listWorlds(ctx).filter { it.meta.online }

    // Host a new online world: a normal local save (the host is the authority) tagged with the
    // owner role + the AES content key it will share via PQC-sealed invites.
    fun createOnlineWorld(
        ctx: Context, name: String, seed: Int, worldId: String, keyB64: String,
        ownerDeviceId: String, now: Long,
    ): WorldInfo {
        val root = worldsRoot(ctx)
        var id = "w_$now"
        var i = 1
        while (File(root, id).exists()) { id = "w_${now}_$i"; i++ }
        val dir = File(root, id).apply { mkdirs() }
        val meta = WorldMeta(
            name = name.trim().ifEmpty { "Online World" }, seed = seed, created = now, lastPlayed = now,
            online = true, worldId = worldId, ownerDeviceId = ownerDeviceId,
            role = VoxelsRoles.OWNER, keyB64 = keyB64,
        )
        File(dir, "world.json").writeText(json.encodeToString(WorldMeta.serializer(), meta))
        return WorldInfo(id = id, dir = dir.absolutePath, meta = meta)
    }

    // Add (or return the existing entry for) a world shared with this device via an invite. Deduped
    // by the shared worldId so re-accepting an invite doesn't create a second copy.
    fun upsertSharedWorld(
        ctx: Context, name: String, seed: Int, worldId: String, keyB64: String,
        ownerDeviceId: String, role: String, now: Long,
    ): WorldInfo {
        listWorlds(ctx).firstOrNull { it.meta.online && it.meta.worldId == worldId }?.let { return it }
        val root = worldsRoot(ctx)
        var id = "w_$now"
        var i = 1
        while (File(root, id).exists()) { id = "w_${now}_$i"; i++ }
        val dir = File(root, id).apply { mkdirs() }
        val meta = WorldMeta(
            name = name.trim().ifEmpty { "Shared World" }, seed = seed, created = now, lastPlayed = now,
            online = true, worldId = worldId, ownerDeviceId = ownerDeviceId, role = role, keyB64 = keyB64,
        )
        File(dir, "world.json").writeText(json.encodeToString(WorldMeta.serializer(), meta))
        return WorldInfo(id = id, dir = dir.absolutePath, meta = meta)
    }

    // Bump lastPlayed so the world floats to the top of the list.
    fun touch(ctx: Context, id: String, now: Long) {
        val dir = File(worldsRoot(ctx), id)
        val metaFile = File(dir, "world.json")
        if (!metaFile.exists()) return
        try {
            val meta = json.decodeFromString<WorldMeta>(metaFile.readText())
            metaFile.writeText(json.encodeToString(WorldMeta.serializer(), meta.copy(lastPlayed = now)))
        } catch (_: Exception) {}
    }

    fun deleteWorld(ctx: Context, id: String) {
        File(worldsRoot(ctx), id).deleteRecursively()
        // The world's achievement tier lives in the app-wide DataStore rather than in its directory,
        // so it has to be dropped by hand or it outlives the world forever.
        val json = ctx.assets.open("achievements.json").bufferedReader().use { it.readText() }
        val tier = VoxelsAchievementsManager(ctx, json, "world_$id:")
        CoroutineScope(Dispatchers.IO).launch { tier.forgetAll() }
    }

    // --- Handled join requests ---
    //
    // The inbox is always pulled from seq 0, so an approved/denied request would otherwise reappear
    // on every refresh (its sealed blob lives in the log forever). We persist a small set of handled
    // "worldId|requesterId" keys to suppress those, mirroring how shared worlds dedup by worldId.

    private fun handledFile(ctx: Context): File = File(ctx.filesDir, "voxels_handled_requests.json")

    fun handledRequests(ctx: Context): Set<String> {
        val f = handledFile(ctx)
        if (!f.exists()) return emptySet()
        return try { json.decodeFromString<Set<String>>(f.readText()) } catch (_: Exception) { emptySet() }
    }

    fun markRequestHandled(ctx: Context, key: String) {
        val updated = handledRequests(ctx) + key
        runCatching { handledFile(ctx).writeText(json.encodeToString(updated)) }
    }
}
