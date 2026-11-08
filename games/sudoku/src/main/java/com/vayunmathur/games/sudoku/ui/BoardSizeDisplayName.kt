package com.vayunmathur.games.sudoku.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.games.sudoku.R
import com.vayunmathur.games.sudoku.data.BoardSize

/**
 * Display names live beside the UI rather than on the enums so `data` stays free of Android types
 * and the enums can be used from the generator and its unit tests without a resource lookup.
 */
@Composable
fun BoardSize.displayName(): String = stringResource(
    when (this) {
        BoardSize.SIX -> R.string.size_six
        BoardSize.NINE -> R.string.size_nine
        BoardSize.TWELVE -> R.string.size_twelve
    }
)
