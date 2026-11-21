package com.vayunmathur.games.wordmaker.ui

import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.games.wordmaker.data.CrosswordData
import com.vayunmathur.games.wordmaker.data.GameMode
import com.vayunmathur.games.wordmaker.platform.WordGameActions
import com.vayunmathur.games.wordmaker.platform.WordGameUiState
import com.vayunmathur.games.wordmaker.platform.WordMakerViewModel
import com.vayunmathur.library.util.AchievementsManager

@Composable
fun WordGamePage(
    crosswordData: CrosswordData,
    currentLevel: Int,
    viewModel: WordMakerViewModel,
    achievementsManager: AchievementsManager,
    onOpenGameCenter: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val foundWords by viewModel.foundWords.collectAsState()
    val bonusWords by viewModel.bonusWords.collectAsState()
    val tapToSpellPreference by viewModel.tapToSpellPreference.collectAsState()
    // Swipe spelling cannot be driven by a screen reader, so tap-to-spell turns on
    // automatically while one is running — unless the player set the toggle themselves.
    val screenReaderEnabled = rememberScreenReaderEnabled()
    val tapToSpell = effectiveTapToSpell(tapToSpellPreference, screenReaderEnabled)
    val wheelSpacing by viewModel.wheelSpacing.collectAsState()
    val revealedHints by viewModel.revealedHints.collectAsState()
    val hintCooldownEnd by viewModel.hintCooldownEnd.collectAsState()
    val gameMode by viewModel.gameMode.collectAsState()
    val competitiveScore by viewModel.competitiveScore.collectAsState()
    val competitiveLevelNumber by viewModel.competitiveLevelNumber.collectAsState()
    val competitiveDeadline by viewModel.competitiveDeadline.collectAsState()
    val dailyDay by viewModel.dailyDay.collectAsState()
    val dailyStreak by viewModel.dailyStreak.collectAsState()
    val dailyBestStreak by viewModel.dailyBestStreak.collectAsState()

    val isCompetitive = gameMode == GameMode.COMPETITIVE
    val isDaily = gameMode == GameMode.DAILY
    val isWon = crosswordData.winsWith(foundWords)

    LaunchedEffect(isWon) {
        if (!isWon) return@LaunchedEffect
        if (isCompetitive) {
            viewModel.onCompetitiveWin()
            return@LaunchedEffect
        }
        if (isDaily) {
            viewModel.onDailyWin()
            achievementsManager.onAchievementUnlocked("first_daily")
            return@LaunchedEffect
        }
        if (currentLevel == 1) achievementsManager.onAchievementUnlocked("level_1_done")
        if (currentLevel == 861) achievementsManager.onAchievementUnlocked("manual_levels_done")

        achievementsManager.onProgressUpdated("manual_levels_done", currentLevel)
        achievementsManager.onProgressUpdated("level_50", currentLevel)
        achievementsManager.onProgressUpdated("level_100", currentLevel)
        achievementsManager.onProgressUpdated("level_500", currentLevel)
    }

    LaunchedEffect(dailyBestStreak) {
        if (dailyBestStreak <= 0L) return@LaunchedEffect
        achievementsManager.onProgressUpdated("daily_streak_7", dailyBestStreak.toInt())
        achievementsManager.onProgressUpdated("daily_streak_30", dailyBestStreak.toInt())
    }

    // The achievements manager belongs to the activity, not the ViewModel, so the two
    // achievement-reporting callbacks are stitched on here rather than pushed into the screen.
    val actions = remember(viewModel, achievementsManager) {
        object : WordGameActions by viewModel {
            override fun onSolutionWordFound(word: String) {
                if (word.length >= 7) achievementsManager.onAchievementUnlocked("long_word")
            }

            override suspend fun addBonusWord(word: String): Int {
                val newTotal = viewModel.addBonusWord(word)
                achievementsManager.onProgressUpdated("bonus_hunter", newTotal)
                return newTotal
            }
        }
    }

    WordGameScreen(
        state = WordGameUiState(
            crosswordData = crosswordData,
            currentLevel = currentLevel,
            foundWords = foundWords,
            bonusWords = bonusWords,
            tapToSpell = tapToSpell,
            wheelSpacing = wheelSpacing,
            revealedHints = revealedHints,
            hintCooldownEnd = hintCooldownEnd,
            gameMode = gameMode,
            competitiveScore = competitiveScore,
            competitiveLevelNumber = competitiveLevelNumber,
            competitiveDeadline = competitiveDeadline,
            dailyDay = dailyDay,
            dailyStreak = dailyStreak
        ),
        actions = actions,
        onOpenGameCenter = onOpenGameCenter,
        onOpenSettings = onOpenSettings
    )
}

/**
 * Whether tap-to-spell is in effect: the player's explicit setting when they have
 * made one, otherwise on exactly while a screen reader is running. Pure, so the
 * override rule is unit-testable without an AccessibilityManager.
 */
internal fun effectiveTapToSpell(manualPreference: Boolean?, screenReaderEnabled: Boolean): Boolean =
    manualPreference ?: screenReaderEnabled

/**
 * True while a screen reader (TalkBack) is running: an accessibility service is
 * enabled and touch exploration is on. Listens for both flipping mid-game, so
 * turning TalkBack off returns the wheel to swipe without restarting the screen.
 */
@Composable
internal fun rememberScreenReaderEnabled(): Boolean {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val manager = context.getSystemService(AccessibilityManager::class.java)
        if (manager == null) {
            enabled = false
            return@DisposableEffect onDispose {}
        }
        fun update() {
            enabled = manager.isEnabled && manager.isTouchExplorationEnabled
        }
        update()
        val stateListener = AccessibilityManager.AccessibilityStateChangeListener { update() }
        val touchListener = AccessibilityManager.TouchExplorationStateChangeListener { update() }
        manager.addAccessibilityStateChangeListener(stateListener)
        manager.addTouchExplorationStateChangeListener(touchListener)
        onDispose {
            manager.removeAccessibilityStateChangeListener(stateListener)
            manager.removeTouchExplorationStateChangeListener(touchListener)
        }
    }
    return enabled
}
