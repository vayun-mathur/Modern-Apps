package com.vayunmathur.games.solitaire.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.solitaire.platform.FlightTarget
import com.vayunmathur.games.solitaire.platform.TABLEAU_PREFIX

/** Vertical step between face-down cards in a tableau column. */
internal val FACE_DOWN_OVERLAP = 8.dp

/** Vertical step between face-up cards in a tableau column (and in a carried stack). */
internal val FACE_UP_OVERLAP = 22.dp

/**
 * Offset of a tableau card from its column's top: [faceDownCount] face-down cards, then the
 * face-up card at [faceUpIndex]. The columns draw every card with this, and the motion layer
 * lands flights with it, so a flight always ends exactly where its card is then drawn.
 */
internal fun tableauCardOffset(faceDownCount: Int, faceUpIndex: Int): Dp =
    FACE_DOWN_OVERLAP * faceDownCount + FACE_UP_OVERLAP * faceUpIndex

/**
 * Offset of [slot]'s card from its pile's laid-out origin. Tableau columns fan downwards;
 * every other pile (foundation, free cell) stacks its cards in place.
 */
internal fun pileSlotOffset(slot: FlightTarget.Slot): Dp =
    if (slot.pileId.startsWith(TABLEAU_PREFIX)) tableauCardOffset(slot.faceDownCount, slot.index) else 0.dp

/** Root position of [slot]'s card, given the root position of its pile's origin. */
internal fun Density.pileSlotPosition(pileOrigin: Offset, slot: FlightTarget.Slot): Offset =
    Offset(pileOrigin.x, pileOrigin.y + pileSlotOffset(slot).toPx())
