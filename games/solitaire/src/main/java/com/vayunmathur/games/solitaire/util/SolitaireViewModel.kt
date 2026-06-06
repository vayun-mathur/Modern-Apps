package com.vayunmathur.games.solitaire.util

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.AndroidViewModel
import com.vayunmathur.games.solitaire.data.*
import com.vayunmathur.library.util.AchievementsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class DragInfo(
    val cards: List<Card>,
    val sourceId: String,
    val offset: Offset = Offset.Zero
)

class SolitaireViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(SolitaireUiState())
    val uiState: StateFlow<SolitaireUiState> = _uiState.asStateFlow()

    private val _dragInfo = MutableStateFlow<DragInfo?>(null)
    val dragInfo: StateFlow<DragInfo?> = _dragInfo.asStateFlow()

    private val statsRepository = SolitaireStatsRepository(application)
    val dropTargets = mutableMapOf<String, Rect>()

    val achievementsManager: AchievementsManager = run {
        val json = application.assets.open("achievements.json")
            .bufferedReader().readText()
        SolitaireAchievementsManager(application, json, statsRepository)
    }

    init {
        achievementsManager.checkExistingAchievements()
    }

    fun getStats(mode: GameMode): GameStats = statsRepository.getStats(mode)

    fun hasActiveGame(): Boolean {
        val state = _uiState.value
        return state.gameMode != null && when (state.gameMode) {
            GameMode.KLONDIKE -> state.klondike?.isWon == false
            GameMode.SPIDER -> state.spider?.isWon == false
            GameMode.FREECELL -> state.freeCell?.isWon == false
        }
    }

    fun selectMode(mode: GameMode, drawMode: DrawMode = DrawMode.DRAW_ONE) {
        when (mode) {
            GameMode.KLONDIKE -> newKlondikeGame(drawMode)
            GameMode.SPIDER -> newSpiderGame()
            GameMode.FREECELL -> newFreeCellGame()
        }
    }

    fun giveUp() {
        val mode = _uiState.value.gameMode ?: return
        statsRepository.recordGameLost(mode)
        _uiState.value = SolitaireUiState()
    }

    // --- Klondike ---

    fun newKlondikeGame(drawMode: DrawMode) {
        val deck = createShuffledDeck()
        val tableauPiles = mutableListOf<TableauPile>()
        var index = 0
        for (i in 0 until 7) {
            val faceDown = deck.subList(index, index + i)
            index += i
            val faceUp = listOf(deck[index])
            index++
            tableauPiles.add(TableauPile(faceDown, faceUp))
        }
        val stock = deck.subList(index, deck.size)
        _uiState.value = SolitaireUiState(
            gameMode = GameMode.KLONDIKE,
            klondike = KlondikeState(
                stock = stock,
                waste = emptyList(),
                tableauPiles = tableauPiles,
                foundations = List(4) { emptyList() },
                drawMode = drawMode
            ),
            history = emptyList()
        )
        statsRepository.recordGamePlayed(GameMode.KLONDIKE)
    }

    fun drawFromStock() {
        val state = _uiState.value.klondike ?: return
        if (state.isWon) return
        saveHistory()
        if (state.stock.isEmpty()) {
            _uiState.update {
                it.copy(klondike = state.copy(
                    stock = state.waste.reversed(),
                    waste = emptyList(),
                    moveCount = state.moveCount + 1
                ))
            }
        } else {
            val drawCount = if (state.drawMode == DrawMode.DRAW_THREE) 3 else 1
            val drawn = state.stock.takeLast(drawCount)
            _uiState.update {
                it.copy(klondike = state.copy(
                    stock = state.stock.dropLast(drawCount),
                    waste = state.waste + drawn,
                    moveCount = state.moveCount + 1
                ))
            }
        }
    }

    fun toggleDrawMode() {
        val state = _uiState.value.klondike ?: return
        val newMode = if (state.drawMode == DrawMode.DRAW_ONE) DrawMode.DRAW_THREE else DrawMode.DRAW_ONE
        newKlondikeGame(newMode)
    }

    fun klondikeMoveWasteToTableau(columnIndex: Int) {
        val state = _uiState.value.klondike ?: return
        if (state.isWon || state.waste.isEmpty()) return
        val card = state.waste.last()
        val pile = state.tableauPiles[columnIndex]
        if (!canPlaceOnKlondikeTableau(card, pile)) return
        saveHistory()
        val newPiles = state.tableauPiles.toMutableList()
        newPiles[columnIndex] = pile.copy(faceUp = pile.faceUp + card)
        _uiState.update {
            it.copy(klondike = state.copy(
                waste = state.waste.dropLast(1),
                tableauPiles = newPiles,
                moveCount = state.moveCount + 1
            ))
        }
        checkKlondikeWin()
    }

    fun klondikeMoveWasteToFoundation(foundationIndex: Int) {
        val state = _uiState.value.klondike ?: return
        if (state.isWon || state.waste.isEmpty()) return
        val card = state.waste.last()
        if (!canPlaceOnFoundation(card, state.foundations[foundationIndex])) return
        saveHistory()
        val newFoundations = state.foundations.toMutableList()
        newFoundations[foundationIndex] = newFoundations[foundationIndex] + card
        _uiState.update {
            it.copy(klondike = state.copy(
                waste = state.waste.dropLast(1),
                foundations = newFoundations,
                moveCount = state.moveCount + 1
            ))
        }
        checkKlondikeWin()
    }

    fun klondikeMoveTableauToFoundation(fromColumn: Int, foundationIndex: Int) {
        val state = _uiState.value.klondike ?: return
        if (state.isWon) return
        val pile = state.tableauPiles[fromColumn]
        if (pile.faceUp.isEmpty()) return
        val card = pile.faceUp.last()
        if (!canPlaceOnFoundation(card, state.foundations[foundationIndex])) return
        saveHistory()
        val newPiles = state.tableauPiles.toMutableList()
        val newFaceUp = pile.faceUp.dropLast(1)
        newPiles[fromColumn] = autoFlip(pile.copy(faceUp = newFaceUp))
        val newFoundations = state.foundations.toMutableList()
        newFoundations[foundationIndex] = newFoundations[foundationIndex] + card
        _uiState.update {
            it.copy(klondike = state.copy(
                tableauPiles = newPiles,
                foundations = newFoundations,
                moveCount = state.moveCount + 1
            ))
        }
        checkKlondikeWin()
    }

    fun klondikeMoveTableauToTableau(fromColumn: Int, cardIndex: Int, toColumn: Int) {
        val state = _uiState.value.klondike ?: return
        if (state.isWon) return
        val fromPile = state.tableauPiles[fromColumn]
        if (cardIndex < 0 || cardIndex >= fromPile.faceUp.size) return
        val movingCards = fromPile.faceUp.subList(cardIndex, fromPile.faceUp.size)
        val topCard = movingCards.first()
        val toPile = state.tableauPiles[toColumn]
        if (!canPlaceOnKlondikeTableau(topCard, toPile)) return
        saveHistory()
        val newPiles = state.tableauPiles.toMutableList()
        newPiles[fromColumn] = autoFlip(fromPile.copy(faceUp = fromPile.faceUp.subList(0, cardIndex)))
        newPiles[toColumn] = toPile.copy(faceUp = toPile.faceUp + movingCards)
        _uiState.update {
            it.copy(klondike = state.copy(
                tableauPiles = newPiles,
                moveCount = state.moveCount + 1
            ))
        }
    }

    fun klondikeAutoComplete() {
        val state = _uiState.value.klondike ?: return
        if (state.isWon) return
        if (state.tableauPiles.any { it.faceDown.isNotEmpty() }) return
        var current = state
        var moved = true
        while (moved) {
            moved = false
            // If stock has cards, draw them to waste first
            if (current.stock.isNotEmpty()) {
                val drawn = current.stock.last()
                current = current.copy(
                    stock = current.stock.dropLast(1),
                    waste = current.waste + drawn
                )
            }
            val sources = mutableListOf<Card>()
            val sourceInfo = mutableListOf<String>()
            if (current.waste.isNotEmpty()) {
                sources.add(current.waste.last())
                sourceInfo.add("waste")
            }
            for (i in current.tableauPiles.indices) {
                if (current.tableauPiles[i].faceUp.isNotEmpty()) {
                    sources.add(current.tableauPiles[i].faceUp.last())
                    sourceInfo.add("tableau_$i")
                }
            }
            for (si in sources.indices) {
                val card = sources[si]
                for (fi in current.foundations.indices) {
                    if (canPlaceOnFoundation(card, current.foundations[fi])) {
                        val newFoundations = current.foundations.toMutableList()
                        newFoundations[fi] = newFoundations[fi] + card
                        val newPiles = current.tableauPiles.toMutableList()
                        val newWaste = if (sourceInfo[si] == "waste") current.waste.dropLast(1) else current.waste
                        if (sourceInfo[si].startsWith("tableau_")) {
                            val idx = sourceInfo[si].removePrefix("tableau_").toInt()
                            val pile = newPiles[idx]
                            newPiles[idx] = autoFlip(pile.copy(faceUp = pile.faceUp.dropLast(1)))
                        }
                        current = current.copy(
                            waste = newWaste,
                            tableauPiles = newPiles,
                            foundations = newFoundations,
                            moveCount = current.moveCount + 1
                        )
                        moved = true
                        break
                    }
                }
                if (moved) break
            }
        }
        _uiState.update { it.copy(klondike = current) }
        checkKlondikeWin()
    }

    private fun canPlaceOnKlondikeTableau(card: Card, pile: TableauPile): Boolean {
        if (pile.faceUp.isEmpty() && pile.faceDown.isEmpty()) return card.rank == Rank.KING
        if (pile.faceUp.isEmpty()) return false
        val top = pile.faceUp.last()
        return top.isOneHigherThan(card) && card.alternatesColorWith(top)
    }

    private fun canPlaceOnFoundation(card: Card, foundation: List<Card>): Boolean {
        if (foundation.isEmpty()) return card.rank == Rank.ACE
        val top = foundation.last()
        return card.suit == top.suit && card.rank.value == top.rank.value + 1
    }

    private fun autoFlip(pile: TableauPile): TableauPile {
        if (pile.faceUp.isEmpty() && pile.faceDown.isNotEmpty()) {
            return pile.copy(
                faceDown = pile.faceDown.dropLast(1),
                faceUp = listOf(pile.faceDown.last())
            )
        }
        return pile
    }

    private fun checkKlondikeWin() {
        val state = _uiState.value.klondike ?: return
        if (state.foundations.all { it.size == 13 }) {
            _uiState.update { it.copy(klondike = state.copy(isWon = true)) }
            onGameWon(GameMode.KLONDIKE, state.elapsedSeconds, state.moveCount, state.usedUndo)
        }
    }

    // --- Spider ---

    fun newSpiderGame() {
        val deck = (createShuffledDeck() + createShuffledDeck()).shuffled()
        val tableauPiles = mutableListOf<TableauPile>()
        var index = 0
        for (i in 0 until 10) {
            val count = if (i < 4) 6 else 5
            val faceDown = deck.subList(index, index + count - 1)
            index += count - 1
            val faceUp = listOf(deck[index])
            index++
            tableauPiles.add(TableauPile(faceDown, faceUp))
        }
        val remaining = deck.subList(index, deck.size)
        val stockGroups = remaining.chunked(10)
        _uiState.value = SolitaireUiState(
            gameMode = GameMode.SPIDER,
            spider = SpiderState(
                tableauPiles = tableauPiles,
                stockGroups = stockGroups
            ),
            history = emptyList()
        )
        statsRepository.recordGamePlayed(GameMode.SPIDER)
    }

    fun dealSpiderStock() {
        val state = _uiState.value.spider ?: return
        if (state.isWon || state.stockGroups.isEmpty()) return
        if (state.tableauPiles.any { it.faceUp.isEmpty() && it.faceDown.isEmpty() }) return
        saveHistory()
        val group = state.stockGroups.first()
        val newPiles = state.tableauPiles.toMutableList()
        for (i in 0 until minOf(group.size, 10)) {
            val pile = newPiles[i]
            newPiles[i] = pile.copy(faceUp = pile.faceUp + group[i])
        }
        _uiState.update {
            it.copy(spider = state.copy(
                tableauPiles = newPiles,
                stockGroups = state.stockGroups.drop(1),
                moveCount = state.moveCount + 1
            ))
        }
        checkSpiderCompletedSuits()
    }

    fun spiderMoveCards(fromColumn: Int, cardIndex: Int, toColumn: Int) {
        val state = _uiState.value.spider ?: return
        if (state.isWon) return
        val fromPile = state.tableauPiles[fromColumn]
        if (cardIndex < 0 || cardIndex >= fromPile.faceUp.size) return
        val movingCards = fromPile.faceUp.subList(cardIndex, fromPile.faceUp.size)
        if (!isSpiderSequence(movingCards)) return
        val toPile = state.tableauPiles[toColumn]
        if (!canPlaceOnSpiderTableau(movingCards.first(), toPile)) return
        saveHistory()
        val newPiles = state.tableauPiles.toMutableList()
        newPiles[fromColumn] = autoFlip(fromPile.copy(faceUp = fromPile.faceUp.subList(0, cardIndex)))
        newPiles[toColumn] = toPile.copy(faceUp = toPile.faceUp + movingCards)
        _uiState.update {
            it.copy(spider = state.copy(
                tableauPiles = newPiles,
                moveCount = state.moveCount + 1
            ))
        }
        checkSpiderCompletedSuits()
    }

    private fun isSpiderSequence(cards: List<Card>): Boolean {
        for (i in 0 until cards.size - 1) {
            if (cards[i].suit != cards[i + 1].suit) return false
            if (cards[i].rank.value != cards[i + 1].rank.value + 1) return false
        }
        return true
    }

    private fun canPlaceOnSpiderTableau(card: Card, pile: TableauPile): Boolean {
        if (pile.faceUp.isEmpty() && pile.faceDown.isEmpty()) return true
        if (pile.faceUp.isEmpty()) return false
        return pile.faceUp.last().rank.value == card.rank.value + 1
    }

    private fun checkSpiderCompletedSuits() {
        val state = _uiState.value.spider ?: return
        val newPiles = state.tableauPiles.toMutableList()
        var completed = state.completedSuits
        for (i in newPiles.indices) {
            val pile = newPiles[i]
            if (pile.faceUp.size >= 13) {
                val last13 = pile.faceUp.takeLast(13)
                if (isSpiderSequence(last13) && last13.first().rank == Rank.KING && last13.last().rank == Rank.ACE) {
                    newPiles[i] = autoFlip(pile.copy(faceUp = pile.faceUp.dropLast(13)))
                    completed++
                }
            }
        }
        if (completed != state.completedSuits) {
            val newState = state.copy(tableauPiles = newPiles, completedSuits = completed, isWon = completed >= 8)
            _uiState.update { it.copy(spider = newState) }
            if (newState.isWon) {
                onGameWon(GameMode.SPIDER, state.elapsedSeconds, state.moveCount, state.usedUndo)
            }
        }
    }

    // --- FreeCell ---

    fun newFreeCellGame() {
        val deck = createShuffledDeck()
        val piles = List(8) { mutableListOf<Card>() }
        for (i in deck.indices) {
            piles[i % 8].add(deck[i])
        }
        _uiState.value = SolitaireUiState(
            gameMode = GameMode.FREECELL,
            freeCell = FreeCellState(
                tableauPiles = piles.map { it.toList() },
                freeCells = List(4) { null },
                foundations = List(4) { emptyList() }
            ),
            history = emptyList()
        )
        statsRepository.recordGamePlayed(GameMode.FREECELL)
    }

    fun freeCellMoveToFreeCell(fromColumn: Int, cellIndex: Int) {
        val state = _uiState.value.freeCell ?: return
        if (state.isWon) return
        val pile = state.tableauPiles[fromColumn]
        if (pile.isEmpty()) return
        if (state.freeCells[cellIndex] != null) return
        saveHistory()
        val card = pile.last()
        val newPiles = state.tableauPiles.toMutableList()
        newPiles[fromColumn] = pile.dropLast(1)
        val newCells = state.freeCells.toMutableList()
        newCells[cellIndex] = card
        _uiState.update {
            it.copy(freeCell = state.copy(
                tableauPiles = newPiles,
                freeCells = newCells,
                moveCount = state.moveCount + 1
            ))
        }
    }

    fun freeCellMoveFromFreeCell(cellIndex: Int, toColumn: Int) {
        val state = _uiState.value.freeCell ?: return
        if (state.isWon) return
        val card = state.freeCells[cellIndex] ?: return
        val pile = state.tableauPiles[toColumn]
        if (!canPlaceOnFreeCellTableau(card, pile)) return
        saveHistory()
        val newPiles = state.tableauPiles.toMutableList()
        newPiles[toColumn] = pile + card
        val newCells = state.freeCells.toMutableList()
        newCells[cellIndex] = null
        _uiState.update {
            it.copy(freeCell = state.copy(
                tableauPiles = newPiles,
                freeCells = newCells,
                moveCount = state.moveCount + 1
            ))
        }
    }

    fun freeCellMoveFreeCellToFoundation(cellIndex: Int, foundationIndex: Int) {
        val state = _uiState.value.freeCell ?: return
        if (state.isWon) return
        val card = state.freeCells[cellIndex] ?: return
        if (!canPlaceOnFoundation(card, state.foundations[foundationIndex])) return
        saveHistory()
        val newCells = state.freeCells.toMutableList()
        newCells[cellIndex] = null
        val newFoundations = state.foundations.toMutableList()
        newFoundations[foundationIndex] = newFoundations[foundationIndex] + card
        _uiState.update {
            it.copy(freeCell = state.copy(
                freeCells = newCells,
                foundations = newFoundations,
                moveCount = state.moveCount + 1
            ))
        }
        checkFreeCellWin()
    }

    fun freeCellMoveTableauToFoundation(fromColumn: Int, foundationIndex: Int) {
        val state = _uiState.value.freeCell ?: return
        if (state.isWon) return
        val pile = state.tableauPiles[fromColumn]
        if (pile.isEmpty()) return
        val card = pile.last()
        if (!canPlaceOnFoundation(card, state.foundations[foundationIndex])) return
        saveHistory()
        val newPiles = state.tableauPiles.toMutableList()
        newPiles[fromColumn] = pile.dropLast(1)
        val newFoundations = state.foundations.toMutableList()
        newFoundations[foundationIndex] = newFoundations[foundationIndex] + card
        _uiState.update {
            it.copy(freeCell = state.copy(
                tableauPiles = newPiles,
                foundations = newFoundations,
                moveCount = state.moveCount + 1
            ))
        }
        checkFreeCellWin()
    }

    fun freeCellMoveTableauToTableau(fromColumn: Int, cardIndex: Int, toColumn: Int) {
        val state = _uiState.value.freeCell ?: return
        if (state.isWon) return
        val fromPile = state.tableauPiles[fromColumn]
        if (cardIndex < 0 || cardIndex >= fromPile.size) return
        val movingCards = fromPile.subList(cardIndex, fromPile.size)
        if (!isFreeCellSequence(movingCards)) return
        val toPile = state.tableauPiles[toColumn]
        if (movingCards.size > 1) {
            val emptyFreeCells = state.freeCells.count { it == null }
            val emptyColumns = state.tableauPiles.indices.count { it != fromColumn && it != toColumn && state.tableauPiles[it].isEmpty() }
            val maxMove = (1 + emptyFreeCells) * (1 shl emptyColumns)
            if (movingCards.size > maxMove) return
        }
        if (!canPlaceOnFreeCellTableau(movingCards.first(), toPile)) return
        saveHistory()
        val newPiles = state.tableauPiles.toMutableList()
        newPiles[fromColumn] = fromPile.subList(0, cardIndex)
        newPiles[toColumn] = toPile + movingCards
        _uiState.update {
            it.copy(freeCell = state.copy(
                tableauPiles = newPiles,
                moveCount = state.moveCount + 1
            ))
        }
    }

    private fun isFreeCellSequence(cards: List<Card>): Boolean {
        for (i in 0 until cards.size - 1) {
            if (!cards[i].alternatesColorWith(cards[i + 1])) return false
            if (cards[i].rank.value != cards[i + 1].rank.value + 1) return false
        }
        return true
    }

    private fun canPlaceOnFreeCellTableau(card: Card, pile: List<Card>): Boolean {
        if (pile.isEmpty()) return true
        val top = pile.last()
        return card.alternatesColorWith(top) && top.rank.value == card.rank.value + 1
    }

    private fun checkFreeCellWin() {
        val state = _uiState.value.freeCell ?: return
        if (state.foundations.all { it.size == 13 }) {
            _uiState.update { it.copy(freeCell = state.copy(isWon = true)) }
            onGameWon(GameMode.FREECELL, state.elapsedSeconds, state.moveCount, state.usedUndo)
        }
    }

    // --- Shared ---

    fun tryMoveByDrag(cards: List<Card>, sourceId: String, dropOffset: Offset) {
        val targetId = dropTargets.entries.find { (_, rect) ->
            rect.contains(dropOffset)
        }?.key ?: return

        when (_uiState.value.gameMode) {
            GameMode.KLONDIKE -> handleKlondikeDrop(cards, sourceId, targetId)
            GameMode.SPIDER -> handleSpiderDrop(cards, sourceId, targetId)
            GameMode.FREECELL -> handleFreeCellDrop(cards, sourceId, targetId)
            null -> {}
        }
    }

    private fun handleKlondikeDrop(cards: List<Card>, sourceId: String, targetId: String) {
        when {
            targetId.startsWith("foundation_") -> {
                val fi = targetId.removePrefix("foundation_").toInt()
                if (cards.size == 1) {
                    when {
                        sourceId == "waste" -> klondikeMoveWasteToFoundation(fi)
                        sourceId.startsWith("tableau_") -> {
                            val col = sourceId.removePrefix("tableau_").substringBefore("_").toInt()
                            klondikeMoveTableauToFoundation(col, fi)
                        }
                    }
                }
            }
            targetId.startsWith("tableau_") -> {
                val toCol = targetId.removePrefix("tableau_").toInt()
                when {
                    sourceId == "waste" -> klondikeMoveWasteToTableau(toCol)
                    sourceId.startsWith("tableau_") -> {
                        val parts = sourceId.removePrefix("tableau_").split("_")
                        val fromCol = parts[0].toInt()
                        val cardIdx = parts.getOrNull(1)?.toInt() ?: return
                        klondikeMoveTableauToTableau(fromCol, cardIdx, toCol)
                    }
                }
            }
        }
    }

    private fun handleSpiderDrop(cards: List<Card>, sourceId: String, targetId: String) {
        if (!targetId.startsWith("tableau_") || !sourceId.startsWith("tableau_")) return
        val toCol = targetId.removePrefix("tableau_").toInt()
        val parts = sourceId.removePrefix("tableau_").split("_")
        val fromCol = parts[0].toInt()
        val cardIdx = parts.getOrNull(1)?.toInt() ?: return
        spiderMoveCards(fromCol, cardIdx, toCol)
    }

    private fun handleFreeCellDrop(cards: List<Card>, sourceId: String, targetId: String) {
        when {
            targetId.startsWith("freecell_") -> {
                val ci = targetId.removePrefix("freecell_").toInt()
                if (sourceId.startsWith("tableau_")) {
                    val col = sourceId.removePrefix("tableau_").substringBefore("_").toInt()
                    freeCellMoveToFreeCell(col, ci)
                }
            }
            targetId.startsWith("foundation_") -> {
                val fi = targetId.removePrefix("foundation_").toInt()
                if (cards.size == 1) {
                    when {
                        sourceId.startsWith("freecell_") -> {
                            val ci = sourceId.removePrefix("freecell_").toInt()
                            freeCellMoveFreeCellToFoundation(ci, fi)
                        }
                        sourceId.startsWith("tableau_") -> {
                            val col = sourceId.removePrefix("tableau_").substringBefore("_").toInt()
                            freeCellMoveTableauToFoundation(col, fi)
                        }
                    }
                }
            }
            targetId.startsWith("tableau_") -> {
                val toCol = targetId.removePrefix("tableau_").toInt()
                when {
                    sourceId.startsWith("freecell_") -> {
                        val ci = sourceId.removePrefix("freecell_").toInt()
                        freeCellMoveFromFreeCell(ci, toCol)
                    }
                    sourceId.startsWith("tableau_") -> {
                        val parts = sourceId.removePrefix("tableau_").split("_")
                        val fromCol = parts[0].toInt()
                        val cardIdx = parts.getOrNull(1)?.toInt() ?: return
                        freeCellMoveTableauToTableau(fromCol, cardIdx, toCol)
                    }
                }
            }
        }
    }

    fun startDrag(cards: List<Card>, sourceId: String) {
        _dragInfo.value = DragInfo(cards, sourceId)
    }

    fun updateDrag(offset: Offset) {
        _dragInfo.update { it?.copy(offset = offset) }
    }

    fun endDrag(dropOffset: Offset) {
        val info = _dragInfo.value ?: return
        tryMoveByDrag(info.cards, info.sourceId, dropOffset)
        _dragInfo.value = null
    }

    fun cancelDrag() {
        _dragInfo.value = null
    }

    fun undo() {
        val history = _uiState.value.history
        if (history.isEmpty()) return
        val prev = history.last()
        val newHistory = history.dropLast(1)
        when (prev) {
            is KlondikeState -> _uiState.update {
                it.copy(klondike = prev.copy(usedUndo = true), history = newHistory)
            }
            is SpiderState -> _uiState.update {
                it.copy(spider = prev.copy(usedUndo = true), history = newHistory)
            }
            is FreeCellState -> _uiState.update {
                it.copy(freeCell = prev.copy(usedUndo = true), history = newHistory)
            }
        }
    }

    fun restart() {
        when (_uiState.value.gameMode) {
            GameMode.KLONDIKE -> newKlondikeGame(_uiState.value.klondike?.drawMode ?: DrawMode.DRAW_ONE)
            GameMode.SPIDER -> newSpiderGame()
            GameMode.FREECELL -> newFreeCellGame()
            null -> {}
        }
    }

    fun incrementTimer() {
        _uiState.update { state ->
            state.copy(
                klondike = state.klondike?.let { if (!it.isWon) it.copy(elapsedSeconds = it.elapsedSeconds + 1) else it },
                spider = state.spider?.let { if (!it.isWon) it.copy(elapsedSeconds = it.elapsedSeconds + 1) else it },
                freeCell = state.freeCell?.let { if (!it.isWon) it.copy(elapsedSeconds = it.elapsedSeconds + 1) else it }
            )
        }
    }

    fun dismissAchievementNotification() {
        achievementsManager.dismissNotification()
    }

    private fun saveHistory() {
        val state = _uiState.value
        val current: Any = when (state.gameMode) {
            GameMode.KLONDIKE -> state.klondike ?: return
            GameMode.SPIDER -> state.spider ?: return
            GameMode.FREECELL -> state.freeCell ?: return
            null -> return
        }
        _uiState.update { it.copy(history = it.history + current) }
    }

    private fun onGameWon(mode: GameMode, timeSeconds: Int, moves: Int, usedUndo: Boolean) {
        statsRepository.recordGameWon(mode, timeSeconds, moves)
        achievementsManager.onAchievementUnlocked("first_win")
        when (mode) {
            GameMode.KLONDIKE -> {
                achievementsManager.onAchievementUnlocked("klondike_first")
                if (timeSeconds < 180) achievementsManager.onAchievementUnlocked("speed_demon")
            }
            GameMode.SPIDER -> achievementsManager.onAchievementUnlocked("spider_first")
            GameMode.FREECELL -> achievementsManager.onAchievementUnlocked("freecell_first")
        }
        if (!usedUndo) achievementsManager.onAchievementUnlocked("no_undo")
        achievementsManager.onProgressUpdated("wins_10", statsRepository.getTotalGamesWon())
        achievementsManager.onProgressUpdated("wins_50", statsRepository.getTotalGamesWon())
        achievementsManager.onProgressUpdated("win_streak_5", statsRepository.getBestWinStreak())
    }
}
