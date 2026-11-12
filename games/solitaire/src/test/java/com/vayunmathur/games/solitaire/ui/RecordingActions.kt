package com.vayunmathur.games.solitaire.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import com.vayunmathur.games.solitaire.data.Card
import com.vayunmathur.games.solitaire.platform.CardFlight
import com.vayunmathur.games.solitaire.platform.DragInfo
import com.vayunmathur.games.solitaire.platform.SolitaireActions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Records every board callback so Compose tests can assert routing. */
class RecordingActions : SolitaireActions {
    override val dragInfo: StateFlow<DragInfo?> = MutableStateFlow(null)
    val dragFlow = dragInfo as MutableStateFlow<DragInfo?>
    override val flights: StateFlow<List<CardFlight>> = MutableStateFlow(emptyList())
    val flightsFlow = flights as MutableStateFlow<List<CardFlight>>
    override val dropTargets: MutableMap<String, Rect> = mutableMapOf()
    val cardPositions: MutableMap<Card, Offset> = mutableMapOf()
    override fun registerCardPosition(card: Card, position: Offset, size: IntSize) { cardPositions[card] = position }
    val calls = mutableListOf<String>()

    override fun startDrag(sourceId: String, startPos: Offset, cardSize: IntSize): Boolean {
        calls += "startDrag:$sourceId"
        return true
    }

    override fun updateDrag(offset: Offset) { calls += "updateDrag" }
    override fun endDrag(dropOffset: Offset, cardSize: IntSize) { calls += "endDrag" }
    override fun cancelDrag() { calls += "cancelDrag" }
    override fun drawFromStock() { calls += "drawFromStock" }
    override fun klondikeAutoComplete() { calls += "klondikeAutoComplete" }
    override fun dealSpiderStock() { calls += "dealSpiderStock" }
    override fun pyramidTapCard(id: String) { calls += "pyramidTapCard:$id" }
    override fun pyramidDealStock() { calls += "pyramidDealStock" }
    override fun autoMove(sourceId: String) { calls += "autoMove:$sourceId" }
    override fun requestAutoMove(sourceId: String, startPos: Offset, cardSize: IntSize): Boolean {
        calls += "autoMove:$sourceId"
        return true
    }
    override fun landFlight(id: Long) { calls += "landFlight:$id" }
    override fun undo() { calls += "undo" }
    override fun restart() { calls += "restart" }
    override fun giveUp() { calls += "giveUp" }
}
