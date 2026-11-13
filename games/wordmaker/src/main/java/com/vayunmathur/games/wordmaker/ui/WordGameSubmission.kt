// RAW ANIMATION EXCEPTION: the rejection-shake is an imperative motion - a rejected word is
// walked through a hand-written sequence of decaying offsets, which is not a state a
// declarative animation can settle on.
package com.vayunmathur.games.wordmaker.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.wordmaker.data.CrosswordData
import com.vayunmathur.games.wordmaker.platform.WordGameActions
import com.vayunmathur.games.wordmaker.ui.components.WordToAnimate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Word-submission decision table for the letter wheel, extracted from
 * [WordGameScreen] to keep that file under the length limit.
 * Behavior identical — only moved.
 */
fun wordSubmitHandler(
    crosswordData: CrosswordData,
    foundWords: Set<String>,
    bonusWords: Set<String>,
    pendingSolutionWords: Set<String>,
    queuedBonusWords: Set<String>,
    actions: WordGameActions,
    density: Density,
    wordShakeAnim: Animatable<Float, AnimationVector1D>,
    bonusShakeAnim: Animatable<Float, AnimationVector1D>,
    onAnimateSolution: (WordToAnimate) -> Unit,
    onAnimateBonus: (String) -> Unit,
): suspend CoroutineScope.(String, List<Int>) -> Unit = { word, ids ->
    suspend fun shakeAnim(anim: Animatable<Float, AnimationVector1D>, duration: Int = 40) {
        for (o in listOf(-16f, 12f, -8f, 6f, -3f, 0f)) {
            anim.animateTo(with(density) { o.dp.toPx() }, tween(duration))
        }
    }

    val isSolution = word in crosswordData.solutionWords
    val isBonus = !isSolution && word.length >= 3 && actions.isInDictionary(word)

    // #543 — re-entering an already-found/bonus word must not
    // replay the board/bonus animation. Check for duplicates
    // first, including words still queued or mid-flight (#807,
    // not yet flushed to DataStore + foundWords/bonusWords flow).
    val alreadyFound = word in foundWords || word in pendingSolutionWords
    val alreadyBonus = word in bonusWords || word in queuedBonusWords
    when {
        isSolution && alreadyFound -> shakeAnim(wordShakeAnim)
        isBonus && alreadyBonus -> {
            val j = launch { shakeAnim(bonusShakeAnim, 60) }
            shakeAnim(wordShakeAnim)
            j.join()
        }
        isSolution && word !in foundWords -> {
            // Credit the word now; the letter flight is cosmetic. Crediting it only when the
            // 800 ms flight landed let the competitive timer run out first, so a word entered in
            // the last ~0.8 s did not count and the player lost points.
            onAnimateSolution(WordToAnimate(word, ids))
            actions.addFoundWord(word)
            actions.onSolutionWordFound(word)
        }
        // #807: only enqueue — the screen drains the bonus queue one flight at a
        // time, so a fast second word no longer steals the shared progress
        // Animatable and cancels the first word's flight (and its addBonusWord).
        isBonus && word !in bonusWords -> onAnimateBonus(word)
        else -> shakeAnim(wordShakeAnim)
    }
}
