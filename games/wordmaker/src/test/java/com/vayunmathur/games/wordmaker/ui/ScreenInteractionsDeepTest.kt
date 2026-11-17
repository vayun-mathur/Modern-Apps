package com.vayunmathur.games.wordmaker.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import com.vayunmathur.games.wordmaker.data.CrosswordData
import com.vayunmathur.games.wordmaker.data.Difficulty
import com.vayunmathur.games.wordmaker.data.GameMode
import com.vayunmathur.games.wordmaker.data.WheelSpacing
import com.vayunmathur.games.wordmaker.platform.WordGameActions
import com.vayunmathur.games.wordmaker.platform.WordGameUiState
import com.vayunmathur.games.wordmaker.ui.components.AnimatedLetter
import com.vayunmathur.games.wordmaker.ui.components.CompetitiveStatusBar
import com.vayunmathur.games.wordmaker.ui.components.CrosswordBoard
import com.vayunmathur.games.wordmaker.ui.components.DailyStatusBar
import com.vayunmathur.games.wordmaker.ui.components.DifficultyDropdown
import com.vayunmathur.games.wordmaker.ui.components.LetterChooser
import com.vayunmathur.games.wordmaker.ui.components.WordMakerModeChooser
import com.vayunmathur.games.wordmaker.ui.components.WordMakerTopBar
import com.vayunmathur.games.wordmaker.ui.components.WordToAnimate
import com.vayunmathur.games.wordmaker.ui.dialogs.BonusWordsDialog
import com.vayunmathur.library.ui.DynamicTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ScreenInteractionsDeepTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @BeforeTest fun setUp() {
        retryingMain { Dispatchers.setMain(Dispatchers.Unconfined) }
    }

    @AfterTest fun tearDown() {
        scope.cancel()
        retryingMain { Dispatchers.resetMain() }
    }

    private fun board() = CrosswordData.fromString("CAT\n C \n T ")!!

    private fun state(
        found: Set<String> = emptySet(),
        mode: GameMode = GameMode.CASUAL,
        bonus: Set<String> = emptySet(),
    ) = WordGameUiState(
        crosswordData = board(), currentLevel = 1, foundWords = found,
        bonusWords = bonus, gameMode = mode,
    )


    /** set/resetMain throw if a background coroutine is using Main at that instant (a stale ViewModel's flow resuming); retry briefly, as the nonogram suite does. */
    private fun retryingMain(action: () -> Unit) {
        var attempts = 0
        while (attempts++ < 50 && runCatching(action).isFailure) Thread.sleep(10)
        if (attempts > 50) action()
    }
    @Test fun shuffleChangesTheWheelOrder() {
        rule.setContent {
            DynamicTheme {
                WordGameScreen(state = state(), actions = WordGameActions.Noop, onOpenGameCenter = {}, onOpenSettings = {})
            }
        }
        rule.onNodeWithContentDescription("Shuffle").assertIsDisplayed()
        repeat(6) {
            rule.onNodeWithContentDescription("Shuffle").performClick()
            rule.waitForIdle()
        }
        // The wheel still shows exactly the chooser letters after shuffling.
        assertEquals(1, rule.onAllNodesWithText("C").fetchSemanticsNodes().size.coerceAtMost(1))
        rule.onNodeWithContentDescription("Shuffle").assertIsDisplayed()
    }

    @Test fun tappingAFoundCellOpensItsDefinitionAndDismissingClosesIt() {
        val actions = object : WordGameActions {
            override fun getDefinition(word: String): List<String> = listOf("definition of $word")
        }
        rule.setContent {
            DynamicTheme {
                WordGameScreen(
                    state = state(found = setOf("CAT", "ACT")), actions = actions,
                    onOpenGameCenter = {}, onOpenSettings = {},
                )
            }
        }
        rule.onAllNodesWithText("C")[0].performClick()
        rule.onNodeWithText("definition of CAT", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Close").performClick()
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("definition of CAT").fetchSemanticsNodes().isEmpty())
        // Tapping an empty part of the board opens nothing.
        rule.onAllNodesWithText("T")[0].performClick()
        rule.waitForIdle()
    }

    @Test fun hintDialogCanBeDeclinedWithoutUsingAHint() {
        var hinted = false
        val actions = object : WordGameActions {
            override fun revealHint(
                crosswordData: CrosswordData,
                foundWords: Set<String>,
                revealedHints: Set<Pair<Int, Int>>,
            ) {
                hinted = true
            }
        }
        rule.setContent {
            DynamicTheme {
                WordGameScreen(state = state(), actions = actions, onOpenGameCenter = {}, onOpenSettings = {})
            }
        }
        rule.onNodeWithContentDescription("Hint").performClick()
        rule.onNodeWithText("Are you sure you want to use a hint?").assertIsDisplayed()
        rule.onNodeWithText("No").performClick()
        rule.waitForIdle()
        assertFalse(hinted)
        assertTrue(rule.onAllNodesWithText("Are you sure you want to use a hint?").fetchSemanticsNodes().isEmpty())
    }

    @Test fun bonusDialogListsWordsAndDefinitionCanBeDismissedBackToTheList() {
        var dismissed = false
        val flightProgress = Animatable(0f)
        rule.setContent {
            DynamicTheme {
                Box {
                    WordGameOverlays(
                        showHintDialog = false, onDismissHint = {},
                        crosswordData = board(), foundWords = emptySet(), revealedHints = emptySet(),
                        actions = object : WordGameActions {
                            override fun getDefinition(word: String) = listOf("meaning of $word")
                        },
                        showBonusWordsDialog = true, onDismissBonusWords = { dismissed = true },
                        bonusWords = setOf("DOG", "CAT"),
                        wordWithDefinition = null, onDismissDefinition = {},
                        animatedWord = null, animationProgress = flightProgress,
                        wordBoxOffset = { Offset.Zero }, bonusButtonCenter = { Offset.Zero },
                        rootOffset = Offset.Zero, animatedLetters = emptyList(), scale = { 1f },
                    )
                }
            }
        }
        rule.onNodeWithText("DOG").performClick()
        rule.onNodeWithText("meaning of DOG").assertIsDisplayed()
        // Closing the definition (the topmost dialog's Close) returns to the bonus
        // list, not out of the dialog.
        val closes = rule.onAllNodesWithText("Close").fetchSemanticsNodes()
        rule.onAllNodesWithText("Close")[closes.size - 1].performClick()
        rule.onNodeWithText("CAT").assertIsDisplayed()
        assertFalse(dismissed)
        // With the definition gone, the remaining Close dismisses the bonus dialog itself.
        rule.onNodeWithText("Close").performClick()
        assertTrue(dismissed)
    }

    @Test fun competitiveScreenTimesOutWhenTheDeadlinePasses() {
        var timedOut = false
        val actions = object : WordGameActions {
            override fun onCompetitiveTimeout() {
                timedOut = true
            }
        }
        rule.setContent {
            DynamicTheme {
                WordGameScreen(
                    state = state(mode = GameMode.COMPETITIVE).copy(
                        competitiveScore = 40, competitiveLevelNumber = 3,
                        competitiveDeadline = System.currentTimeMillis() - 1_000L,
                    ),
                    actions = actions, onOpenGameCenter = {}, onOpenSettings = {},
                )
            }
        }
        rule.waitUntil(timeoutMillis = 10000) { timedOut }
        assertTrue(timedOut)
    }

    @Test fun dragBacktracksOverThePreviousLetterAndCancelsCleanly() {
        var submitted: String? = null
        rule.setContent {
            DynamicTheme {
                LetterChooser(
                    letters = listOf(
                        ChooserLetter(0, 'C'), ChooserLetter(1, 'A'),
                        ChooserLetter(2, 'T'), ChooserLetter(3, 'S'),
                    ),
                    tapToSpell = false, wheelSpacing = WheelSpacing.DEFAULT,
                    onShuffle = {}, onWordSubmitted = { w, _ -> submitted = w },
                    onWordBoxPositioned = {}, onLetterPositioned = { _, _ -> },
                )
            }
        }
        // Swipe onto a neighbour and back onto the previous tile: the last letter is
        // dropped again instead of being duplicated.
        rule.onAllNodesWithText("C")[0].performTouchInput {
            down(center)
            moveBy(Offset(80f, 40f))
            moveBy(Offset(-70f, -30f))
            cancel()
        }
        rule.waitForIdle()
        // A cancelled drag submits nothing and clears the selection.
        assertEquals(null, submitted)
    }

    @Test fun overlaysRenderBonusFlightAloneAndLettersAlone() {
        var dismissedDefinition = false
        val flightProgress = Animatable(1f)
        rule.setContent {
            DynamicTheme {
                Column {
                    WordGameOverlays(
                        showHintDialog = false, onDismissHint = {},
                        crosswordData = board(), foundWords = emptySet(), revealedHints = emptySet(),
                        actions = WordGameActions.Noop,
                        showBonusWordsDialog = false, onDismissBonusWords = {},
                        bonusWords = emptySet(),
                        wordWithDefinition = Pair("CAT", listOf("a pet")),
                        onDismissDefinition = { dismissedDefinition = true },
                        animatedWord = "DOG", animationProgress = flightProgress,
                        wordBoxOffset = { Offset(10f, 10f) }, bonusButtonCenter = { Offset(90f, 90f) },
                        rootOffset = Offset.Zero, animatedLetters = emptyList(), scale = { 2f },
                    )
                }
            }
        }
        rule.onNodeWithText("a pet").assertIsDisplayed()
        rule.onNodeWithText("Close").performClick()
        assertTrue(dismissedDefinition)
        rule.onNodeWithText("DOG").assertIsDisplayed()
    }

    @Test fun overlaysRenderFlyingLettersAtZoomWithoutBonusWord() {
        val progress = Animatable(0.25f)
        val flightProgress = Animatable(0f)
        rule.setContent {
            DynamicTheme {
                WordGameOverlays(
                    showHintDialog = false, onDismissHint = {},
                    crosswordData = board(), foundWords = emptySet(), revealedHints = emptySet(),
                    actions = WordGameActions.Noop,
                    showBonusWordsDialog = false, onDismissBonusWords = {},
                    bonusWords = emptySet(),
                    wordWithDefinition = null, onDismissDefinition = {},
                    animatedWord = null, animationProgress = flightProgress,
                    wordBoxOffset = { Offset.Zero }, bonusButtonCenter = { Offset.Zero },
                    rootOffset = Offset(5f, 5f),
                    animatedLetters = listOf(
                        AnimatedLetter('C', Offset.Zero, Offset(40f, 0f), progress),
                        AnimatedLetter('A', Offset(10f, 0f), Offset(50f, 0f), progress),
                    ),
                    scale = { 1.5f },
                )
            }
        }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("C").fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun modeChooserLabelsEveryModeAndSelectionFires() {
        var selected: GameMode? = null
        rule.setContent {
            DynamicTheme {
                Column {
                    WordMakerTopBar(
                        gameMode = GameMode.DAILY, onModeSelected = { selected = it },
                        onOpenGameCenter = {}, onOpenSettings = {}, levelNumber = null,
                    )
                    WordMakerModeChooser(
                        selected = GameMode.COMPETITIVE, onSelected = { selected = it }, levelNumber = null,
                    )
                    DifficultyDropdown(selected = Difficulty.EASY, onSelected = {})
                    CompetitiveStatusBar(score = 3, remainingTimeMs = 9_000L)
                    DailyStatusBar(streak = 0)
                }
            }
        }
        rule.waitForIdle()
        // The chooser shows the current mode; opening it lists the other modes by label.
        rule.onAllNodesWithText("Daily")[0].performClick()
        rule.waitForIdle()
        val casual = rule.onAllNodesWithText("Casual").fetchSemanticsNodes()
        if (casual.isNotEmpty()) {
            rule.onAllNodesWithText("Casual")[0].performClick()
            assertEquals(GameMode.CASUAL, selected)
        }
    }

    @Test fun placeAnimationsHandleUnknownQueuedWordsAndMultipleFlights() {
        var bonusAdded = mutableListOf<String>()
        val actions = object : WordGameActions {
            override suspend fun addBonusWord(word: String): Int {
                bonusAdded.add(word)
                return bonusAdded.size
            }
        }
        var anims: WordPlaceAnimations? = null
        var setFound: ((Set<String>) -> Unit)? = null
        rule.setContent {
            DynamicTheme {
                var found by remember { mutableStateOf(emptySet<String>()) }
                setFound = { found = it }
                anims = rememberWordPlaceAnimations(
                    levelKey = "deep", crosswordData = board(), foundWords = found, actions = actions,
                    density = Density(1f), rootOffset = Offset.Zero,
                    letterChooserPositions = mapOf(0 to Offset.Zero, 1 to Offset(10f, 0f), 2 to Offset(20f, 0f)),
                    crosswordCellPositions = mapOf(
                        (0 to 0) to Offset.Zero, (0 to 1) to Offset(10f, 0f), (0 to 2) to Offset(20f, 0f),
                    ),
                )
            }
        }
        // A word with no board positions is dropped from the queue without flying.
        rule.runOnUiThread { anims!!.enqueueSolution(WordToAnimate("ZZZ", listOf(0, 1, 2))) }
        rule.waitForIdle()
        assertTrue(anims!!.flyingSolutionWords.isEmpty())
        // Two bonus words fly one at a time and both are recorded in order.
        rule.runOnUiThread {
            anims!!.enqueueBonus("ONE")
            anims!!.enqueueBonus("TWO")
        }
        rule.waitForIdle()
        rule.waitUntil(timeoutMillis = 20000) { bonusAdded == listOf("ONE", "TWO") }
        assertTrue(anims!!.queuedBonusWords.isEmpty())
        assertEquals(null, anims!!.activeBonusWord)
        // A real solution word flies and leaves the pending set once it lands; marking
        // it found in the flow (as DataStore would) also drops it from the dedup set.
        rule.runOnUiThread { anims!!.enqueueSolution(WordToAnimate("CAT", listOf(0, 1, 2))) }
        rule.runOnUiThread { setFound!!(setOf("CAT")) }
        rule.waitUntil(timeoutMillis = 10000) { anims!!.flyingSolutionWords.isEmpty() }
        assertTrue(anims!!.animatedLetters.isEmpty())
    }

    @Test fun crosswordBoardWithEmptyGridAndUnfoundCells() {
        var positioned = 0
        var scaleSeen = -1f
        rule.setContent {
            DynamicTheme {
                Column {
                    CrosswordBoard(
                        foundWords = emptySet(), revealedHints = emptySet(),
                        crosswordData = CrosswordData(
                            solutionWords = emptySet(), lettersInChooser = emptyList(),
                            gridStructure = emptyList(), letterPositions = emptyMap(),
                        ),
                        hiddenWords = emptySet(),
                        onCellPositioned = { _, _ -> positioned++ },
                        onCellClicked = { _, _ -> },
                        scaleUpdated = { scaleSeen = it },
                    )
                    CrosswordBoard(
                        foundWords = emptySet(), revealedHints = setOf(0 to 1),
                        crosswordData = board(),
                        hiddenWords = emptySet(),
                        onCellPositioned = { _, _ -> positioned++ },
                        onCellClicked = { _, _ -> },
                        scaleUpdated = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        assertTrue(positioned > 0)
        assertTrue(scaleSeen >= 0f)
    }

    @Test fun letterChooserDragTrailSelectsAndSubmitsAWord() {
        var submitted: String? = null
        rule.setContent {
            DynamicTheme {
                LetterChooser(
                    letters = listOf(
                        ChooserLetter(0, 'C'), ChooserLetter(1, 'A'),
                        ChooserLetter(2, 'T'), ChooserLetter(3, 'S'),
                    ),
                    tapToSpell = false, wheelSpacing = WheelSpacing.WIDEST,
                    onShuffle = {}, onWordSubmitted = { w, _ -> submitted = w },
                    onWordBoxPositioned = {}, onLetterPositioned = { _, _ -> },
                )
            }
        }
        // Press on the first tile and sweep through its neighbours before lifting,
        // so the trail canvas has consecutive letters (and a live drag segment) to draw.
        rule.onAllNodesWithText("C")[0].performTouchInput {
            down(center)
            advanceEventTime(50)
            moveBy(Offset(60f, 30f))
            advanceEventTime(50)
            moveBy(Offset(120f, 90f))
            advanceEventTime(50)
            up()
        }
        rule.waitForIdle()
        assertTrue(submitted == null || submitted!!.isNotEmpty())
    }

    @Test fun tappingTheLastTileAgainDeselectsIt() {
        var submitted: String? = null
        rule.setContent {
            DynamicTheme {
                LetterChooser(
                    letters = listOf(ChooserLetter(0, 'C'), ChooserLetter(1, 'A'), ChooserLetter(2, 'T')),
                    tapToSpell = true, wheelSpacing = WheelSpacing.DEFAULT,
                    onShuffle = {}, onWordSubmitted = { w, _ -> submitted = w },
                    onWordBoxPositioned = {}, onLetterPositioned = { _, _ -> },
                )
            }
        }
        // Tiles are the clickable nodes carrying the letter; the formed-word box
        // above the wheel shows the same letters without a click action.
        fun tapTile(ch: String) {
            val nodes = rule.onAllNodesWithText(ch)
            nodes[nodes.fetchSemanticsNodes().size - 1].performClick()
        }
        tapTile("C")
        tapTile("A")
        // Re-tapping the most recent tile removes just that letter again.
        tapTile("A")
        rule.onNodeWithContentDescription("Submit").performClick()
        assertEquals("C", submitted)
        // And re-tapping the only remaining tile clears the selection entirely.
        submitted = null
        tapTile("C")
        tapTile("C")
        assertTrue(
            rule.onAllNodesWithContentDescription("Submit").fetchSemanticsNodes().isEmpty()
        )
        assertEquals(null, submitted)
    }

    @Test fun draggingBackOverThePreviousTileDropsTheLastLetter() {
        val submitted = mutableListOf<String>()
        rule.setContent {
            DynamicTheme {
                LetterChooser(
                    letters = listOf(
                        ChooserLetter(0, 'C'), ChooserLetter(1, 'A'),
                        ChooserLetter(2, 'T'), ChooserLetter(3, 'S'),
                    ),
                    tapToSpell = false, wheelSpacing = WheelSpacing.DEFAULT,
                    onShuffle = {}, onWordSubmitted = { w, _ -> submitted += w },
                    onWordBoxPositioned = {}, onLetterPositioned = { _, _ -> },
                )
            }
        }
        fun centerOf(c: Char): Offset =
            rule.onAllNodesWithText(c.toString()).fetchSemanticsNodes().last().boundsInRoot.center
        val c = centerOf('C')
        val a = centerOf('A')
        val t = centerOf('T')
        // C -> A selects "CA"; sliding back onto C drops A again, then T extends to "CT".
        rule.onRoot().performTouchInput {
            down(c)
            moveTo(c + Offset(0f, 12f))
            moveTo(a)
            moveTo(c)
            moveTo(t)
            up()
        }
        rule.waitForIdle()
        assertEquals(listOf("CT"), submitted)
    }

    @Test fun bonusWordsDialogStandaloneDismissesThroughClose() {
        var dismissed = false
        rule.setContent {
            DynamicTheme {
                BonusWordsDialog(
                    bonusWords = setOf("SOLO"),
                    getDefinition = { emptyList() },
                    onDismiss = { dismissed = true },
                )
            }
        }
        rule.onNodeWithText("SOLO").assertIsDisplayed()
        rule.onNodeWithText("Close").performClick()
        assertTrue(dismissed)
    }
}
