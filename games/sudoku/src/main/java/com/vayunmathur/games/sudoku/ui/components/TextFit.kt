package com.vayunmathur.games.sudoku.ui.components

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit

/**
 * [preferred], unless at the current font scale it would render taller than [cap].
 *
 * Board glyphs live in boxes of a fixed physical size — a cell, a pencil-mark slot, a 50 dp key —
 * so a font size in `sp` alone clips at large font scales: at 200% a 12x12 digit, a 9x9 pencil mark
 * and the key's remaining-count badge all outgrew their boxes. Comparing in pixels keeps the
 * preferred size (and so today's look) at normal scales and lets it grow with the user's setting
 * until it fills the box, never past it. Pixels rather than raw values because Android 14's
 * non-linear font scaling makes `sp` to `px` non-proportional.
 */
internal fun Density.fitSp(preferred: TextUnit, cap: Dp): TextUnit =
    if (preferred.toPx() > cap.toPx()) cap.toSp() else preferred
