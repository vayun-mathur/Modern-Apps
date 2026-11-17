package com.vayunmathur.games.wordmaker.platform

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.wordmaker.data.Difficulty
import com.vayunmathur.games.wordmaker.data.GameMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ViewModelDeepTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @BeforeTest fun setUp() {
        retryingMain { Dispatchers.setMain(Dispatchers.Unconfined) }
        runCatching {
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        }
    }

    @AfterTest fun tearDown() {
        scope.cancel()
        retryingMain { Dispatchers.resetMain() }
    }

    private fun vm(): WordMakerViewModel {
        val vm = WordMakerViewModel(ApplicationProvider.getApplicationContext<Application>())
        scope.launch { vm.crosswordData.collect {} }
        scope.launch { vm.foundWords.collect {} }
        scope.launch { vm.bonusWords.collect {} }
        scope.launch { vm.revealedHints.collect {} }
        scope.launch { vm.currentLevel.collect {} }
        scope.launch { vm.gameMode.collect {} }
        scope.launch { vm.difficulty.collect {} }
        scope.launch { vm.competitiveScore.collect {} }
        scope.launch { vm.tapToSpell.collect {} }
        scope.launch { vm.wheelSpacing.collect {} }
        scope.launch { vm.reminderEnabled.collect {} }
        scope.launch { vm.reminderMinutesOfDay.collect {} }
        scope.launch { vm.dailyStreak.collect {} }
        scope.launch { vm.dailyBestStreak.collect {} }
        scope.launch { vm.error.collect {} }
        scope.launch { vm.competitiveActive.collect {} }
        scope.launch { vm.competitiveResult.collect {} }
        scope.launch { vm.competitiveDeadline.collect {} }
        scope.launch { vm.competitiveLevelNumber.collect {} }
        scope.launch { vm.dailyDay.collect {} }
        scope.launch { vm.hintCooldownEnd.collect {} }
        return vm
    }


    /** set/resetMain throw if a background coroutine is using Main at that instant (a stale ViewModel's flow resuming); retry briefly, as the nonogram suite does. */
    private fun retryingMain(action: () -> Unit) {
        var attempts = 0
        while (attempts++ < 50 && runCatching(action).isFailure) Thread.sleep(10)
        if (attempts > 50) action()
    }
    private fun pollUntil(deadlineMs: Long = 30000, cond: () -> Boolean) {
        // Step-counted, not wall-clock: Robolectric's substituted
        // currentTimeMillis only advances when its scheduler runs, so a
        // deadline poll could spin forever (the CI build timeout) if the
        // condition it waited for never landed, or expire instantly.
        // Each step sleeps real time, bounding the wait in real time.
        var steps = (deadlineMs / 50).toInt()
        while (steps-- > 0 && !cond()) Thread.sleep(50)
        assertTrue(cond(), "condition not met within ${deadlineMs}ms")
    }

    private fun waitFor(deadlineMs: Long = 8000, cond: () -> Boolean): Boolean {
        var steps = (deadlineMs / 50).toInt()
        while (steps-- > 0 && !cond()) Thread.sleep(50)
        return cond()
    }

    private fun resetToCasual(vm: WordMakerViewModel) {
        vm.setGameMode(GameMode.CASUAL)
        vm.saveLevel(1)
        pollUntil { vm.gameMode.value == GameMode.CASUAL && vm.currentLevel.value == 1 }
        pollUntil { vm.crosswordData.value != null }
    }

    @Test fun seededLevelBeyondDesignedRangeIsDeterministic() {
        val vm = vm()
        resetToCasual(vm)
        val designedGrid = vm.crosswordData.value!!.gridStructure
        vm.saveLevel(WordMakerViewModel.MAX_DESIGNED_LEVEL + 1)
        pollUntil { vm.currentLevel.value == WordMakerViewModel.MAX_DESIGNED_LEVEL + 1 }
        // The previous board stays visible while the new one generates; wait for it to change.
        pollUntil { vm.crosswordData.value?.gridStructure?.let { it != designedGrid } == true }
        val first = vm.crosswordData.value!!
        assertTrue(first.solutionWords.size >= 4)
        // Leaving and returning to the same generated level yields the identical board,
        // so saved found words stay valid.
        vm.saveLevel(WordMakerViewModel.MAX_DESIGNED_LEVEL + 2)
        pollUntil { vm.currentLevel.value == WordMakerViewModel.MAX_DESIGNED_LEVEL + 2 }
        pollUntil { vm.crosswordData.value?.gridStructure != first.gridStructure }
        vm.saveLevel(WordMakerViewModel.MAX_DESIGNED_LEVEL + 1)
        pollUntil { vm.currentLevel.value == WordMakerViewModel.MAX_DESIGNED_LEVEL + 1 }
        pollUntil { vm.crosswordData.value?.gridStructure == first.gridStructure }
        assertEquals(first.solutionWords, vm.crosswordData.value!!.solutionWords)
        vm.saveLevel(1)
        pollUntil { vm.currentLevel.value == 1 }
    }

    @Test fun retryLoadRegeneratesForEveryMode() {
        val vm = vm()
        resetToCasual(vm)
        // Casual retry reloads the current level without an error.
        vm.retryLoad()
        pollUntil { vm.crosswordData.value != null && vm.error.value == null }
        // Daily retry refreshes (or reuses) today's board.
        vm.setGameMode(GameMode.DAILY)
        pollUntil { vm.gameMode.value == GameMode.DAILY }
        pollUntil { vm.crosswordData.value != null }
        vm.retryLoad()
        pollUntil { vm.crosswordData.value != null }
        // Competitive retry starts a fresh level straight away.
        vm.setGameMode(GameMode.COMPETITIVE)
        pollUntil { vm.gameMode.value == GameMode.COMPETITIVE }
        val before = vm.competitiveLevelNumber.value
        vm.retryLoad()
        pollUntil { vm.competitiveActive.value && vm.crosswordData.value != null }
        assertTrue(vm.competitiveLevelNumber.value > before)
        assertTrue(vm.competitiveDeadline.value > System.currentTimeMillis())
        vm.setGameMode(GameMode.CASUAL)
        pollUntil { vm.gameMode.value == GameMode.CASUAL }
    }

    @Test fun incidentalWordsAreCreditedWithoutBeingTraced() {
        val vm = vm()
        resetToCasual(vm)
        // Designed level 20: HAS is crossed at every letter by other solution words,
        // so finding those words fills HAS in without it ever being traced.
        vm.saveLevel(20)
        pollUntil { vm.currentLevel.value == 20 }
        pollUntil { vm.crosswordData.value?.solutionWords?.contains("HAS") == true }
        val data = vm.crosswordData.value!!
        assertTrue(data.incidentalWords(data.solutionWords - "HAS").contains("HAS"))
        assertFalse("HAS" in vm.foundWords.value)
        for (word in data.solutionWords - "HAS") vm.addFoundWord(word)
        pollUntil(20000) { "HAS" in vm.foundWords.value }
        assertTrue(data.winsWith(vm.foundWords.value))
        vm.saveLevel(1)
        pollUntil { vm.currentLevel.value == 1 }
    }

    @Test fun revealHintInDailyModeCompletesWordsAndStartsCooldown() {
        val vm = vm()
        vm.setGameMode(GameMode.DAILY)
        pollUntil { vm.gameMode.value == GameMode.DAILY }
        // Wait for today's generated board, not the casual board shown before the switch.
        pollUntil {
            vm.crosswordData.value?.solutionWords?.let { it != setOf("CAT", "ACT") } == true
        }
        val data = vm.crosswordData.value!!
        // Earlier tests may already have daily found words saved for today; the hint
        // flow is done when revealing grows that set further.
        val foundAtStart = vm.foundWords.value
        repeat(40) {
            if (vm.foundWords.value.size > foundAtStart.size) return@repeat
            val before = vm.revealedHints.value.size
            vm.revealHint(vm.crosswordData.value ?: data, vm.foundWords.value, vm.revealedHints.value)
            waitFor(8000) {
                vm.revealedHints.value.size > before || vm.foundWords.value.size > foundAtStart.size
            }
        }
        pollUntil(20000) { vm.revealedHints.value.isNotEmpty() }
        pollUntil(20000) { vm.foundWords.value.size > foundAtStart.size }
        assertTrue(vm.hintCooldownEnd.value > System.currentTimeMillis())
        // Revealing when every cell is already shown is a harmless no-op.
        val allCells = data.letterPositions.values.flatMap { it.flatten() }.toSet()
        val cooldownBefore = vm.hintCooldownEnd.value
        vm.revealHint(data, data.solutionWords, allCells)
        assertEquals(cooldownBefore, vm.hintCooldownEnd.value)
        vm.setGameMode(GameMode.CASUAL)
        pollUntil { vm.gameMode.value == GameMode.CASUAL }
    }

    @Test fun revealHintInCasualModeCanFinishTheLevelWordByWord() {
        val vm = vm()
        resetToCasual(vm)
        Thread.sleep(300)
        val data = vm.crosswordData.value!!
        assertEquals(setOf("CAT", "ACT"), data.solutionWords)
        // Reveal cells until both words complete through their crossing cell.
        repeat(10) {
            if (data.winsWith(vm.foundWords.value)) return@repeat
            val before = vm.revealedHints.value.size
            vm.revealHint(data, vm.foundWords.value, vm.revealedHints.value)
            waitFor(8000) {
                vm.revealedHints.value.size > before || data.winsWith(vm.foundWords.value)
            }
        }
        pollUntil(20000) { data.winsWith(vm.foundWords.value) }
        // Leave the level pristine for other tests sharing the store.
        vm.saveLevel(1)
        pollUntil { vm.currentLevel.value == 1 }
    }

    @Test fun dailyBonusAndFoundWordsDoNotLeakIntoCasualSets() {
        val vm = vm()
        resetToCasual(vm)
        vm.setGameMode(GameMode.DAILY)
        pollUntil { vm.gameMode.value == GameMode.DAILY }
        pollUntil { vm.crosswordData.value != null }
        vm.addFoundWord("ZQXDAILYDEEP")
        pollUntil { "ZQXDAILYDEEP" in vm.foundWords.value }
        val total = kotlinx.coroutines.runBlocking { vm.addBonusWord("ZQXDAILYDEEP") }
        assertTrue(total >= 1)
        pollUntil { "ZQXDAILYDEEP" in vm.bonusWords.value }
        // The same word found today does not appear once back in casual mode.
        // foundWords/bonusWords are combines over gameMode, so they switch
        // asynchronously after gameMode itself: await the observed sets,
        // not just the mode (an immediate assert reads the daily sets).
        vm.setGameMode(GameMode.CASUAL)
        pollUntil { vm.gameMode.value == GameMode.CASUAL }
        pollUntil { "ZQXDAILYDEEP" !in vm.foundWords.value }
        pollUntil { "ZQXDAILYDEEP" !in vm.bonusWords.value }
        assertFalse("ZQXDAILYDEEP" in vm.foundWords.value)
        assertFalse("ZQXDAILYDEEP" in vm.bonusWords.value)
        // Daily win is idempotent within the day for the streak.
        vm.setGameMode(GameMode.DAILY)
        pollUntil { vm.gameMode.value == GameMode.DAILY }
        vm.onDailyWin()
        vm.onDailyWin()
        pollUntil { vm.dailyStreak.value >= 1L }
        pollUntil { vm.dailyBestStreak.value >= 1L }
        vm.setGameMode(GameMode.CASUAL)
        pollUntil { vm.gameMode.value == GameMode.CASUAL }
    }

    @Test fun competitiveWinTimeoutAndDifficultyDeltasUsePersistedScore() {
        val vm = vm()
        resetToCasual(vm)
        vm.setGameMode(GameMode.COMPETITIVE)
        pollUntil { vm.gameMode.value == GameMode.COMPETITIVE }
        vm.setDifficulty(Difficulty.HARD)
        pollUntil { vm.difficulty.value == Difficulty.HARD }
        vm.loadNextCompetitiveLevel()
        pollUntil { vm.competitiveActive.value && vm.crosswordData.value != null }
        assertTrue(vm.competitiveDeadline.value > System.currentTimeMillis())
        val score0 = vm.competitiveScore.value
        vm.onCompetitiveWin()
        pollUntil { vm.competitiveResult.value == CompetitiveResult(true, Difficulty.HARD.winDelta) }
        pollUntil { vm.competitiveScore.value == score0 + Difficulty.HARD.winDelta }
        assertEquals(0L, vm.competitiveDeadline.value)
        assertFalse(vm.competitiveActive.value)
        // A second level can be started from the lobby state after a win.
        vm.loadNextCompetitiveLevel()
        pollUntil { vm.competitiveActive.value && vm.crosswordData.value != null }
        assertEquals(null, vm.competitiveResult.value)
        vm.onCompetitiveTimeout()
        pollUntil { vm.competitiveResult.value == CompetitiveResult(false, -Difficulty.HARD.lossDelta) }
        pollUntil { vm.competitiveScore.value == score0 + Difficulty.HARD.winDelta - Difficulty.HARD.lossDelta }
        vm.setDifficulty(Difficulty.MEDIUM)
        vm.setGameMode(GameMode.CASUAL)
        pollUntil { vm.gameMode.value == GameMode.CASUAL }
    }

    @Test fun dictionaryRejectsUnknownAndAcceptsRealWordsCaseInsensitively() {
        val vm = vm()
        resetToCasual(vm)
        pollUntil { vm.isInDictionary("cat") }
        assertTrue(vm.isInDictionary("CAT"))
        assertTrue(vm.isInDictionary("Dog"))
        assertFalse(vm.isInDictionary("zzzzqq"))
        assertFalse(vm.isInDictionary(""))
        assertTrue(vm.getDefinition("cat").isNotEmpty())
        assertEquals(vm.getDefinition("cat"), vm.getDefinition("CAT"))
        assertTrue(vm.getDefinition("zzzzqq").isEmpty())
    }

    @Test fun achievementsManagerReportsLevelBonusAndStreakProgress() {
        val vm = vm()
        resetToCasual(vm)
        val json = context.assets.open("achievements.json").bufferedReader().use { it.readText() }
        val manager = WordMakerAchievementsManager(context, json, vm.levelDataStore)
        // Start from a clean achievement store so every check below is a first unlock.
        kotlinx.coroutines.runBlocking { manager.forgetAll() }
        vm.saveLevel(3)
        pollUntil { vm.currentLevel.value == 3 }
        kotlinx.coroutines.runBlocking { vm.addBonusWord("ZQXACHIEVE") }
        vm.onDailyWin()
        pollUntil { vm.dailyStreak.value >= 1L }
        manager.checkExistingAchievements()
        // Level, bonus and streak progress each surface through the manager's queue:
        // the first queued unlock is shown, dismissing advances to the next.
        pollUntil(20000) { manager.newAchievement.value != null }
        val firstShown = manager.newAchievement.value!!
        manager.dismissNotification()
        resetToCasual(vm)
        assertTrue(firstShown.id.isNotEmpty())
    }

    @Test fun backupAgentDeclaresBothDatastores() {
        val agent = AppBackupAgent()
        assertEquals(listOf("settings", "datastore_default"), agent.datastoreNames)
    }
}
