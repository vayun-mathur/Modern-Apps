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

/**
 * A functional key that only needs a tap (page toggles, comma/period, emoji, enter).
 *
 * [onLongClick] is optional and is the only extra gesture the key knows: unlike [CharKey]
 * there is no popup to slide onto, so the press simply fires once the hold is long enough.
 */
@Composable
fun RowScope.SpecialKey(
    height: Dp,
    weight: Float = 1f,
    containerColor: Color = specialKeyColor(),
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    pressedContainerColor: Color = pressedKeyColor(),
    pressedContentColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val tick = rememberKeyHapticTick()
    Box(
        modifier = Modifier
            .weight(weight)
            .height(height + KeyPadding * 2)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onLongClickLabel = onLongClickLabel,
                onLongClick = {
                    tick()
                    onLongClick?.invoke()
                },
                onClick = {
                    tick()
                    onClick()
                },
            )
            .padding(KeyPadding)
            .clip(KeyShape)
            .background(if (pressed) pressedContainerColor else containerColor),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (pressed) pressedContentColor else contentColor,
        ) { content() }
    }
}
