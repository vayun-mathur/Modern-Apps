package com.vayunmathur.games.voxels.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/** Repeat interval while the Use button is held (the engine's own cooldowns cap attacks and eating). */
private const val USE_REPEAT_MS = 300L

// Dedicated use/attack button: acts on whatever is under the crosshair (attack a mob, eat, throw,
// equip, cast, mine) immediately on press and then repeats while held, so none of those need a
// long-press on the world any more.
@Composable
fun UseButton(label: String, onUse: () -> Unit) {
    val use by rememberUpdatedState(onUse)
    var held by remember { mutableStateOf(false) }
    LaunchedEffect(held) {
        while (held) {
            use()
            delay(USE_REPEAT_MS)
        }
    }
    HoldButton(label = label, onPress = { held = true }, onRelease = { held = false })
}
