package com.vayunmathur.office.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Full mutable UI state owned by [DocumentScreen]: the dialog flags from
 * [DocumentOverlayState] plus selection state shared with the scaffold body and
 * the chrome state for the menu/find bars.
 *
 * Single snapshot-state holder so the screen's sections (menu bar, find bars, panes,
 * launchers, overlay dialogs) can live in separate files for the FileLength limit.
 * Each flag is a `MutableState` field plus a delegated `var` convenience accessor;
 * sections in other files use `holder.flag` (recomposition-tracked reads/writes) or
 * take `holder.flagState` when they need the state object itself. Behavior identical
 * to the inline `var x by remember { ... }` declarations this replaces.
 */
class DocumentScreenState : DocumentOverlayState() {
    val showSearchState = mutableStateOf(false)
    var showSearch by showSearchState
    val searchQueryState = mutableStateOf("")
    var searchQuery by searchQueryState
    val showFontControlState = mutableStateOf(false)
    var showFontControl by showFontControlState
    val fontSizeMultiplierState = mutableFloatStateOf(1f)
    var fontSizeMultiplier by fontSizeMultiplierState
    val activeRunStartState = mutableIntStateOf(-1)
    var activeRunStart by activeRunStartState
    val activeRunEndState = mutableIntStateOf(-1)
    var activeRunEnd by activeRunEndState
    val activeTableBlockState = mutableIntStateOf(-1)
    var activeTableBlock by activeTableBlockState
    val activeTableRowState = mutableIntStateOf(-1)
    var activeTableRow by activeTableRowState
    val activeTableColState = mutableIntStateOf(-1)
    var activeTableCol by activeTableColState
    val showReplaceBarState = mutableStateOf(false)
    var showReplaceBar by showReplaceBarState
    val replaceTextState = mutableStateOf("")
    var replaceText by replaceTextState
    val matchCaseState = mutableStateOf(false)
    var matchCase by matchCaseState
    val wholeWordState = mutableStateOf(false)
    var wholeWord by wholeWordState
    val showTimerState = mutableStateOf(false)
    var showTimer by showTimerState
    val timerSecondsState = mutableIntStateOf(0)
    var timerSeconds by timerSecondsState
    val selStartState = mutableIntStateOf(0)
    var selStart by selStartState
    val selEndState = mutableIntStateOf(0)
    var selEnd by selEndState
    val fileMenuState = mutableStateOf(false)
    var fileMenu by fileMenuState
    val exportMenuState = mutableStateOf(false)
    var exportMenu by exportMenuState
    val insertMenuState = mutableStateOf(false)
    var insertMenu by insertMenuState
    val viewMenuState = mutableStateOf(false)
    var viewMenu by viewMenuState
    // Hoisted selection for the shared bottom bar (Phase 2).
    val activeCellState: MutableState<Triple<Int, Int, Int>?> = mutableStateOf(null)
    var activeCell by activeCellState
    val activeSlideState = mutableIntStateOf(0)
    var activeSlide by activeSlideState
    val activeSlideElState = mutableIntStateOf(-1)
    var activeSlideEl by activeSlideElState
    val showWordBarState = mutableStateOf(false)
    var showWordBar by showWordBarState
    val findMatchesState: MutableState<List<Int>> = mutableStateOf(emptyList())
    var findMatches by findMatchesState
    val findIndexState = mutableIntStateOf(0)
    var findIndex by findIndexState
    // Replace-image picker: invokes the pending replace action with the chosen bytes. (C4)
    val pendingReplaceState: MutableState<((String, ByteArray) -> Unit)?> = mutableStateOf(null)
    var pendingReplace by pendingReplaceState
}

@Composable
internal fun rememberDocumentScreenState(): DocumentScreenState = remember { DocumentScreenState() }
