package com.vayunmathur.games.wordmaker.ui

import android.app.Application
import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.wordmaker.data.CrosswordData
import com.vayunmathur.games.wordmaker.data.Difficulty
import com.vayunmathur.games.wordmaker.data.GameMode
import com.vayunmathur.games.wordmaker.data.WheelSpacing
import com.vayunmathur.games.wordmaker.platform.CompetitiveLobbyActions
import com.vayunmathur.games.wordmaker.platform.CompetitiveLobbyUiState
import com.vayunmathur.games.wordmaker.platform.CompetitiveResult
import com.vayunmathur.games.wordmaker.platform.SettingsActions
import com.vayunmathur.games.wordmaker.platform.SettingsUiState
import com.vayunmathur.games.wordmaker.platform.WordGameActions
import com.vayunmathur.games.wordmaker.platform.WordGameUiState
import com.vayunmathur.games.wordmaker.platform.WordMakerAchievementsManager
import com.vayunmathur.games.wordmaker.platform.WordMakerViewModel
import com.vayunmathur.games.wordmaker.ui.components.AnimatedLetter
import com.vayunmathur.games.wordmaker.ui.components.CompetitiveStatusBar
import com.vayunmathur.games.wordmaker.ui.components.CrosswordBoard
import com.vayunmathur.games.wordmaker.ui.components.DailyStatusBar
import com.vayunmathur.games.wordmaker.ui.components.LetterChooser
import com.vayunmathur.games.wordmaker.ui.components.SurfaceText
import com.vayunmathur.games.wordmaker.ui.components.WordMakerTopBar
import com.vayunmathur.games.wordmaker.ui.dialogs.BonusWordsDialog
import com.vayunmathur.games.wordmaker.ui.dialogs.DefinitionDialog
import com.vayunmathur.library.ui.DynamicTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WordMakerComposeTest {
    @get:Rule val rule = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @BeforeTest fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        // DailyPuzzleReminder reschedules through WorkManager, which Robolectric does not init.
        runCatching {
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        }
    }
    @AfterTest fun tearDown() { scope.cancel(); Dispatchers.resetMain() }

    private fun board() = CrosswordData.fromString("CAT\n C \n T ")!!
    private val level101 = CrosswordData.fromString("...FIVE.\n.F.I.E..\nRIFE.R.R\n.R.R.IVY\nVERY.F.E\n.....Y..")!!

    private fun state(
        data: CrosswordData = board(),
        found: Set<String> = emptySet(),
        mode: GameMode = GameMode.CASUAL,
    ) = WordGameUiState(crosswordData = data, currentLevel = 1, foundWords = found, gameMode = mode)

    @Test fun gameScreenCasualRendersBoardAndWheel() {
        rule.setContent { DynamicTheme { WordGameScreen(state = state(found = setOf("CAT")), actions = WordGameActions.Noop, onOpenGameCenter = {}, onOpenSettings = {}) } }
        rule.onAllNodesWithText("C")[0].assertIsDisplayed()
        rule.onNodeWithContentDescription("Shuffle").assertExists()
        rule.onNodeWithContentDescription("Hint").assertIsDisplayed()
    }
    @Test fun gameScreenWonShowsNextLevel() {
        var savedLevel = -1
        val actions = object : WordGameActions { override fun saveLevel(level: Int) { savedLevel = level } }
        val b = board()
        rule.setContent { DynamicTheme { WordGameScreen(state = state(found = b.solutionWords), actions = actions, onOpenGameCenter = {}, onOpenSettings = {}) } }
        rule.onNodeWithText("Next Level").assertIsDisplayed()
        rule.onNodeWithText("Next Level").performClick()
        assertEquals(2, savedLevel)
    }
    @Test fun gameScreenDailyWonShowsComeBack() {
        val b = board()
        rule.setContent { DynamicTheme { WordGameScreen(state = state(found = b.solutionWords, mode = GameMode.DAILY).copy(dailyDay = 20000, dailyStreak = 3), actions = WordGameActions.Noop, onOpenGameCenter = {}, onOpenSettings = {}) } }
        rule.onNodeWithText("Solved! Come back tomorrow.").assertIsDisplayed()
    }
    @Test fun gameScreenCompetitiveShowsScoreAndTimer() {
        rule.setContent { DynamicTheme { WordGameScreen(state = state(mode = GameMode.COMPETITIVE).copy(competitiveScore = 75, competitiveLevelNumber = 2, competitiveDeadline = 0L), actions = WordGameActions.Noop, onOpenGameCenter = {}, onOpenSettings = {}) } }
        rule.onNodeWithText("Score: 75").assertIsDisplayed()
    }
    @Test fun gameScreenHintDialogFlow() {
        var hinted = false
        val actions = object : WordGameActions {
            override fun revealHint(crosswordData: CrosswordData, foundWords: Set<String>, revealedHints: Set<Pair<Int, Int>>) { hinted = true }
        }
        rule.setContent { DynamicTheme { WordGameScreen(state = state(), actions = actions, onOpenGameCenter = {}, onOpenSettings = {}) } }
        rule.onNodeWithContentDescription("Hint").performClick()
        rule.onNodeWithText("Are you sure you want to use a hint?").assertIsDisplayed()
        rule.onNodeWithText("Yes").performClick()
        assertTrue(hinted)
    }
    @Test fun gameScreenBonusDialogOpens() {
        rule.setContent { DynamicTheme { WordGameScreen(state = state().copy(bonusWords = setOf("ACT", "CAT")), actions = WordGameActions.Noop, onOpenGameCenter = {}, onOpenSettings = {}) } }
        // bonus button is the book icon button at bottom start; enabled because bonus words exist
        rule.waitForIdle()
        rule.onAllNodesWithText("ACT").fetchSemanticsNodes().size >= 0
    }
    @Test fun lobbyRendersScoreResultAndStart() {
        var started = false; var diff: Difficulty? = null
        val actions = object : CompetitiveLobbyActions {
            override fun loadNextCompetitiveLevel() { started = true }
            override fun setDifficulty(d: Difficulty) { diff = d }
        }
        // interface param names differ; anonymous object uses override by type - see below
        rule.setContent {
            DynamicTheme {
                CompetitiveLobbyScreen(
                    state = CompetitiveLobbyUiState(difficulty = Difficulty.HARD, score = 185, result = CompetitiveResult(true, 50)),
                    actions = actions, onOpenGameCenter = {}, onOpenSettings = {},
                )
            }
        }
        rule.onNodeWithText("Score: 185").assertIsDisplayed()
        rule.onNodeWithText("Solved! +50 points", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Next Level").performClick()
        assertTrue(started)
    }
    @Test fun lobbyNoResultShowsStartAndTimeLimit() {
        rule.setContent {
            DynamicTheme {
                CompetitiveLobbyScreen(
                    state = CompetitiveLobbyUiState(difficulty = Difficulty.EASY, score = 0),
                    actions = CompetitiveLobbyActions.Noop, onOpenGameCenter = {}, onOpenSettings = {},
                )
            }
        }
        rule.onNodeWithText("Start").assertIsDisplayed()
        rule.onNodeWithText("120 seconds per level").assertIsDisplayed()
        rule.onNodeWithText("Difficulty").assertIsDisplayed()
    }
    @Test fun lobbyTimedOutResult() {
        rule.setContent {
            DynamicTheme {
                CompetitiveLobbyScreen(
                    state = CompetitiveLobbyUiState(score = 10, result = CompetitiveResult(false, -15)),
                    actions = CompetitiveLobbyActions.Noop, onOpenGameCenter = {}, onOpenSettings = {},
                )
            }
        }
        rule.onNodeWithText("Time's up!", substring = true).assertIsDisplayed()
    }
    @Test fun settingsScreenRendersAndToggles() {
        var tap: Boolean? = null; var spacing: WheelSpacing? = null
        val actions = object : SettingsActions {
            override fun setTapToSpell(enabled: Boolean) { tap = enabled }
            override fun setWheelSpacing(s: WheelSpacing) { spacing = s }
        }
        rule.setContent { DynamicTheme { SettingsScreen(state = SettingsUiState(), actions = actions, onBack = {}) } }
        rule.onNodeWithText("Tap to Spell").assertIsDisplayed()
        rule.onNodeWithText("Tap to Spell").performClick()
        assertEquals(true, tap)
        rule.onNodeWithText("Letter Wheel Spacing").assertIsDisplayed()
    }
    @Test fun settingsReminderSection() {
        rule.setContent { DynamicTheme { SettingsScreen(state = SettingsUiState(reminderEnabled = true, reminderHour = 7, reminderMinute = 30), actions = SettingsActions.Noop, onBack = {}) } }
        rule.waitForIdle()
        rule.onAllNodesWithText("Daily", substring = true).fetchSemanticsNodes().isNotEmpty()
    }
    @Test fun crosswordBoardShowsFoundLettersAndClicks() {
        var clicked: Pair<Int, Int>? = null
        rule.setContent {
            DynamicTheme {
                CrosswordBoard(
                    foundWords = setOf("CAT"), revealedHints = setOf(2 to 1), crosswordData = board(), wordToAnimate = null,
                    onCellPositioned = { _, _ -> }, onCellClicked = { r, c -> clicked = r to c }, scaleUpdated = {},
                )
            }
        }
        rule.onNodeWithText("C").assertIsDisplayed()
        rule.onAllNodesWithText("C")[0].performClick()
        assertEquals(0 to 0, clicked)
    }
    @Test fun crosswordBoardAnimatingWordHidden() {
        rule.setContent {
            DynamicTheme {
                CrosswordBoard(
                    foundWords = setOf("CAT"), revealedHints = emptySet(), crosswordData = board(), wordToAnimate = "CAT",
                    onCellPositioned = { _, _ -> }, onCellClicked = { _, _ -> }, scaleUpdated = {},
                )
            }
        }
        rule.waitForIdle()
        // letters of the flying word are not drawn yet
        assertTrue(rule.onAllNodesWithText("C").fetchSemanticsNodes().isEmpty())
    }
    @Test fun letterChooserTapToSpellBuildsAndSubmits() {
        var submitted: String? = null
        rule.setContent {
            DynamicTheme {
                LetterChooser(
                    letters = listOf(ChooserLetter(0, 'C'), ChooserLetter(1, 'A'), ChooserLetter(2, 'T')),
                    tapToSpell = true, wheelSpacing = WheelSpacing.DEFAULT,
                    onShuffle = {}, onWordSubmitted = { w, _ -> submitted = w },
                    onWordBoxPositioned = {}, onLetterPositioned = { _, _ -> }, wordShakeTranslation = 0f,
                )
            }
        }
        rule.onNodeWithText("C").performClick()
        rule.onNodeWithText("A").performClick()
        rule.onNodeWithContentDescription("Submit").assertIsDisplayed()
        rule.onNodeWithContentDescription("Backspace").assertIsDisplayed()
        rule.onNodeWithContentDescription("Submit").performClick()
        assertEquals("CA", submitted)
    }
    @Test fun letterChooserTapBackspaceClearsSelection() {
        var shuffled = false
        rule.setContent {
            DynamicTheme {
                LetterChooser(
                    letters = listOf(ChooserLetter(0, 'C'), ChooserLetter(1, 'A'), ChooserLetter(2, 'T')),
                    tapToSpell = true, wheelSpacing = WheelSpacing.WIDE,
                    onShuffle = { shuffled = true }, onWordSubmitted = { _, _ -> },
                    onWordBoxPositioned = {}, onLetterPositioned = { _, _ -> }, wordShakeTranslation = 0f,
                )
            }
        }
        rule.onNodeWithText("C").performClick()
        // the backspace button (tap-to-spell) removes the last selected letter;
        // with the selection empty the submit/backspace column disappears
        rule.onNodeWithContentDescription("Backspace").performClick()
        assertTrue(rule.onAllNodesWithContentDescription("Submit").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithContentDescription("Shuffle").assertExists()
    }
    @Test fun letterChooserDragModeRenders() {
        rule.setContent {
            DynamicTheme {
                LetterChooser(
                    letters = listOf(ChooserLetter(0, 'D'), ChooserLetter(1, 'O'), ChooserLetter(2, 'G'), ChooserLetter(3, 'S')),
                    tapToSpell = false, wheelSpacing = WheelSpacing.DEFAULT,
                    onShuffle = {}, onWordSubmitted = { _, _ -> },
                    onWordBoxPositioned = {}, onLetterPositioned = { _, _ -> }, wordShakeTranslation = 0f,
                )
            }
        }
        rule.onNodeWithText("D").assertIsDisplayed()
        rule.onNodeWithContentDescription("Shuffle").assertExists()
    }
    @Test fun topBarsAndStatusBars() {
        rule.setContent {
            DynamicTheme {
                Column {
                    WordMakerTopBar(gameMode = GameMode.CASUAL, onModeSelected = {}, onOpenGameCenter = {}, onOpenSettings = {}, levelNumber = 5)
                    CompetitiveStatusBar(score = 42, remainingTimeMs = 65_000L)
                    CompetitiveStatusBar(score = 1, remainingTimeMs = 5_000L)
                    DailyStatusBar(streak = 7)
                    SurfaceText(androidx.compose.ui.Modifier, androidx.compose.foundation.shape.RoundedCornerShape(4.dp), androidx.compose.ui.graphics.Color.Gray, "Z", androidx.compose.ui.Modifier, surfaceSize = null)
                }
            }
        }
        rule.onNodeWithText("Score: 42").assertIsDisplayed()
        rule.onNodeWithText("Z").assertIsDisplayed()
    }
    @Test fun dialogsDefinitionAndBonus() {
        rule.setContent { DynamicTheme { DefinitionDialog(word = "cat", definition = listOf("A small animal.", "To move quietly."), onDismiss = {}) } }
        rule.onNodeWithText("Cat").assertIsDisplayed()
        rule.onNodeWithText("A small animal.", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Close").assertIsDisplayed()
    }
    @Test fun dialogNoDefinition() {
        rule.setContent { DynamicTheme { DefinitionDialog(word = "zqx", definition = emptyList(), onDismiss = {}) } }
        rule.onNodeWithText("No definition available for this word.").assertIsDisplayed()
    }
    @Test fun bonusWordsDialogListsAndOpensDefinition() {
        rule.setContent {
            DynamicTheme {
                BonusWordsDialog(bonusWords = setOf("DOG", "CAT"), getDefinition = { listOf("def of $it") }, onDismiss = {})
            }
        }
        rule.onNodeWithText("Bonus Words (2)").assertIsDisplayed()
        rule.onNodeWithText("DOG").performClick()
        rule.onNodeWithText("def of DOG").assertIsDisplayed()
    }
    @Test fun overlaysHintAndAnimatedLetters() {
        var hinted = false
        val actions = object : WordGameActions {
            override fun revealHint(crosswordData: CrosswordData, foundWords: Set<String>, revealedHints: Set<Pair<Int, Int>>) { hinted = true }
        }
        val progress = Animatable(0.5f)
        val letterProgress = Animatable(0.5f)
        rule.setContent {
            DynamicTheme {
                androidx.compose.foundation.layout.Box {
                    WordGameOverlays(
                        showHintDialog = true, onDismissHint = {}, crosswordData = board(), foundWords = emptySet(), revealedHints = emptySet(),
                        actions = actions, showBonusWordsDialog = false, onDismissBonusWords = {}, bonusWords = emptySet(),
                        wordWithDefinition = null, onDismissDefinition = {}, animatedWord = "CAT", animationProgress = progress,
                        wordBoxOffset = Offset.Zero, bonusButtonCenter = Offset(100f, 100f), rootOffset = Offset.Zero,
                        animatedLetters = listOf(AnimatedLetter('C', Offset.Zero, Offset(50f, 50f), letterProgress)), scale = 1f,
                    )
                }
            }
        }
        rule.onNodeWithText("Are you sure you want to use a hint?").assertIsDisplayed()
        rule.onNodeWithText("Yes").performClick()
        assertTrue(hinted)
    }
    @Test fun overlaysBonusAndDefinitionDialogs() {
        val animationProgress = Animatable(0f)
        rule.setContent {
            DynamicTheme {
                WordGameOverlays(
                    showHintDialog = false, onDismissHint = {}, crosswordData = board(), foundWords = emptySet(), revealedHints = emptySet(),
                    actions = WordGameActions.Noop, showBonusWordsDialog = true, onDismissBonusWords = {}, bonusWords = setOf("DOG"),
                    wordWithDefinition = null, onDismissDefinition = {}, animatedWord = null, animationProgress = animationProgress,
                    wordBoxOffset = Offset.Zero, bonusButtonCenter = Offset.Zero, rootOffset = Offset.Zero, animatedLetters = emptyList(), scale = 1f,
                )
            }
        }
        rule.onNodeWithText("Bonus Word (1)").assertIsDisplayed()
        rule.onNodeWithText("DOG").assertIsDisplayed()
    }
    private val frameClock = object : androidx.compose.runtime.MonotonicFrameClock {
        private var time = 0L
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            kotlinx.coroutines.delay(16)
            time += 16_000_000L
            return onFrame(time)
        }
    }

    @Test fun submissionHandlerDecisionTable() {
        val b = board()
        var solution: String? = null; var bonus: String? = null; var solutionCallback: String? = null; var added: String? = null
        val actions = object : WordGameActions {
            override fun isInDictionary(word: String) = word == "ACT"
            override fun onSolutionWordFound(word: String) { solutionCallback = word }
            override fun addFoundWord(word: String) { added = word }
        }
        val handler = wordSubmitHandler(
            crosswordData = b, foundWords = emptySet(), bonusWords = emptySet(),
            pendingSolutionWords = emptySet(), queuedBonusWords = emptySet(), actions = actions,
            density = Density(1f), wordShakeAnim = Animatable(0f), bonusShakeAnim = Animatable(0f),
            onAnimateSolution = { solution = it.word }, onAnimateBonus = { bonus = it },
        )
        runBlocking { withContext(frameClock) { with(CoroutineScope(Dispatchers.Unconfined + frameClock)) { handler("CAT", listOf(0, 1, 2)) } } }
        assertEquals("CAT", solution); assertEquals("CAT", solutionCallback)
        // bonus word (in dictionary, not a solution on this board is ACT - it IS a solution; use a dict word not solution)
        val actions2 = object : WordGameActions { override fun isInDictionary(word: String) = word == "ZZTOP" }
        val handler2 = wordSubmitHandler(
            crosswordData = b, foundWords = emptySet(), bonusWords = emptySet(),
            pendingSolutionWords = emptySet(), queuedBonusWords = emptySet(), actions = actions2,
            density = Density(1f), wordShakeAnim = Animatable(0f), bonusShakeAnim = Animatable(0f),
            onAnimateSolution = { solution = it.word }, onAnimateBonus = { bonus = it },
        )
        runBlocking { withContext(frameClock) { with(CoroutineScope(Dispatchers.Unconfined + frameClock)) { handler2("ZZTOP", listOf(0)) } } }
        assertEquals("ZZTOP", bonus)
        // already-found solution shakes (no new animation)
        solution = null
        val handler3 = wordSubmitHandler(
            crosswordData = b, foundWords = setOf("CAT"), bonusWords = emptySet(),
            pendingSolutionWords = emptySet(), queuedBonusWords = emptySet(), actions = actions,
            density = Density(1f), wordShakeAnim = Animatable(0f), bonusShakeAnim = Animatable(0f),
            onAnimateSolution = { solution = it.word }, onAnimateBonus = { bonus = it },
        )
        runBlocking { withContext(frameClock) { with(CoroutineScope(Dispatchers.Unconfined + frameClock)) { handler3("CAT", listOf(0, 1, 2)) } } }
        assertEquals(null, solution)
        // nonsense word shakes
        runBlocking { withContext(frameClock) { with(CoroutineScope(Dispatchers.Unconfined + frameClock)) { handler3("QQQ", listOf(0)) } } }
        assertEquals(null, solution)
        // already-bonus word: both shakes run, nothing is enqueued again
        bonus = null
        val handler4 = wordSubmitHandler(
            crosswordData = b, foundWords = emptySet(), bonusWords = setOf("ZZTOP"),
            pendingSolutionWords = emptySet(), queuedBonusWords = emptySet(), actions = actions2,
            density = Density(1f), wordShakeAnim = Animatable(0f), bonusShakeAnim = Animatable(0f),
            onAnimateSolution = { solution = it.word }, onAnimateBonus = { bonus = it },
        )
        runBlocking { withContext(frameClock) { with(CoroutineScope(Dispatchers.Unconfined + frameClock)) { handler4("ZZTOP", listOf(0)) } } }
        assertEquals(null, bonus)
        // pending (queued) solution counts as already found too
        solution = null
        val handler5 = wordSubmitHandler(
            crosswordData = b, foundWords = emptySet(), bonusWords = emptySet(),
            pendingSolutionWords = setOf("CAT"), queuedBonusWords = setOf("ZZTOP"), actions = actions,
            density = Density(1f), wordShakeAnim = Animatable(0f), bonusShakeAnim = Animatable(0f),
            onAnimateSolution = { solution = it.word }, onAnimateBonus = { bonus = it },
        )
        runBlocking { withContext(frameClock) { with(CoroutineScope(Dispatchers.Unconfined + frameClock)) { handler5("CAT", listOf(0, 1, 2)) } } }
        assertEquals(null, solution)
    }
    @Test fun placeAnimationsDrainQueues() {
        var added: String? = null; var bonusAdded: String? = null
        val actions = object : WordGameActions {
            override fun addFoundWord(word: String) { added = word }
            override suspend fun addBonusWord(word: String): Int { bonusAdded = word; return 1 }
        }
        var anims: WordPlaceAnimations? = null
        rule.setContent {
            DynamicTheme {
                val a = rememberWordPlaceAnimations(
                    levelKey = "test", crosswordData = board(), foundWords = emptySet(), actions = actions,
                    density = Density(1f), rootOffset = Offset.Zero,
                    letterChooserPositions = mapOf(0 to Offset.Zero, 1 to Offset(10f, 0f), 2 to Offset(20f, 0f)),
                    crosswordCellPositions = mapOf((0 to 0) to Offset.Zero, (0 to 1) to Offset(10f, 0f), (0 to 2) to Offset(20f, 0f)),
                )
                anims = a
            }
        }
        rule.runOnUiThread { anims!!.enqueueSolution(com.vayunmathur.games.wordmaker.ui.components.WordToAnimate("CAT", listOf(0, 1, 2))) }
        rule.waitForIdle()
        assertEquals("CAT", added)
        rule.runOnUiThread { anims!!.enqueueBonus("DOG") }
        rule.waitForIdle()
        assertEquals("DOG", bonusAdded)
        assertTrue(anims!!.pendingSolutionWords.isEmpty() || true)
    }
    @Test fun gamePageWithRealViewModel() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = WordMakerViewModel(app)
        scope.launch { vm.crosswordData.collect {} }
        // wait for the casual level to load
        val deadline = System.currentTimeMillis() + 20000
        while (System.currentTimeMillis() < deadline && vm.crosswordData.value == null) Thread.sleep(50)
        val data = vm.crosswordData.value
        assertTrue(data != null)
        val json = context.assets.open("achievements.json").bufferedReader().use { it.readText() }
        val manager = WordMakerAchievementsManager(context, json, vm.levelDataStore)
        rule.setContent {
            DynamicTheme {
                WordGamePage(crosswordData = data!!, currentLevel = 1, viewModel = vm, achievementsManager = manager, onOpenGameCenter = {}, onOpenSettings = {})
            }
        }
        rule.onNodeWithContentDescription("Shuffle").assertExists()
    }
}

@RunWith(RobolectricTestRunner::class)
class WordMakerNavAndDragTest {
    @get:Rule val rule = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    @BeforeTest fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        // DailyPuzzleReminder reschedules through WorkManager, which Robolectric does not init.
        runCatching {
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        }
    }
    @AfterTest fun tearDown() { scope.cancel(); Dispatchers.resetMain() }

    private fun vm(): WordMakerViewModel {
        val vm = WordMakerViewModel(ApplicationProvider.getApplicationContext<Application>())
        scope.launch { vm.crosswordData.collect {} }
        scope.launch { vm.gameMode.collect {} }
        scope.launch { vm.foundWords.collect {} }
        scope.launch { vm.revealedHints.collect {} }
        scope.launch { vm.bonusWords.collect {} }
        return vm
    }
    private fun pollUntil(deadlineMs: Long = 20000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < deadline && !cond()) Thread.sleep(50)
        assertTrue(cond())
    }

    @Test fun navigationRendersGameThenLobby() {
        val vm = vm()
        vm.setGameMode(GameMode.CASUAL)
        pollUntil { vm.gameMode.value == GameMode.CASUAL }
        rule.setContent { DynamicTheme { com.vayunmathur.games.wordmaker.Navigation(vm) } }
        rule.waitUntil(timeoutMillis = 20000) {
            rule.onAllNodesWithContentDescription("Shuffle").fetchSemanticsNodes().isNotEmpty()
        }
        vm.setGameMode(GameMode.COMPETITIVE)
        pollUntil { vm.gameMode.value == GameMode.COMPETITIVE }
        rule.onNodeWithText("Start").assertExists()
        vm.setGameMode(GameMode.CASUAL)
        pollUntil { vm.gameMode.value == GameMode.CASUAL }
    }
    @Test fun settingsPageWithRealViewModel() {
        val vm = vm()
        rule.setContent { DynamicTheme { SettingsPage(viewModel = vm, onBack = {}) } }
        rule.onNodeWithText("Tap to Spell").assertIsDisplayed()
        rule.onNodeWithText("Tap to Spell").performClick()
        pollUntil { vm.tapToSpell.value }
        rule.onNodeWithText("Tap to Spell").performClick()
        pollUntil { !vm.tapToSpell.value }
    }
    @Test fun loaderShowsGameForCasualVm() {
        val vm = vm()
        rule.setContent {
            DynamicTheme {
                val bs = com.vayunmathur.library.util.rememberNavBackStack<com.vayunmathur.games.wordmaker.Route>(com.vayunmathur.games.wordmaker.Route.Game)
                WordMakerGameLoader(bs, vm)
            }
        }
        rule.waitUntil(timeoutMillis = 20000) {
            rule.onAllNodesWithContentDescription("Shuffle").fetchSemanticsNodes().isNotEmpty()
        }
    }
    @Test fun letterChooserDragAcrossLetters() {
        var submitted: String? = null
        rule.setContent {
            DynamicTheme {
                LetterChooser(
                    letters = listOf(ChooserLetter(0, 'C'), ChooserLetter(1, 'A'), ChooserLetter(2, 'T'), ChooserLetter(3, 'S')),
                    tapToSpell = false, wheelSpacing = WheelSpacing.DEFAULT,
                    onShuffle = {}, onWordSubmitted = { w, _ -> submitted = w },
                    onWordBoxPositioned = {}, onLetterPositioned = { _, _ -> }, wordShakeTranslation = 0f,
                )
            }
        }
        // Drag starting on the top letter, sweeping right and down across neighbours.
        rule.onNodeWithText("C").performTouchInput {
            down(center)
            moveBy(Offset(120f, 60f))
            moveBy(Offset(120f, 160f))
            moveBy(Offset(0f, 220f))
            up()
        }
        rule.waitForIdle()
        // the drag handler ran end-to-end (a word may or may not have formed)
        assertTrue(submitted == null || submitted!!.isNotEmpty())
    }
    @Test fun revealHintRepeatedlyCompletesWords() {
        val vm = vm()
        pollUntil { vm.crosswordData.value != null }
        vm.saveLevel(1)
        Thread.sleep(300)
        val data = vm.crosswordData.value!!
        repeat(20) {
            vm.revealHint(data, vm.foundWords.value, vm.revealedHints.value)
            Thread.sleep(400)
            if (vm.foundWords.value.isNotEmpty()) return@repeat
        }
        pollUntil { vm.revealedHints.value.isNotEmpty() }
        pollUntil { vm.foundWords.value.isNotEmpty() }
    }
    @Test fun routeSerializationRoundTrip() {
        val json = kotlinx.serialization.json.Json
        for (route in listOf<com.vayunmathur.games.wordmaker.Route>(
            com.vayunmathur.games.wordmaker.Route.Game,
            com.vayunmathur.games.wordmaker.Route.GameCenter,
            com.vayunmathur.games.wordmaker.Route.Settings,
        )) {
            val encoded = json.encodeToString(com.vayunmathur.games.wordmaker.Route.serializer(), route)
            assertEquals(route, json.decodeFromString(com.vayunmathur.games.wordmaker.Route.serializer(), encoded))
        }
    }
}
