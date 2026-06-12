package com.vayunmathur.launcher.ui

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

fun Modifier.launcherGestures(
    onSwipeUp: () -> Unit,
    onSwipeDown: () -> Unit
): Modifier = this.pointerInput(Unit) {
    detectVerticalDragGestures { _, dragAmount ->
        when {
            dragAmount < -40 -> onSwipeUp()
            dragAmount > 40 -> onSwipeDown()
        }
    }
}
