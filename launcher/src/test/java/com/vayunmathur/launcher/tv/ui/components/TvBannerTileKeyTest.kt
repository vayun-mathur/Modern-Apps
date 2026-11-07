package com.vayunmathur.launcher.tv.ui.components

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.launcher.tv.domain.TvRowOrder
import com.vayunmathur.launcher.tv.platform.TvTile
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * D-pad key handling for [TvBannerTile]: while a tile is held, direction
 * keys move it (by one, or by a row vertically), Menu is swallowed, and
 * when not held Menu on key-up opens the edit menu instead.
 */
@RunWith(AndroidJUnit4::class)
class TvBannerTileKeyTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun tile() = TvTile(
        packageName = "com.example.tv",
        label = "TV App",
        banner = null,
        appIcon = null,
        userInstalled = true,
    )

    private class Recorder {
        val moves = mutableListOf<Int>()
        var edits = 0
        var presses = 0
    }

    private fun show(moving: Boolean, recorder: Recorder) {
        composeRule.setContent {
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
            TvBannerTile(
                tile = tile(),
                tileWidth = 160.dp,
                artHeight = 90.dp,
                moving = moving,
                dimmed = false,
                focusRequester = focusRequester,
                onPress = { recorder.presses++ },
                onEdit = { recorder.edits++ },
                onMove = { recorder.moves += it },
            )
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `held tile moves with direction keys and swallows menu`() {
        val recorder = Recorder()
        show(moving = true, recorder = recorder)
        composeRule.onRoot().performKeyInput {
            pressKey(Key.DirectionRight)
            pressKey(Key.DirectionLeft)
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionUp)
            pressKey(Key.Menu)
        }
        composeRule.waitForIdle()
        assertEquals(
            listOf(1, -1, TvRowOrder.PER_ROW, -TvRowOrder.PER_ROW),
            recorder.moves,
        )
        assertEquals(0, recorder.edits)
    }

    @Test
    fun `unheld tile opens edit on menu and ignores directions`() {
        val recorder = Recorder()
        show(moving = false, recorder = recorder)
        composeRule.onRoot().performKeyInput {
            pressKey(Key.DirectionRight)
            pressKey(Key.Menu)
        }
        composeRule.waitForIdle()
        assertEquals(emptyList(), recorder.moves)
        assertEquals(1, recorder.edits)
        assertEquals(0, recorder.presses)
    }
}
