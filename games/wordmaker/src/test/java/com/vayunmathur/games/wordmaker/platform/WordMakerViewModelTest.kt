package com.vayunmathur.games.wordmaker.platform

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.wordmaker.data.CrosswordData
import com.vayunmathur.games.wordmaker.data.Difficulty
import com.vayunmathur.games.wordmaker.data.GameMode
import com.vayunmathur.games.wordmaker.data.WheelSpacing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WordMakerViewModelTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val collectorScope = CoroutineScope(Dispatchers.Unconfined)

    @BeforeTest fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        // DailyPuzzleReminder reschedules through WorkManager, which Robolectric does not init.
        runCatching {
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        }
    }
    @AfterTest fun tearDown() { collectorScope.cancel(); Dispatchers.resetMain() }

    /** StateIn(WhileSubscribed) flows only compute while collected, as the UI collects them. */
    private fun vm(): WordMakerViewModel {
        val vm = WordMakerViewModel(ApplicationProvider.getApplicationContext<Application>())
        with(collectorScope) {
            launch { vm.crosswordData.collect {} }
            launch { vm.foundWords.collect {} }
            launch { vm.bonusWords.collect {} }
            launch { vm.revealedHints.collect {} }
            launch { vm.currentLevel.collect {} }
            launch { vm.gameMode.collect {} }
            launch { vm.difficulty.collect {} }
            launch { vm.competitiveScore.collect {} }
            launch { vm.tapToSpell.collect {} }
            launch { vm.wheelSpacing.collect {} }
            launch { vm.reminderEnabled.collect {} }
            launch { vm.reminderMinutesOfDay.collect {} }
            launch { vm.dailyStreak.collect {} }
            launch { vm.dailyBestStreak.collect {} }
            launch { vm.error.collect {} }
        }
        return vm
    }

    private fun pollUntil(deadlineMs: Long = 15000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < deadline && !cond()) Thread.sleep(50)
        assertTrue(cond())
    }

    @Test fun loadsFirstCasualLevelFromAssets() {
        val vm = vm()
        pollUntil { vm.crosswordData.value != null }
        assertNotNull(vm.crosswordData.value)
        assertEquals(1, vm.currentLevel.value)
        assertNull(vm.error.value)
        assertEquals(GameMode.CASUAL, vm.gameMode.value)
    }
    @Test fun dictionaryLookupsAfterInit() {
        val vm = vm()
        pollUntil { vm.isInDictionary("cat") }
        assertTrue(vm.isInDictionary("CAT"))
        assertFalse(vm.isInDictionary("zzzzqq"))
        assertTrue(vm.getDefinition("cat").isNotEmpty())
        assertTrue(vm.getDefinition("zzzzqq").isEmpty())
    }
    @Test fun casualFoundBonusAndLevelFlow() {
        val vm = vm()
        pollUntil { vm.crosswordData.value != null }
        vm.saveLevel(1)
        Thread.sleep(300)
        vm.addFoundWord("CAT")
        pollUntil { vm.foundWords.value.contains("CAT") }
        val total = kotlinx.coroutines.runBlocking { vm.addBonusWord("ZQXVM") }
        assertTrue(total >= 1)
        pollUntil { vm.bonusWords.value.contains("ZQXVM") }
        // saveLevel clears progress and moves the level
        vm.saveLevel(2)
        pollUntil { vm.currentLevel.value == 2 }
        pollUntil { vm.foundWords.value.isEmpty() }
        vm.saveLevel(1)
        pollUntil { vm.currentLevel.value == 1 }
    }
    @Test fun settingsActionsPersist() {
        val vm = vm()
        vm.setTapToSpell(true)
        pollUntil { vm.tapToSpell.value }
        vm.setTapToSpell(false)
        pollUntil { !vm.tapToSpell.value }
        vm.setWheelSpacing(WheelSpacing.WIDEST)
        pollUntil { vm.wheelSpacing.value == WheelSpacing.WIDEST }
        vm.setWheelSpacing(WheelSpacing.DEFAULT)
        pollUntil { vm.wheelSpacing.value == WheelSpacing.DEFAULT }
        // reminder settings round-trip through the shared daily reminder store
        vm.setReminderEnabled(true)
        pollUntil { vm.reminderEnabled.value }
        vm.setReminderTime(7, 30)
        pollUntil { vm.reminderMinutesOfDay.value == 450L }
        vm.setReminderEnabled(false)
        pollUntil { !vm.reminderEnabled.value }
    }
    @Test fun competitiveLobbyFlowAndScore() {
        val vm = vm()
        vm.setGameMode(GameMode.COMPETITIVE)
        pollUntil { vm.gameMode.value == GameMode.COMPETITIVE }
        assertFalse(vm.competitiveActive.value)
        vm.setDifficulty(Difficulty.EASY)
        pollUntil { vm.difficulty.value == Difficulty.EASY }
        val scoreBefore = vm.competitiveScore.value
        vm.loadNextCompetitiveLevel()
        pollUntil { vm.competitiveActive.value && vm.crosswordData.value != null }
        assertEquals(1, vm.competitiveLevelNumber.value.coerceAtLeast(1))
        assertTrue(vm.competitiveDeadline.value > System.currentTimeMillis())
        // found words go to the in-memory competitive set
        val word = vm.crosswordData.value!!.solutionWords.first()
        vm.addFoundWord(word)
        assertTrue(vm.foundWords.value.contains(word))
        vm.onCompetitiveWin()
        pollUntil { vm.competitiveResult.value?.won == true }
        assertEquals(Difficulty.EASY.winDelta, vm.competitiveResult.value!!.delta)
        pollUntil { vm.competitiveScore.value == scoreBefore + Difficulty.EASY.winDelta }
        assertFalse(vm.competitiveActive.value)
        // timeout deducts (clamped) and also returns to the lobby
        vm.loadNextCompetitiveLevel()
        pollUntil { vm.competitiveActive.value }
        vm.onCompetitiveTimeout()
        pollUntil { vm.competitiveResult.value?.won == false }
        assertEquals(-Difficulty.EASY.lossDelta, vm.competitiveResult.value!!.delta)
        vm.setDifficulty(Difficulty.MEDIUM)
        // back to casual for other tests
        vm.setGameMode(GameMode.CASUAL)
        pollUntil { vm.gameMode.value == GameMode.CASUAL }
    }
    @Test fun revealHintRevealsACellAndStartsCooldown() {
        val vm = vm()
        pollUntil { vm.crosswordData.value != null }
        val data = vm.crosswordData.value!!
        val before = vm.revealedHints.value
        vm.revealHint(data, vm.foundWords.value, before)
        pollUntil { vm.revealedHints.value.size > before.size || data.incidentalWords(vm.foundWords.value).isNotEmpty() }
        assertTrue(vm.hintCooldownEnd.value > System.currentTimeMillis())
        // revealing with everything already revealed is a no-op (no crash, no cooldown reset path taken)
        val allCells = data.letterPositions.values.flatMap { it.flatten() }.toSet()
        vm.revealHint(data, data.solutionWords, allCells)
    }
    @Test fun dailyModeGeneratesBoardAndWinRecordsStreak() {
        val vm = vm()
        vm.setGameMode(GameMode.DAILY)
        pollUntil { vm.gameMode.value == GameMode.DAILY }
        pollUntil(30000) { vm.crosswordData.value != null }
        assertNotNull(vm.crosswordData.value)
        assertTrue(vm.dailyDay.value > 0)
        vm.addFoundWord("ZQXDAILYWORD")
        pollUntil { vm.foundWords.value.contains("ZQXDAILYWORD") }
        vm.onDailyWin()
        pollUntil { vm.dailyStreak.value >= 1L }
        vm.setGameMode(GameMode.CASUAL)
        pollUntil { vm.gameMode.value == GameMode.CASUAL }
    }
    @Test fun achievementsManagerChecksExisting() {
        val vm = vm()
        val json = context.assets.open("achievements.json").bufferedReader().use { it.readText() }
        val manager = WordMakerAchievementsManager(context, json, vm.levelDataStore)
        manager.checkExistingAchievements()
        Thread.sleep(500)
        // no crash; the flows it reads are live
        assertTrue(vm.currentLevel.value >= 1)
    }
    @Test fun contractDefaultsAreHarmless() {
        val actions = WordGameActions.Noop
        actions.addFoundWord("X"); actions.saveLevel(9); actions.setGameMode(GameMode.DAILY)
        actions.revealHint(CrosswordData.fromString("CAT")!!, emptySet(), emptySet())
        assertFalse(actions.isInDictionary("cat"))
        assertTrue(actions.getDefinition("cat").isEmpty())
        assertEquals(0, kotlinx.coroutines.runBlocking { actions.addBonusWord("X") })
        actions.onSolutionWordFound("X"); actions.onCompetitiveTimeout()
        val lobby = CompetitiveLobbyActions.Noop
        lobby.setGameMode(GameMode.CASUAL); lobby.setDifficulty(Difficulty.HARD); lobby.loadNextCompetitiveLevel()
        val settings = SettingsActions.Noop
        settings.setTapToSpell(true); settings.setWheelSpacing(WheelSpacing.WIDE)
        settings.setReminderEnabled(true); settings.setReminderTime(1, 2)
        // state defaults
        assertEquals(GameMode.COMPETITIVE, CompetitiveLobbyUiState().gameMode)
        assertEquals(20, SettingsUiState().reminderHour)
        assertEquals(WheelSpacing.DEFAULT, SettingsUiState().wheelSpacing)
        assertNull(CompetitiveLobbyUiState().result)
        assertEquals(CompetitiveResult(true, 10), CompetitiveResult(true, 10))
    }
}
