package com.vayunmathur.games.unblockjam.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The board a player has left part-way through, one per level, so a puzzle picks up where it was
 * after Back, a switch to another puzzle, or the process being killed.
 *
 * Only block positions are stored (see [SavedStep]), for the current board and for every undo
 * step; the level itself always comes from the shipped (or generated) data. A save that no longer
 * fits its level - a pack update moved a wall, say - is ignored rather than trusted.
 */
class InProgressBoards(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The saved board and undo history for [level], or null if there is none or it does not fit. */
    fun load(level: LevelData): SavedProgress? {
        val raw = prefs.getString(level.id, null) ?: return null
        val saved = runCatching { Json.decodeFromString<SavedBoard>(raw) }.getOrNull() ?: return null
        val current = saved.current.applyTo(level) ?: return null
        val history = saved.history.map { it.applyTo(level) ?: return null }
        return SavedProgress(current, history)
    }

    /** Remembers [current] and its undo [history] for the level they belong to. */
    fun save(current: LevelData, history: List<LevelData>) {
        val saved = SavedBoard(current = current.toStep(), history = history.map { it.toStep() })
        prefs.edit { putString(current.id, Json.encodeToString(saved)) }
    }

    /** Forgets [levelId]'s board: it was solved or restarted. */
    fun clear(levelId: String) {
        prefs.edit { remove(levelId) }
    }

    /** Drops saved daily boards from other days; only today's pack is playable. */
    fun retainDaily(todayIds: Set<String>) {
        val stale = prefs.all.keys.filter { it.startsWith(DAILY_PREFIX) && it !in todayIds }
        if (stale.isNotEmpty()) prefs.edit { stale.forEach { remove(it) } }
    }

    private companion object {
        const val PREFS_NAME = "in_progress_boards"
        const val DAILY_PREFIX = "daily_"
    }
}

/** A restored board: [current] as it was left, and the undo steps that led to it, oldest first. */
data class SavedProgress(val current: LevelData, val history: List<LevelData>)

@Serializable
private data class SavedBoard(val current: SavedStep, val history: List<SavedStep>)

/**
 * One board: block positions as `[x, y]` in level order, and which block moved last - the
 * ViewModel uses that to fold consecutive drags of one block into a single move, so an undo step
 * restored without it would count the next drag of that block as a new move.
 */
@Serializable
private data class SavedStep(val blocks: List<List<Int>>, val lastMoved: Int? = null)

private fun LevelData.toStep() =
    SavedStep(blocks.map { listOf(it.position.x, it.position.y) }, lastMovedBlockIndex)

/**
 * [level] with its blocks moved to this step's positions, or null if they do not describe a legal board
 * for it: wrong block count, a fixed block moved, a block off its axis or overlapping another.
 */
private fun SavedStep.applyTo(level: LevelData): LevelData? {
    if (blocks.size != level.blocks.size || blocks.any { it.size != 2 }) return null
    val moved = level.blocks.mapIndexed { i, block ->
        val (x, y) = blocks[i]
        val offAxis = if (block.dimension.width > block.dimension.height) y != block.position.y
        else x != block.position.x
        val fixedMoved = block.fixed && Coord(x, y) != block.position
        if (offAxis || fixedMoved) return null
        block.copy(position = Coord(x, y))
    }
    val legal = moved.indices.all { i ->
        val block = moved[i]
        val inside = block.position.x >= 0 && block.position.y >= 0 &&
            block.position.x + block.dimension.width <= level.dimension.width &&
            block.position.y + block.dimension.height <= level.dimension.height
        inside && moved.indices.none { j -> j != i && overlaps(block, moved[j]) }
    }
    return if (legal) level.copy(blocks = moved, lastMovedBlockIndex = lastMoved) else null
}

private fun overlaps(a: Block, b: Block): Boolean =
    a.position.x < b.position.x + b.dimension.width &&
        a.position.x + a.dimension.width > b.position.x &&
        a.position.y < b.position.y + b.dimension.height &&
        a.position.y + a.dimension.height > b.position.y
