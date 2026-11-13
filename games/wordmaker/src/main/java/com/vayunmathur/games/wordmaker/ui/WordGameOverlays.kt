// RAW ANIMATION EXCEPTION: the letter-flight is an imperative motion - letters fly from the
// chooser to their grid cells on a per-letter Animatable, which is not a state a declarative
// animation can settle on.
package com.vayunmathur.games.wordmaker.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.wordmaker.data.CrosswordData
import com.vayunmathur.games.wordmaker.platform.WordGameActions
import com.vayunmathur.games.wordmaker.ui.components.AnimatedLetter
import com.vayunmathur.games.wordmaker.ui.components.SurfaceText
import com.vayunmathur.games.wordmaker.ui.dialogs.BonusWordsDialog
import com.vayunmathur.games.wordmaker.ui.dialogs.DefinitionDialog
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

/**
 * Dialogs and flying-letter overlays for the word game, extracted from
 * [WordGameScreen] to keep that file under the length limit.
 * Behavior identical — only moved.
 */
@Composable
fun WordGameOverlays(
    showHintDialog: Boolean,
    onDismissHint: () -> Unit,
    crosswordData: CrosswordData,
    foundWords: Set<String>,
    revealedHints: Set<Pair<Int, Int>>,
    actions: WordGameActions,
    showBonusWordsDialog: Boolean,
    onDismissBonusWords: () -> Unit,
    bonusWords: Set<String>,
    wordWithDefinition: Pair<String, List<String>>?,
    onDismissDefinition: () -> Unit,
    animatedWord: String?,
    animationProgress: Animatable<Float, AnimationVector1D>,
    wordBoxOffset: Offset,
    bonusButtonOffset: Offset,
    animatedLetters: List<AnimatedLetter>,
    scale: Float,
) {
    if (showHintDialog) {
        AlertDialog(
            onDismissRequest = onDismissHint,
            title = { Text(com.vayunmathur.games.wordmaker.R.string.hint_confirmation.let { androidx.compose.ui.res.stringResource(it) }) },
            confirmButton = {
                Button(onClick = {
                    actions.revealHint(crosswordData, foundWords, revealedHints)
                    onDismissHint()
                }) {
                    Text(androidx.compose.ui.res.stringResource(com.vayunmathur.games.wordmaker.R.string.yes))
                }
            },
            dismissButton = {
                Button(onClick = onDismissHint) {
                    Text(androidx.compose.ui.res.stringResource(com.vayunmathur.games.wordmaker.R.string.no))
                }
            }
        )
    }

    if (showBonusWordsDialog) {
        BonusWordsDialog(bonusWords = bonusWords, getDefinition = actions::getDefinition) {
            onDismissBonusWords()
        }
    }

    wordWithDefinition?.let { (word, definition) ->
        DefinitionDialog(word, definition) {
            onDismissDefinition()
        }
    }

    // Flight positions are root coordinates (localToRoot), which never mirror. Place the
    // flights from the absolute top-left with absoluteOffset: offset {} and a TopStart parent
    // both mirror in RTL, which sent every flight to the opposite side of the screen.
    if (animatedWord == null && animatedLetters.isEmpty()) return
    Box(Modifier.fillMaxSize(), contentAlignment = AbsoluteAlignment.TopLeft) {
        animatedWord?.let { word ->
            val progress = animationProgress.value
            val currentOffset = lerp(wordBoxOffset, bonusButtonOffset, progress)
            val alpha = 1f - progress
            val animScale = 1f - (progress * 0.5f)

            Text(
                text = word,
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp,
                modifier = Modifier
                    .absoluteOffset { IntOffset(currentOffset.x.toInt(), currentOffset.y.toInt()) }
                    .graphicsLayer(
                        scaleX = animScale,
                        scaleY = animScale,
                        alpha = alpha
                    )
            )
        }
        val (size, fontSize) = Pair(35.dp * scale, 18.sp * scale)
        animatedLetters.forEach { letter ->
            val progress = letter.progress.value
            val offset = lerp(letter.startOffset, letter.endOffset, progress)

            SurfaceText(Modifier.absoluteOffset { IntOffset(offset.x.toInt(), offset.y.toInt()) },
                RoundedCornerShape(4.dp * scale),
                MaterialTheme.colorScheme.primary, letter.char.toString(),
                Modifier, FontWeight.Bold, fontSize, size,
                textColor = MaterialTheme.colorScheme.onPrimary)
        }
    }
}
