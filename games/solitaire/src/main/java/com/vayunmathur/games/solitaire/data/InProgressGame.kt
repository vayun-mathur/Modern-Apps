package com.vayunmathur.games.solitaire.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Full-encode codec for [SolitaireUiState], following the pipes/unblockjam approach.
 *
 * The smaller alternative — seed plus replay — is not correct here: no move list is
 * kept, only undo snapshots, and drag/auto-complete moves cannot be re-derived from
 * the seed alone. So the current board and its undo history are encoded in full:
 * each card is one int (`suit * 13 + rank - 1`), piles are int arrays.
 */
object SolitaireProgressCodec {
    private const val VERSION = 1
    private const val NO_CARD = -1
    private const val DECK_CARDS = 52
    private const val SPIDER_CARDS = 104

    fun encode(state: SolitaireUiState): String = buildJsonObject {
        put("v", VERSION)
        put("mode", state.gameMode?.name)
        state.klondike?.let { put("klondike", encodeKlondike(it)) }
        state.spider?.let { put("spider", encodeSpider(it)) }
        state.freeCell?.let { put("freeCell", encodeFreeCell(it)) }
        state.pyramid?.let { put("pyramid", encodePyramid(it)) }
        put("h", JsonArray(state.history.map { encodeAny(it) }))
    }.toString()

    fun decode(json: String, mode: GameMode): SolitaireUiState? = runCatching {
        val root = Json.parseToJsonElement(json).jsonObject
        if (root["v"]?.jsonPrimitive?.int != VERSION) return null
        if (root["mode"]?.jsonPrimitive?.content != mode.name) return null
        val history = root.getValue("h").jsonArray.map { decodeAny(it.jsonObject, mode) ?: return null }
        val state = when (mode) {
            GameMode.KLONDIKE -> SolitaireUiState(
                gameMode = mode,
                klondike = decodeKlondike(root.getValue("klondike").jsonObject) ?: return null,
                history = history,
            )
            GameMode.SPIDER -> SolitaireUiState(
                gameMode = mode,
                spider = decodeSpider(root.getValue("spider").jsonObject) ?: return null,
                history = history,
            )
            GameMode.FREECELL -> SolitaireUiState(
                gameMode = mode,
                freeCell = decodeFreeCell(root.getValue("freeCell").jsonObject) ?: return null,
                history = history,
            )
            GameMode.PYRAMID -> SolitaireUiState(
                gameMode = mode,
                pyramid = decodePyramid(root.getValue("pyramid").jsonObject) ?: return null,
                history = history,
            )
        }
        if (isWon(state)) return null
        state
    }.getOrNull()

    fun isWon(state: SolitaireUiState): Boolean = when (state.gameMode) {
        GameMode.KLONDIKE -> state.klondike?.isWon == true
        GameMode.SPIDER -> state.spider?.isWon == true
        GameMode.FREECELL -> state.freeCell?.isWon == true
        GameMode.PYRAMID -> state.pyramid?.isWon == true
        null -> false
    }

    // -- cards --

    private fun code(card: Card): Int = card.suit.ordinal * Rank.entries.size + (card.rank.value - 1)

    private fun card(code: Int): Card? {
        if (code !in 0 until DECK_CARDS) return null
        return Card(Suit.entries[code / Rank.entries.size], Rank.entries[code % Rank.entries.size])
    }

    private fun cards(list: List<Card>): JsonArray = buildJsonArray { for (c in list) add(code(c)) }

    private fun cards(obj: JsonObject, key: String): List<Card>? {
        val raw = obj.getValue(key).jsonArray.map { it.jsonPrimitive.int }
        return raw.map { card(it) ?: return null }
    }

    private fun piles(list: List<List<Card>>): JsonArray =
        JsonArray(list.map { cards(it) })

    private fun piles(obj: JsonObject, key: String, count: Int): List<List<Card>>? {
        val raw = obj.getValue(key).jsonArray
        if (raw.size != count) return null
        return raw.map { element ->
            element.jsonArray.map { card(it.jsonPrimitive.int) ?: return null }
        }
    }

    private fun tableau(list: List<TableauPile>): JsonArray = JsonArray(list.map { pile ->
        buildJsonObject { put("d", cards(pile.faceDown)); put("u", cards(pile.faceUp)) }
    })

    private fun tableau(obj: JsonObject, key: String, count: Int): List<TableauPile>? {
        val raw = obj.getValue(key).jsonArray
        if (raw.size != count) return null
        return raw.map { element ->
            val pile = element.jsonObject
            TableauPile(cards(pile, "d") ?: return null, cards(pile, "u") ?: return null)
        }
    }

    private fun totalKlondike(s: KlondikeState): Int =
        s.stock.size + s.waste.size + s.foundations.sumOf { it.size } +
            s.tableauPiles.sumOf { it.faceDown.size + it.faceUp.size }

    private fun totalSpider(s: SpiderState): Int =
        s.tableauPiles.sumOf { it.faceDown.size + it.faceUp.size } +
            s.stockGroups.sumOf { it.size } + s.completedSuits * FULL_SUIT_SIZE

    private fun totalFreeCell(s: FreeCellState): Int =
        s.tableauPiles.sumOf { it.size } + s.freeCells.count { it != null } +
            s.foundations.sumOf { it.size }

    // -- per-mode states --

    private fun encodeAny(state: Any): JsonObject = when (state) {
        is KlondikeState -> encodeKlondike(state)
        is SpiderState -> encodeSpider(state)
        is FreeCellState -> encodeFreeCell(state)
        is PyramidState -> encodePyramid(state)
        else -> buildJsonObject { put("t", "unknown") }
    }

    private fun decodeAny(obj: JsonObject, mode: GameMode): Any? = when (mode) {
        GameMode.KLONDIKE -> decodeKlondike(obj)
        GameMode.SPIDER -> decodeSpider(obj)
        GameMode.FREECELL -> decodeFreeCell(obj)
        GameMode.PYRAMID -> decodePyramid(obj)
    }

    private fun encodeKlondike(s: KlondikeState): JsonObject = buildJsonObject {
        put("t", "k")
        put("seed", s.seed); put("variant", s.variant)
        put("drawMode", s.drawMode.name); put("difficulty", s.difficulty.name)
        put("redeals", s.redealsRemaining); put("moves", s.moveCount)
        put("elapsed", s.elapsedSeconds); put("usedUndo", s.usedUndo); put("won", s.isWon)
        put("stock", cards(s.stock)); put("waste", cards(s.waste))
        put("foundations", piles(s.foundations)); put("tableau", tableau(s.tableauPiles))
    }

    private fun decodeKlondike(obj: JsonObject): KlondikeState? {
        if (obj["t"]?.jsonPrimitive?.content != "k") return null
        val state = KlondikeState(
            stock = cards(obj, "stock") ?: return null,
            waste = cards(obj, "waste") ?: return null,
            tableauPiles = tableau(obj, "tableau", KLONDIKE_TABLEAU_COUNT) ?: return null,
            foundations = piles(obj, "foundations", FOUNDATION_COUNT) ?: return null,
            drawMode = DrawMode.valueOf(obj.getValue("drawMode").jsonPrimitive.content),
            difficulty = KlondikeDifficulty.valueOf(obj.getValue("difficulty").jsonPrimitive.content),
            redealsRemaining = obj.getValue("redeals").jsonPrimitive.int,
            variant = obj.getValue("variant").jsonPrimitive.content,
            seed = obj.getValue("seed").jsonPrimitive.content.toLong(),
            moveCount = obj.getValue("moves").jsonPrimitive.int,
            elapsedSeconds = obj.getValue("elapsed").jsonPrimitive.int,
            usedUndo = obj.getValue("usedUndo").jsonPrimitive.content.toBooleanStrict(),
            isWon = obj.getValue("won").jsonPrimitive.content.toBooleanStrict(),
        )
        return state.takeIf { totalKlondike(it) == DECK_CARDS && it.moveCount >= 0 && it.elapsedSeconds >= 0 }
    }

    private fun encodeSpider(s: SpiderState): JsonObject = buildJsonObject {
        put("t", "s")
        put("seed", s.seed); put("variant", s.variant); put("suits", s.suitCount)
        put("completed", s.completedSuits); put("moves", s.moveCount)
        put("elapsed", s.elapsedSeconds); put("usedUndo", s.usedUndo); put("won", s.isWon)
        put("stockGroups", piles(s.stockGroups)); put("tableau", tableau(s.tableauPiles))
    }

    private fun decodeSpider(obj: JsonObject): SpiderState? {
        if (obj["t"]?.jsonPrimitive?.content != "s") return null
        val stockGroupsRaw = obj.getValue("stockGroups").jsonArray
        val stockGroups = stockGroupsRaw.map { element ->
            element.jsonArray.map { card(it.jsonPrimitive.int) ?: return null }
        }
        val state = SpiderState(
            tableauPiles = tableau(obj, "tableau", SPIDER_TABLEAU_COUNT) ?: return null,
            stockGroups = stockGroups,
            suitCount = obj.getValue("suits").jsonPrimitive.int,
            variant = obj.getValue("variant").jsonPrimitive.content,
            completedSuits = obj.getValue("completed").jsonPrimitive.int,
            seed = obj.getValue("seed").jsonPrimitive.content.toLong(),
            moveCount = obj.getValue("moves").jsonPrimitive.int,
            elapsedSeconds = obj.getValue("elapsed").jsonPrimitive.int,
            usedUndo = obj.getValue("usedUndo").jsonPrimitive.content.toBooleanStrict(),
            isWon = obj.getValue("won").jsonPrimitive.content.toBooleanStrict(),
        )
        return state.takeIf {
            it.suitCount in setOf(1, 2, 4) && totalSpider(it) == SPIDER_CARDS &&
                it.moveCount >= 0 && it.elapsedSeconds >= 0
        }
    }

    private fun encodeFreeCell(s: FreeCellState): JsonObject = buildJsonObject {
        put("t", "f")
        put("seed", s.seed); put("variant", s.variant)
        put("moves", s.moveCount); put("elapsed", s.elapsedSeconds)
        put("usedUndo", s.usedUndo); put("won", s.isWon)
        put("tableau", piles(s.tableauPiles)); put("foundations", piles(s.foundations))
        put("free", buildJsonArray { for (c in s.freeCells) add(c?.let { code(it) } ?: NO_CARD) })
    }

    private fun decodeFreeCell(obj: JsonObject): FreeCellState? {
        if (obj["t"]?.jsonPrimitive?.content != "f") return null
        val freeRaw = obj.getValue("free").jsonArray.map { it.jsonPrimitive.int }
        if (freeRaw.size != FREECELL_COUNT) return null
        val free = freeRaw.map { if (it == NO_CARD) null else card(it) ?: return null }
        val state = FreeCellState(
            tableauPiles = piles(obj, "tableau", FREECELL_TABLEAU_COUNT) ?: return null,
            freeCells = free,
            foundations = piles(obj, "foundations", FOUNDATION_COUNT) ?: return null,
            variant = obj.getValue("variant").jsonPrimitive.content,
            seed = obj.getValue("seed").jsonPrimitive.content.toLong(),
            moveCount = obj.getValue("moves").jsonPrimitive.int,
            elapsedSeconds = obj.getValue("elapsed").jsonPrimitive.int,
            usedUndo = obj.getValue("usedUndo").jsonPrimitive.content.toBooleanStrict(),
            isWon = obj.getValue("won").jsonPrimitive.content.toBooleanStrict(),
        )
        return state.takeIf { totalFreeCell(it) == DECK_CARDS && it.moveCount >= 0 && it.elapsedSeconds >= 0 }
    }

    private fun encodePyramid(s: PyramidState): JsonObject = buildJsonObject {
        put("t", "p")
        put("seed", s.seed); put("variant", s.variant); put("relaxed", s.relaxed)
        put("selected", s.selectedId); put("moves", s.moveCount)
        put("elapsed", s.elapsedSeconds); put("usedUndo", s.usedUndo); put("won", s.isWon)
        put("stock", cards(s.stock)); put("waste", cards(s.waste))
        put("pyramid", JsonArray(s.pyramid.map { row ->
            buildJsonArray { for (c in row) add(c?.let { code(it) } ?: NO_CARD) }
        }))
    }

    private fun decodePyramid(obj: JsonObject): PyramidState? {
        if (obj["t"]?.jsonPrimitive?.content != "p") return null
        val rowsRaw = obj.getValue("pyramid").jsonArray
        if (rowsRaw.size != PYRAMID_ROWS) return null
        val rows = rowsRaw.mapIndexed { r, element ->
            val row = element.jsonArray.map { it.jsonPrimitive.int }
            if (row.size != r + 1) return null
            row.map { if (it == NO_CARD) null else card(it) ?: return null }
        }
        val state = PyramidState(
            pyramid = rows,
            stock = cards(obj, "stock") ?: return null,
            waste = cards(obj, "waste") ?: return null,
            relaxed = obj.getValue("relaxed").jsonPrimitive.content.toBooleanStrict(),
            variant = obj.getValue("variant").jsonPrimitive.content,
            selectedId = obj["selected"]?.jsonPrimitive?.content?.takeIf { it != "null" },
            seed = obj.getValue("seed").jsonPrimitive.content.toLong(),
            moveCount = obj.getValue("moves").jsonPrimitive.int,
            elapsedSeconds = obj.getValue("elapsed").jsonPrimitive.int,
            usedUndo = obj.getValue("usedUndo").jsonPrimitive.content.toBooleanStrict(),
            isWon = obj.getValue("won").jsonPrimitive.content.toBooleanStrict(),
        )
        val total = state.pyramid.sumOf { row -> row.count { it != null } } +
            state.stock.size + state.waste.size
        return state.takeIf { total in 1..DECK_CARDS && it.moveCount >= 0 && it.elapsedSeconds >= 0 }
    }
}

/**
 * Unfinished games, one encoded [SolitaireUiState] per mode, so process death never
 * loses a game in progress. Own prefs file, following unblockjam's InProgressBoards.
 */
class InProgressGame(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(mode: GameMode): SolitaireUiState? =
        prefs.getString(mode.name, null)?.let { SolitaireProgressCodec.decode(it, mode) }

    fun save(state: SolitaireUiState) {
        val mode = state.gameMode ?: return
        prefs.edit { putString(mode.name, SolitaireProgressCodec.encode(state)) }
    }

    fun clear(mode: GameMode) {
        prefs.edit { remove(mode.name) }
    }

    private companion object {
        const val PREFS_NAME = "solitaire_in_progress"
    }
}
