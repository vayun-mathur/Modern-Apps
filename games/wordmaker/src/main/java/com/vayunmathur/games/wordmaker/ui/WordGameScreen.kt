// RAW ANIMATION EXCEPTION: the letter-flight and rejection-shake are imperative motion - letters
// fly from the chooser to their grid cells on a per-letter Animatable, and a rejected word is
// walked through a hand-written sequence of decaying offsets, neither of which is a state a
// declarative animation can settle on.
package com.vayunmathur.games.wordmaker.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.wordmaker.R
import com.vayunmathur.games.wordmaker.data.CrosswordData
import com.vayunmathur.games.wordmaker.data.GameMode
import com.vayunmathur.games.wordmaker.platform.WordGameActions
import com.vayunmathur.games.wordmaker.platform.WordGameUiState
import com.vayunmathur.library.ui.AppBarAlignment
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.game.GameTopBarActions
import com.vayunmathur.library.util.AchievementsManager
import com.vayunmathur.games.wordmaker.ui.components.CrosswordBoard
import com.vayunmathur.games.wordmaker.ui.components.WordMakerModeChooser
import kotlinx.coroutines.delay

/** Room above the wheel for the formed-word box and its padding. */
private val WHEEL_HEADROOM = 70.dp

@Composable
fun WordGameScreen(
    state: WordGameUiState,
    actions: WordGameActions,
    onOpenGameCenter: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val crosswordData = state.crosswordData
    val currentLevel = state.currentLevel
    val foundWords = state.foundWords
    val bonusWords = state.bonusWords
    val tapToSpell = state.tapToSpell
    val wheelSpacing = state.wheelSpacing
    val revealedHints = state.revealedHints
    val hintCooldownEnd = state.hintCooldownEnd
    val gameMode = state.gameMode
    val competitiveScore = state.competitiveScore
    val competitiveLevelNumber = state.competitiveLevelNumber
    val competitiveDeadline = state.competitiveDeadline
    val isCompetitive = gameMode == GameMode.COMPETITIVE
    val isDaily = gameMode == GameMode.DAILY
    val levelKey = when (gameMode) {
        GameMode.COMPETITIVE -> "c$competitiveLevelNumber"
        GameMode.DAILY -> "d${state.dailyDay}"
        GameMode.CASUAL -> "n$currentLevel"
    }
    var showBonusWordsDialog by remember(levelKey) { mutableStateOf(false) }
    var showHintDialog by remember(levelKey) { mutableStateOf(false) }
    var remainingCooldown by remember { mutableLongStateOf(0L) }
    var remainingTime by remember(levelKey) { mutableLongStateOf(0L) }
    var timedOut by remember(levelKey) { mutableStateOf(false) }
    val density = LocalDensity.current
    var rootOffset by remember { mutableStateOf(Offset.Zero) }
    var wordWithDefinition by remember { mutableStateOf<Pair<String, List<String>>?>(null) }

    // Animation state: solution and bonus word-place flights queue up (#807) so a fast
    // second word waits for the first flight to finish instead of cancelling it.
    var wordBoxOffset by remember(levelKey) { mutableStateOf(Offset.Zero) }
    var bonusButtonCenter by remember(levelKey) { mutableStateOf(Offset.Zero) }
    var crosswordCellPositions by remember(levelKey) {
        mutableStateOf<Map<Pair<Int, Int>, Offset>>(
            emptyMap()
        )
    }
    var letterChooserPositions by remember(levelKey) {
        mutableStateOf<Map<Int, Offset>>(
            emptyMap()
        )
    }
    val wordPlaceAnimations = rememberWordPlaceAnimations(
        levelKey = levelKey,
        crosswordData = crosswordData,
        foundWords = foundWords,
        actions = actions,
        density = density,
        rootOffset = rootOffset,
        letterChooserPositions = letterChooserPositions,
        crosswordCellPositions = crosswordCellPositions,
    )

    // Animatables for shaking (we'll animate them directly when submission fails)
    val wordShakeAnim = remember { Animatable(0f) }
    val bonusShakeAnim = remember { Animatable(0f) }

    var scale by remember { mutableFloatStateOf(1f) }
    var shuffledLetters by remember(crosswordData) {
        mutableStateOf(crosswordData.lettersInChooser.mapIndexed { index, char ->
            ChooserLetter(index, char)
        })
    }


    val isWon = crosswordData.winsWith(foundWords)

    LaunchedEffect(competitiveDeadline, isCompetitive, isWon, levelKey) {
        if (!isCompetitive || isWon || competitiveDeadline <= 0L) {
            if (competitiveDeadline <= 0L && !isWon) remainingTime = 0L
            return@LaunchedEffect
        }
        while (true) {
            remainingTime = (competitiveDeadline - System.currentTimeMillis()).coerceAtLeast(0)
            if (remainingTime <= 0L) break
            delay(200)
        }
        if (!isWon) {
            timedOut = true
            actions.onCompetitiveTimeout()
        }
    }

    LaunchedEffect(hintCooldownEnd) {
        while (true) {
            remainingCooldown = (hintCooldownEnd - System.currentTimeMillis()).coerceAtLeast(0)
            if (remainingCooldown <= 0) break
            delay(100)
        }
    }

    AppScaffold(
        modifier = Modifier.fillMaxSize(),
        title = {
            WordMakerModeChooser(
                selected = gameMode,
                onSelected = { actions.setGameMode(it) },
                levelNumber = currentLevel,
            )
        },
        actions = {
            GameTopBarActions(
                onOpenGameCenter = onOpenGameCenter,
                onOpenSettings = onOpenSettings,
            )
        },
        alignment = AppBarAlignment.Center,
        scrollBehavior = appBarScrollBehavior(),
    ) { innerPadding ->
        // Wide windows letterbox the whole play area at a readable measure
        // instead of stretching the crossword across the window; the board
        // itself never splits. Bars stay full-bleed — only the body is capped.
        DesktopMaxWidthContainer(modifier = Modifier.padding(innerPadding)) {
        Box(
            modifier = Modifier
                .padding(bottom = 32.dp)
                .fillMaxSize()
                .onGloballyPositioned {
                    rootOffset = it.localToRoot(Offset.Zero)
                }
        ) {
            // Puzzle board occupies the space above the letter wheel so it stays
            // vertically centred there. The wheel below reserves its own height.
            val wheelHeight = wheelSpacing.boxSize + WHEEL_HEADROOM
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = wheelHeight),
                contentAlignment = Alignment.Center
            ) {
                CrosswordBoard(
                    foundWords = foundWords,
                    revealedHints = revealedHints,
                    crosswordData = crosswordData,
                    hiddenWords = wordPlaceAnimations.flyingSolutionWords,
                    onCellPositioned = { position, offset ->
                        if (crosswordCellPositions[position] != offset) {
                            crosswordCellPositions =
                                crosswordCellPositions + (position to offset)
                        }
                    },
                    onCellClicked = { row, col ->
                        val word = crosswordData.getWordAt(row, col, foundWords)
                        if (word != null && word in foundWords) {
                            wordWithDefinition = Pair(word, actions.getDefinition(word))
                        }
                    }, {
                        scale = it
                    }
                )
            }
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                WordGameWheel(
                    isCompetitive = isCompetitive,
                    isDaily = isDaily,
                    competitiveScore = competitiveScore,
                    remainingTimeMs = remainingTime,
                    dailyStreak = state.dailyStreak,
                    wheelHeight = wheelHeight,
                    isWon = isWon,
                    timedOut = timedOut,
                    currentLevel = currentLevel,
                    actions = actions,
                    shuffledLetters = shuffledLetters,
                    onShuffle = {
                        var nextLetters = shuffledLetters.shuffled()
                        while (nextLetters == shuffledLetters && shuffledLetters.size > 1) {
                            nextLetters = shuffledLetters.shuffled()
                        }
                        shuffledLetters = nextLetters
                    },
                    tapToSpell = tapToSpell,
                    wheelSpacing = wheelSpacing,
                    crosswordData = crosswordData,
                    foundWords = foundWords,
                    bonusWords = bonusWords,
                    animations = wordPlaceAnimations,
                    density = density,
                    wordShakeAnim = wordShakeAnim,
                    bonusShakeAnim = bonusShakeAnim,
                    onWordBoxPositioned = { wordBoxOffset = it },
                    letterChooserPositions = letterChooserPositions,
                    onLetterPositioned = { id, offset ->
                        letterChooserPositions = letterChooserPositions + (id to offset)
                    },
                )
            }

            WordGameCornerButtons(
                isWon = isWon,
                isCompetitive = isCompetitive,
                bonusWords = bonusWords,
                bonusShake = bonusShakeAnim.value,
                onOpenBonusWords = { showBonusWordsDialog = true },
                onBonusButtonPositioned = { bonusButtonCenter = it },
                remainingCooldownMs = remainingCooldown,
                onOpenHint = { showHintDialog = true },
            )

            WordGameOverlays(
                showHintDialog = showHintDialog,
                onDismissHint = { showHintDialog = false },
                crosswordData = crosswordData,
                foundWords = foundWords,
                revealedHints = revealedHints,
                actions = actions,
                showBonusWordsDialog = showBonusWordsDialog,
                onDismissBonusWords = { showBonusWordsDialog = false },
                bonusWords = bonusWords,
                wordWithDefinition = wordWithDefinition,
                onDismissDefinition = { wordWithDefinition = null },
                animatedWord = wordPlaceAnimations.activeBonusWord,
                animationProgress = wordPlaceAnimations.bonusProgress,
                wordBoxOffset = wordBoxOffset,
                bonusButtonCenter = bonusButtonCenter,
                rootOffset = rootOffset,
                animatedLetters = wordPlaceAnimations.animatedLetters,
                scale = scale,
            )
        }
        }
    }
}
