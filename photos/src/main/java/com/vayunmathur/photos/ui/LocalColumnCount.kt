package com.vayunmathur.photos.ui

import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.staticCompositionLocalOf

internal const val COLUMN_COUNT_KEY = "photos_column_count"
internal const val MIN_COLUMN_COUNT = 2
internal const val MAX_COLUMN_COUNT = 8

val LocalColumnCount = staticCompositionLocalOf<MutableFloatState> {
    error("No LocalColumnCount provided")
}
