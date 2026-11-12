package com.vayunmathur.games.solitaire.domain

import com.vayunmathur.games.solitaire.data.DrawMode
import com.vayunmathur.games.solitaire.data.GameConfig
import com.vayunmathur.games.solitaire.data.GameMode
import com.vayunmathur.games.solitaire.data.KLONDIKE_DIFFICULTY_ORDER
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * Which seeds may be dealt: one sorted table of solver-proven seeds per mode ×
 * option, generated offline (see games/solitaire/AGENTS.md) and shipped in
 * `assets/solvable_deals.bin`. Every seed in a table was solved to a full win by
 * the matching domain solver, so any deal picked here is winnable.
 *
 * Table keys reuse the stats variant names (`KLONDIKE__DRAW_THREE_HARD`,
 * `SPIDER__4SUIT`, ...), which are already frozen by the persisted stats keys.
 */
object SolvableDeals {

    const val ASSET_NAME = "solvable_deals.bin"

    private val SPIDER_SUIT_OPTIONS = listOf(1, 2, 4)

    /** The stats variant for a config: `<draw>_<difficulty>`, `<n>SUIT`, `STANDARD`, `RELAXED`/`ORIGINAL`. */
    fun variant(mode: GameMode, config: GameConfig): String = when (mode) {
        GameMode.KLONDIKE -> "${config.drawMode.name}_${config.klondikeDifficulty.name}"
        GameMode.SPIDER -> "${config.spiderSuits}SUIT"
        GameMode.FREECELL -> "STANDARD"
        GameMode.PYRAMID -> if (config.relaxed) "RELAXED" else "ORIGINAL"
    }

    fun tableKey(mode: GameMode, config: GameConfig): String = "${mode.name}__${variant(mode, config)}"

    /** Every config the New Game dialog can produce: 8 Klondike, 3 Spider, FreeCell, 2 Pyramid. */
    val allConfigs: List<Pair<GameMode, GameConfig>> = buildList {
        for (draw in DrawMode.entries) {
            for (difficulty in KLONDIKE_DIFFICULTY_ORDER) {
                add(GameMode.KLONDIKE to GameConfig(drawMode = draw, klondikeDifficulty = difficulty))
            }
        }
        for (suits in SPIDER_SUIT_OPTIONS) add(GameMode.SPIDER to GameConfig(spiderSuits = suits))
        add(GameMode.FREECELL to GameConfig())
        add(GameMode.PYRAMID to GameConfig(relaxed = true))
        add(GameMode.PYRAMID to GameConfig(relaxed = false))
    }

}

/** Decoded seed tables, keyed by [SolvableDeals.tableKey]. */
class SolvableDealTables(private val tables: Map<String, IntArray>) {

    fun seeds(mode: GameMode, config: GameConfig): IntArray? =
        tables[SolvableDeals.tableKey(mode, config)]?.takeIf { it.isNotEmpty() }

    /** A uniformly random proven seed for the config, or null if it has no table. */
    fun pick(mode: GameMode, config: GameConfig, random: Random): Long? =
        seeds(mode, config)?.let { it[random.nextInt(it.size)].toLong() }

    /**
     * The same proven seed on every device for a given [epochDay] (days since
     * 1970-01-01), for a daily or shared deal. Consecutive days are scattered
     * across the table by a fixed 64-bit mix, so they do not walk it in order.
     */
    fun seedForDay(mode: GameMode, config: GameConfig, epochDay: Long): Long? =
        seeds(mode, config)?.let { table ->
            table[Math.floorMod(mix64(epochDay xor DAILY_SALT), table.size.toLong()).toInt()].toLong()
        }

    companion object {
        /**
         * Format: `SOL1`, table count (u8), then per table its key (u8 length +
         * ASCII), seed count (varint) and ascending seeds as varint deltas.
         */
        fun decode(bytes: ByteArray): SolvableDealTables {
            val reader = Reader(bytes)
            require(MAGIC.all { reader.byte() == it.code }) { "not a solvable-deal table" }
            val count = reader.byte()
            val tables = LinkedHashMap<String, IntArray>()
            repeat(count) {
                val key = String(CharArray(reader.byte()) { reader.byte().toChar() })
                var seed = 0
                tables[key] = IntArray(reader.varint()) {
                    seed += reader.varint()
                    seed
                }
            }
            return SolvableDealTables(tables)
        }

        fun encode(tables: Map<String, IntArray>): ByteArray {
            val out = ByteArrayOutputStream()
            MAGIC.forEach { out.write(it.code) }
            out.write(tables.size)
            for ((key, seeds) in tables) {
                out.write(key.length)
                key.forEach { out.write(it.code) }
                writeVarint(out, seeds.size)
                var previous = 0
                for (seed in seeds.sorted()) {
                    writeVarint(out, seed - previous)
                    previous = seed
                }
            }
            return out.toByteArray()
        }

        private fun writeVarint(out: ByteArrayOutputStream, value: Int) {
            var v = value
            while (v >= VARINT_HIGH) {
                out.write((v and VARINT_LOW) or VARINT_HIGH)
                v = v ushr VARINT_SHIFT
            }
            out.write(v)
        }

        private const val MAGIC = "SOL1"
        private const val DAILY_SALT = 0x5EED_DA11L
        private const val VARINT_HIGH = 0x80
        private const val VARINT_LOW = 0x7F
        private const val VARINT_SHIFT = 7
    }

    private class Reader(private val bytes: ByteArray) {
        private var pos = 0

        fun byte(): Int = bytes[pos++].toInt() and 0xFF

        fun varint(): Int {
            var result = 0
            var shift = 0
            while (true) {
                val b = byte()
                result = result or ((b and VARINT_LOW) shl shift)
                if (b < VARINT_HIGH) return result
                shift += VARINT_SHIFT
            }
        }
    }
}
