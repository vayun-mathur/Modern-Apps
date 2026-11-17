package com.vayunmathur.keyboard.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.keyboard.R
import com.vayunmathur.keyboard.platform.ime.EnterAction
import com.vayunmathur.keyboard.platform.ime.ImeActions
import com.vayunmathur.keyboard.platform.ime.KeyboardState
import com.vayunmathur.keyboard.domain.KeyboardPage
import com.vayunmathur.keyboard.domain.Layouts
import com.vayunmathur.library.ui.IconArrowForward
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconEmoji
import com.vayunmathur.library.ui.IconReturn
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconSend
import com.vayunmathur.library.ui.IconSkipNext
import com.vayunmathur.library.ui.IconSkipPrevious
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

@Composable
internal fun BottomRow(
    state: KeyboardState,
    actions: ImeActions,
    keyHeight: Dp,
    leftLabel: String,
    leftTarget: KeyboardPage,
    commaChar: String = ",",
    periodChar: String = ".",
) {
    Row(Modifier.fillMaxWidth()) {
        SpecialKey(keyHeight, 1.5f, onClick = { actions.setPage(leftTarget) }) {
            Text(leftLabel, fontSize = 14.sp)
        }
        SpecialKey(keyHeight, 1f, onClick = { actions.setPage(KeyboardPage.EMOJI) }) { IconEmoji() }
        CharKey(
            label = commaChar,
            height = keyHeight,
            weight = 1f,
            alternates = punctuationAlternates(commaChar),
            onClick = { actions.onChar(commaChar) },
            onAlternate = actions::onChar,
        )
        SpaceKey(
            height = keyHeight,
            weight = 4f,
            label = state.effectiveLayout.name,
            onSpace = actions::onSpace,
        )
        CharKey(
            label = periodChar,
            height = keyHeight,
            weight = 1f,
            alternates = punctuationAlternates(periodChar),
            onClick = { actions.onChar(periodChar) },
            onAlternate = actions::onChar,
        )
        EnterKey(state, actions, keyHeight, 1.5f)
    }
}

/**
 * Alternates for the two punctuation keys beside the space bar. A layout may put a
 * multi-character string there (or the field flavour may swap in `@`/`/`), and only a
 * single character can have alternates.
 */
internal fun punctuationAlternates(label: String): String =
    label.singleOrNull()?.let { Layouts.SYMBOL_ALTERNATES[it] }.orEmpty()

@Composable
internal fun RowScope.EnterKey(state: KeyboardState, actions: ImeActions, keyHeight: Dp, weight: Float) {
    SpecialKey(
        height = keyHeight,
        weight = weight,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        pressedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        pressedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        onClick = actions::onEnter,
        // Enter had no hold gesture of its own, and it is the one key that is present on
        // every page, which is why dictation lives here (issue #678).
        onLongClick = actions::onVoiceInput,
        onLongClickLabel = stringResource(R.string.voice_input),
    ) {
        // A custom action carries an app-supplied label ("Join", "Post", ...) whose purpose
        // we can't map to an icon, so that one case stays textual; every standard action
        // gets a glyph.
        val customLabel = state.enterActionLabel
        if (customLabel != null) {
            Text(customLabel, fontSize = 14.sp, maxLines = 1)
        } else {
            EnterActionIcon(state.enterAction)
        }
    }
}

/**
 * The glyph for each Enter purpose. Arrow/send/return icons are AutoMirrored so they flip
 * in RTL layouts; the skip pair used for field navigation is deliberately directional in
 * the media sense and does not mirror.
 */
@Composable
internal fun EnterActionIcon(action: EnterAction) = when (action) {
    EnterAction.SEARCH -> IconSearch()
    EnterAction.SEND -> IconSend()
    EnterAction.DONE -> IconCheck()
    EnterAction.GO -> IconArrowForward()
    EnterAction.NEXT -> IconSkipNext()
    EnterAction.PREVIOUS -> IconSkipPrevious()
    EnterAction.RETURN -> IconReturn()
}
