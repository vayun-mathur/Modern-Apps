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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
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
import com.vayunmathur.library.ui.IconHelp
import com.vayunmathur.library.ui.IconBook
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Text
import kotlinx.coroutines.delay

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
    /** Epoch millis the competitive timer runs out; 0 when no timer runs. */
    competitiveDeadline: Long,
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
    onLetterPositioned: (Int, Offset) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (isCompetitive) {
            CompetitiveCountdown(
                score = competitiveScore,
                deadline = competitiveDeadline,
                running = !isWon && !timedOut,
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
                    onLetterPositioned = onLetterPositioned,
                    // Read in the word box's graphicsLayer, not here: reading the shake value
                    // in composition recomposed the whole wheel on every shake frame.
                    wordShakeTranslation = { wordShakeAnim.value }
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
    /** Current shake offset, read in the draw layer so a shake does not recompose. */
    bonusShake: () -> Float,
    onOpenBonusWords: () -> Unit,
    /** Receives the bonus button's centre in root coordinates. */
    onBonusButtonPositioned: (Offset) -> Unit,
    /** Epoch millis until which the hint is cooling down; 0 means available. */
    hintCooldownEnd: Long,
    onOpenHint: () -> Unit,
) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .align(Alignment.BottomStart)
            // Same 16 dp inset as the hint button opposite, so the two corner buttons line up
            // (the extra 32 dp bottom padding left the bonus button floating higher).
            .padding(16.dp)
            // Report the button's centre, the bonus flight's landing point. Measured outside
            // the shake layer, so a shake does not move the target.
            .onGloballyPositioned {
                onBonusButtonPositioned(it.localToRoot(Offset(it.size.width / 2f, it.size.height / 2f)))
            }
            .graphicsLayer {
                translationX = bonusShake()
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
        HintButton(
            cooldownEnd = hintCooldownEnd,
            onOpenHint = onOpenHint,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        )
    }
}

/**
 * The hint button with its cooldown ring. The cooldown clock ticks here, not in the screen:
 * as screen state it recomposed the whole game ten times a second. Only the enabled flag is
 * read in composition; the ring reads the clock while drawing.
 */
@Composable
private fun HintButton(cooldownEnd: Long, onOpenHint: () -> Unit, modifier: Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(cooldownEnd) {
        while (true) {
            now = System.currentTimeMillis()
            if (now >= cooldownEnd) break
            delay(COOLDOWN_TICK_MS)
        }
    }
    val hintEnabled by remember(cooldownEnd) { derivedStateOf { now >= cooldownEnd } }
    Box(modifier) {
        FilledIconButton(
            onClick = onOpenHint,
            enabled = hintEnabled
        ) {
            // The button's disabled colours already dim the icon during the cooldown; a
            // second 0.5 alpha on top made it nearly invisible.
            IconHelp(contentDescription = stringResource(R.string.cd_hint))
        }
        if (!hintEnabled) {
            CircularProgressIndicator(
                progress = { 1f - ((cooldownEnd - now).coerceAtLeast(0L) / HINT_COOLDOWN_MS) },
                modifier = Modifier.size(48.dp).align(Alignment.Center),
                strokeWidth = 3.dp
            )
        }
    }
}

/**
 * Competitive score and countdown. The countdown ticks locally, so only this bar recomposes
 * as the seconds run down, not the screen. It stops (frozen) once the level is over.
 */
@Composable
private fun CompetitiveCountdown(score: Int, deadline: Long, running: Boolean) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(deadline, running) {
        // One fresh reading even when stopping, so the frozen value is the moment it ended.
        now = System.currentTimeMillis()
        while (running && deadline > 0L && now < deadline) {
            delay(COUNTDOWN_TICK_MS)
            now = System.currentTimeMillis()
        }
    }
    CompetitiveStatusBar(
        score = score,
        remainingTimeMs = if (deadline > 0L) (deadline - now).coerceAtLeast(0L) else 0L,
    )
}

private const val COOLDOWN_TICK_MS = 100L
private const val COUNTDOWN_TICK_MS = 200L

/** Length of the hint cooldown the ring counts down. */
private const val HINT_COOLDOWN_MS = 30_000f

/** Most bonus words the badge spells out; beyond it shows "99+". */
private const val BONUS_BADGE_MAX = 99

/** Badge text for [count] bonus words: the number, capped at "99+". */
internal fun bonusBadgeText(count: Int): String =
    if (count > BONUS_BADGE_MAX) "$BONUS_BADGE_MAX+" else count.toString()
