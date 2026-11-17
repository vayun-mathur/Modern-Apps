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

/** Shift key: single tap toggles shift, double tap latches caps-lock. */
@Composable
fun RowScope.ShiftKey(
    height: Dp,
    weight: Float,
    shift: ShiftState,
    onShift: () -> Unit,
) {
    val active = shift != ShiftState.OFF
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val container = when {
        pressed -> pressedKeyColor()
        active -> MaterialTheme.colorScheme.primaryContainer
        else -> specialKeyColor()
    }
    val content = when {
        pressed -> MaterialTheme.colorScheme.onSurface
        active -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    // Captured for the pointerInput block below, which can't read CompositionLocals itself.
    val currentTick by androidx.compose.runtime.rememberUpdatedState(rememberKeyHapticTick())
    Box(
        modifier = Modifier
            .weight(weight)
            .height(height + KeyPadding * 2)
            .pointerInput(Unit) {
                // Fire on press-up immediately (no double-tap wait). Caps-lock is detected
                // from tap timing in the service, so shift responds instantly.
                detectTapGestures(
                    onPress = { offset ->
                        val press = PressInteraction.Press(offset)
                        interaction.emit(press)
                        val released = tryAwaitRelease()
                        interaction.emit(
                            if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press),
                        )
                        if (released) {
                            currentTick()
                            onShift()
                        }
                    },
                )
            }
            .padding(KeyPadding)
            .clip(KeyShape)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        IconShift(tint = content)
        if (shift == ShiftState.CAPS_LOCK) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 7.dp)
                    .size(width = 14.dp, height = 2.dp)
                    .background(content),
            )
        }
    }
}
