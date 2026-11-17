package com.vayunmathur.keyboard.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.vayunmathur.library.ui.MaterialTheme

/** The whole-key highlight shown while a key is held down. */
@Composable
fun pressedKeyColor(): Color = MaterialTheme.colorScheme.surfaceBright
