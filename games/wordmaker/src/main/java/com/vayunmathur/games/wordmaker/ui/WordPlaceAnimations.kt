// RAW ANIMATION EXCEPTION: the letter-flight and bonus-flight are imperative motion - letters
// fly from the chooser to their grid cells on per-letter Animatables drained from a FIFO queue,
// which is not a state a declarative animation can settle on.
package com.vayunmathur.games.wordmaker.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.wordmaker.data.CrosswordData
import com.vayunmathur.games.wordmaker.platform.WordGameActions
import com.vayunmathur.games.wordmaker.ui.components.AnimatedLetter
import com.vayunmathur.games.wordmaker.ui.components.WordToAnimate
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * Live state of the queued word-place flights, drained by [rememberWordPlaceAnimations].
 */
class WordPlaceAnimations(
    /** Solution words accepted but not yet visible in the found-words flow. */
    val pendingSolutionWords: Set<String>,
    /** Bonus words accepted but not yet visible in the bonus-words flow. */
    val queuedBonusWords: Set<String>,
    /** Solution word currently flying into the grid, for the board's hide-while-flying slot. */
    val activeSolutionWord: String?,
    /** Bonus word currently flying toward the bonus button. */
    val activeBonusWord: String?,
    val bonusProgress: Animatable<Float, AnimationVector1D>,
    val animatedLetters: List<AnimatedLetter>,
    val enqueueSolution: (WordToAnimate) -> Unit,
    val enqueueBonus: (String) -> Unit,
)

/**
 * FIFO word-place flights for the letter wheel (#807), extracted from [WordGameScreen]
 * to keep that file under the length limit. Every submitted word finishes its full flight:
 * a fast second word queues behind the first instead of cancelling it (which used to drop
 * the first word's grid/bonus credit entirely).
 */
@Composable
fun rememberWordPlaceAnimations(
    levelKey: String,
    crosswordData: CrosswordData,
    foundWords: Set<String>,
    actions: WordGameActions,
    density: Density,
    rootOffset: Offset,
    letterChooserPositions: Map<Int, Offset>,
    crosswordCellPositions: Map<Pair<Int, Int>, Offset>,
): WordPlaceAnimations {
    var solutionQueue by remember(levelKey) { mutableStateOf<List<WordToAnimate>>(emptyList()) }
    var activeSolutionWord by remember(levelKey) { mutableStateOf<String?>(null) }
    var flushedSolutions by remember(levelKey) { mutableStateOf<Set<String>>(emptySet()) }
    var bonusQueue by remember(levelKey) { mutableStateOf<List<String>>(emptyList()) }
    var activeBonusWord by remember(levelKey) { mutableStateOf<String?>(null) }
    val bonusProgress = remember(levelKey) { Animatable(0f) }
    var animatedLetters by remember(levelKey) { mutableStateOf<List<AnimatedLetter>>(emptyList()) }

    // Solution flights drain head-to-tail: appending to the queue never restarts the
    // in-flight effect because its key (the head word) is unchanged, so each word flies
    // the full flight before the next one starts.
    LaunchedEffect(solutionQueue.firstOrNull()) {
        val animationInfo = solutionQueue.firstOrNull() ?: return@LaunchedEffect
        val word = animationInfo.word
        val letterPositions = crosswordData.letterPositions[word]?.firstOrNull()
        if (letterPositions == null) {
            solutionQueue = solutionQueue.drop(1)
            return@LaunchedEffect
        }
        activeSolutionWord = word
        val letters = word.mapIndexed { index, char ->
            val id = animationInfo.letterIds[index]
            val start = (letterChooserPositions[id] ?: Offset.Zero) - rootOffset
            val end = (crosswordCellPositions[letterPositions[index]] ?: Offset.Zero) - rootOffset
            val offsetCorrection = with(density) { 15.dp.toPx() }
            val correctedStart = start.plus(Offset(offsetCorrection, offsetCorrection))
            AnimatedLetter(char, correctedStart, end, Animatable(0f))
        }
        animatedLetters = letters
        letters.map { launch { it.progress.animateTo(1f, tween(LETTER_FLIGHT_MILLIS)) } }.joinAll()
        actions.addFoundWord(word)
        animatedLetters = emptyList()
        activeSolutionWord = null
        flushedSolutions = flushedSolutions + word
        solutionQueue = solutionQueue.drop(1)
    }

    // A finished flight takes a DataStore round-trip to show up in the found-words flow;
    // keep it in the dedup set until then so re-entering it shakes instead of replaying.
    LaunchedEffect(foundWords) {
        if (flushedSolutions.isNotEmpty()) flushedSolutions = flushedSolutions - foundWords
    }

    // Bonus flights share one progress Animatable, so they fly one at a time too: a fast
    // second word used to steal it mid-flight and skip the first word's addBonusWord.
    LaunchedEffect(bonusQueue.firstOrNull()) {
        val word = bonusQueue.firstOrNull() ?: return@LaunchedEffect
        activeBonusWord = word
        bonusProgress.snapTo(0f)
        bonusProgress.animateTo(1f, tween(BONUS_FLIGHT_MILLIS))
        actions.addBonusWord(word)
        activeBonusWord = null
        bonusQueue = bonusQueue.drop(1)
    }

    return WordPlaceAnimations(
        pendingSolutionWords = (solutionQueue.map { it.word } + flushedSolutions).toSet(),
        queuedBonusWords = bonusQueue.toSet(),
        activeSolutionWord = activeSolutionWord,
        activeBonusWord = activeBonusWord,
        bonusProgress = bonusProgress,
        animatedLetters = animatedLetters,
        enqueueSolution = { solutionQueue = solutionQueue + it },
        enqueueBonus = { bonusQueue = bonusQueue + it },
    )
}

private const val LETTER_FLIGHT_MILLIS = 800
private const val BONUS_FLIGHT_MILLIS = 800

/**
 * Top-left of the flying bonus word at [progress], in the overlay's coordinate space.
 *
 * Both ends arrive as root coordinates (localToRoot), so [rootOffset] - where the overlay itself
 * sits in the root - is subtracted, exactly as the solution flights do; without it the word landed
 * that far below the button, off the bottom of the screen. It ends centred on the bonus button
 * ([bonusCenter]) rather than with its top-left corner there, so as it shrinks around its own
 * centre it disappears into the button.
 */
internal fun bonusFlightOffset(
    wordBox: Offset,
    bonusCenter: Offset,
    rootOffset: Offset,
    wordSize: IntSize,
    progress: Float,
): Offset {
    val start = wordBox - rootOffset
    val end = bonusCenter - rootOffset - Offset(wordSize.width / 2f, wordSize.height / 2f)
    return lerp(start, end, progress)
}
