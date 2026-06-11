package com.vayunmathur.library.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier

fun Modifier.invisibleClickable(onClick: () -> Unit) = clickable(null, null) {onClick()}

/**
 * Adds a double-tap handler to this modifier.
 * Usage: `Modifier.onDoubleTap { /* duplicate item in viewmodel/state */ }`
 */
fun Modifier.onDoubleTap(onDoubleTap: () -> Unit): Modifier =
	this.pointerInput(Unit) {
		detectTapGestures(onDoubleTap = { onDoubleTap() })
	}