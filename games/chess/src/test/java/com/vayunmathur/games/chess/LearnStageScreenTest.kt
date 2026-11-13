package com.vayunmathur.games.chess

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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

    /** The glyph size a Text actually renders at, in px, font scale included. */
    private fun renderedFontPx(node: androidx.compose.ui.semantics.SemanticsNode): Float {
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val input = layouts.first().layoutInput
        return with(input.density) { input.style.fontSize.toPx() }
    }

    @Test fun `level stepper glyphs keep their dp size at 200 percent font`() {
        rule.setContent {
            val d = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, 2f)
            ) {
                // Level 2 solved with three stars; 12 levels so two-digit numbers appear.
                LevelStepper(count = 12, current = 0, stars = listOf(0, 3) + List(10) { 0 }, onSelect = {})
            }
        }
        val dp = rule.density.density
        val stars = rule.onAllNodesWithText("★", useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(3, stars.size)
        // 9dp stars (3 x 9dp + gaps fit the 36dp circle) and 14dp numbers, whatever the font scale;
        // in sp they doubled to 18 / 24 and clipped.
        stars.forEach { assertEquals(9f * dp, renderedFontPx(it), 0.5f) }
        val twelve = rule.onNodeWithText("12", useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(14f * dp, renderedFontPx(twelve), 0.5f)
    }
}
