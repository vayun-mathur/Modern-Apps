package com.vayunmathur.games.chess

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.chess.data.LearnRepository
import com.vayunmathur.games.chess.util.LearnViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class LearnStageScreenTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @BeforeTest fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    @Test fun `stage screen shows intro then board after lets go`() {
        LearnRepository.ensureLoaded(ctx)
        val cat = LearnRepository.categories.first()
        val stage = cat.stages.first()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = LearnViewModel(app)
        vm.loadStage(cat.key, stage.key)
        rule.waitUntil(timeoutMillis = 15_000) { vm.uiState.value.level != null }
        var back = 0
        rule.setContent {
            LearnStageScreen(viewModel = vm, onBack = { back++ }, onOpenStage = { _, _ -> })
        }
        rule.onNodeWithText(ctx.getString(R.string.learn_lets_go)).performClick()
        // Board pieces render after intro dismissal
        assertTrue(vm.uiState.value.board.pieces.flatten().any { it != null })
        assertEquals(stage.key, vm.uiState.value.stage?.key)
        assertEquals(0, back)
    }

    @Test fun `stage screen without loaded stage shows loading`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = LearnViewModel(app)
        rule.setContent {
            LearnStageScreen(viewModel = vm, onBack = {}, onOpenStage = { _, _ -> })
        }
        // No stage: progress indicator branch, title falls back to Learn tab label
        rule.onNodeWithText(ctx.getString(R.string.tab_learn)).assertExists()
    }
}
