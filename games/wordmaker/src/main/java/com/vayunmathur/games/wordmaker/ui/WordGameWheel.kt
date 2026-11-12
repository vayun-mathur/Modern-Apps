package com.vayunmathur.games.wordmaker.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.wordmaker.R
import com.vayunmathur.games.wordmaker.data.CrosswordData
import com.vayunmathur.games.wordmaker.data.WheelSpacing
import com.vayunmathur.games.wordmaker.platform.WordGameActions
import com.vayunmathur.games.wordmaker.ui.components.CompetitiveStatusBar
import com.vayunmathur.games.wordmaker.ui.components.DailyStatusBar
import com.vayunmathur.games.wordmaker.ui.components.LetterChooser
import com.vayunmathur.library.ui.Badge
import com.vayunmathur.library.ui.BadgedBox
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.FilledIconButton
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.IconBook
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Text

/**
 * Status bars plus the letter wheel below the board, extracted from
 * [WordGameScreen] to keep that file under the length limit.
 * Behavior identical — only moved.
 */
@Composable
fun WordGameWheel(
    isCompetitive: Boolean,
    isDaily: Boolean,
    competitiveScore: Int,
    remainingTimeMs: Long,
    dailyStreak: Long,
    wheelHeight: Dp,
    isWon: Boolean,
    timedOut: Boolean,
    currentLevel: Int,
    actions: WordGameActions,
    shuffledLetters: List<ChooserLetter>,
    onShuffle: () -> Unit,
    tapToSpell: Boolean,
    wheelSpacing: WheelSpacing,
    crosswordData: CrosswordData,
    foundWords: Set<String>,
    bonusWords: Set<String>,
    animations: WordPlaceAnimations,
    density: Density,
    wordShakeAnim: Animatable<Float, AnimationVector1D>,
    bonusShakeAnim: Animatable<Float, AnimationVector1D>,
    onWordBoxPositioned: (Offset) -> Unit,
    letterChooserPositions: Map<Int, Offset>,
    onLetterPositioned: (Int, Offset) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (isCompetitive) {
            CompetitiveStatusBar(
                score = competitiveScore,
                remainingTimeMs = remainingTimeMs
            )
        } else if (isDaily) {
            DailyStatusBar(streak = dailyStreak)
        }
        Spacer(modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier.height(wheelHeight),
            contentAlignment = Alignment.Center
        ) {
            if (isWon && isDaily) {
                Text(
                    stringResource(R.string.daily_come_back_tomorrow),
                    fontWeight = FontWeight.Bold
                )
            } else if (isWon && !isCompetitive) {
                Button(onClick = { actions.saveLevel(currentLevel + 1) }) {
                    Text(stringResource(UiR.string.next_level))
                }
            } else if (isCompetitive && (isWon || timedOut)) {
                // Level finished — the between-levels lobby (WordMakerGameLoader) takes over.
            } else {
                LetterChooser(
                    letters = shuffledLetters,
                    tapToSpell = tapToSpell,
                    wheelSpacing = wheelSpacing,
                    onShuffle = onShuffle,
                    onWordSubmitted = wordSubmitHandler(
                        crosswordData = crosswordData,
                        foundWords = foundWords,
                        bonusWords = bonusWords,
                        pendingSolutionWords = animations.pendingSolutionWords,
                        queuedBonusWords = animations.queuedBonusWords,
                        actions = actions,
                        density = density,
                        wordShakeAnim = wordShakeAnim,
                        bonusShakeAnim = bonusShakeAnim,
                        onAnimateSolution = animations.enqueueSolution,
                        onAnimateBonus = animations.enqueueBonus,
                    ),
                    onWordBoxPositioned = onWordBoxPositioned,
                    onLetterPositioned = { id, offset ->
                        if (letterChooserPositions[id] != offset) {
                            onLetterPositioned(id, offset)
                        }
                    },
                    wordShakeTranslation = wordShakeAnim.value
                )
            }
        }
    }
}

/**
 * Corner buttons (bonus words, hint) with their cooldown indicator, extracted
 * from [WordGameScreen] to keep that file under the length limit.
 * Behavior identical — only moved.
 */
@Composable
fun BoxScope.WordGameCornerButtons(
    isWon: Boolean,
    isCompetitive: Boolean,
    bonusWords: Set<String>,
    bonusShake: Float,
    onOpenBonusWords: () -> Unit,
    onBonusButtonPositioned: (Offset) -> Unit,
    remainingCooldownMs: Long,
    onOpenHint: () -> Unit,
) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(16.dp)
            .padding(bottom = 32.dp)
            .onGloballyPositioned { onBonusButtonPositioned(it.localToRoot(Offset.Zero)) }
            .graphicsLayer {
                translationX = bonusShake
            },
    ) {
        // Badge: how many bonus words this puzzle has so far (#915).
        BadgedBox(
            badge = {
                if (bonusWords.isNotEmpty()) {
                    Badge { Text(bonusBadgeText(bonusWords.size)) }
                }
            },
        ) {
            FilledIconButton(
                onClick = onOpenBonusWords,
                enabled = bonusWords.isNotEmpty()
            ) {
                IconBook(contentDescription = stringResource(R.string.bonus_words))
            }
        }
    }

    if (!isWon && !isCompetitive) {
        val hintEnabled = remainingCooldownMs <= 0L
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            FilledIconButton(
                onClick = onOpenHint,
                enabled = hintEnabled
            ) {
                Icon(
                    painterResource(android.R.drawable.ic_menu_help),
                    contentDescription = stringResource(R.string.cd_hint),
                    modifier = Modifier.graphicsLayer { alpha = if (hintEnabled) 1f else 0.5f }
                )
            }
            if (!hintEnabled) {
                CircularProgressIndicator(
                    progress = { 1f - (remainingCooldownMs / 30_000f) },
                    modifier = Modifier.size(48.dp).align(Alignment.Center),
                    strokeWidth = 3.dp
                )
            }
        }
    }
}

/** Most bonus words the badge spells out; beyond it shows "99+". */
private const val BONUS_BADGE_MAX = 99

/** Badge text for [count] bonus words: the number, capped at "99+". */
internal fun bonusBadgeText(count: Int): String =
    if (count > BONUS_BADGE_MAX) "$BONUS_BADGE_MAX+" else count.toString()
