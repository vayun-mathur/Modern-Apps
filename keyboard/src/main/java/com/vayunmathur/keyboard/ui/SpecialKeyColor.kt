package com.vayunmathur.keyboard.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.vayunmathur.library.ui.MaterialTheme

/** Resting colour for function keys (dimmer, so they read as secondary). */
@Composable
fun specialKeyColor(): Color = MaterialTheme.colorScheme.surfaceContainer
