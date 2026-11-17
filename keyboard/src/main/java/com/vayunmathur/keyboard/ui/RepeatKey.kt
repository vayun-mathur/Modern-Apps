@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.vayunmathur.keyboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.keyboard.domain.ShiftState
import com.vayunmathur.library.ui.IconShift
import com.vayunmathur.library.ui.LocalContentColor
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Backspace with press-and-hold repeat delete. */
@Composable
fun RowScope.RepeatKey(
    height: Dp,
    weight: Float,
    onRepeat: () -> Unit,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Captured for the pointerInput block below, which can't read CompositionLocals itself.
    val currentTick by androidx.compose.runtime.rememberUpdatedState(rememberKeyHapticTick())
    Box(
        modifier = Modifier
            .weight(weight)
            .height(height + KeyPadding * 2)
            .pointerInput(Unit) {
                detectTapGestures(onPress = { offset ->
                    val press = PressInteraction.Press(offset)
                    interaction.emit(press)
                    // Once per press, not per repeat: holding backspace buzzes once, then
                    // deletes silently the way Gboard does.
                    currentTick()
                    onRepeat()
                    val job = scope.launch {
                        delay(350)
                        while (true) {
                            onRepeat()
                            delay(45)
                        }
                    }
                    val released = tryAwaitRelease()
                    job.cancel()
                    interaction.emit(
                        if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press),
                    )
                })
            }
            .padding(KeyPadding)
            .clip(KeyShape)
            .background(if (pressed) pressedKeyColor() else specialKeyColor()),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) { content() }
    }
}
