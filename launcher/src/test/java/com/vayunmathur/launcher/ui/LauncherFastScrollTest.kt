package com.vayunmathur.launcher.ui

import android.content.ComponentName
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.launcher.platform.ComponentKey
import com.vayunmathur.launcher.platform.DrawerApp
import com.vayunmathur.launcher.ui.components.FastScrollStrip
import com.vayunmathur.launcher.ui.components.LauncherDragController
import com.vayunmathur.launcher.ui.components.LocalLauncherDrag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * Compose tests (Robolectric + createComposeRule) for the fast-scroll
 * pieces: [FastScrollThumb] publishes section counts to the strip and jumps
 * on fractions, [LetterBubble] shows the current letter.
 */
@RunWith(AndroidJUnit4::class)
class LauncherFastScrollTest {

    @get:Rule val rule = createComposeRule()

    private fun app(label: String) = DrawerApp(
        key = ComponentKey(ComponentName("com.example.$label", "com.example.$label.Main"), 0L),
        label = label,
    )

    private val apps = listOf(app("Alpha"), app("Beta"), app("Gamma"))

    @Test fun thumbPublishesSectionsAndJumps() {
        val strip = FastScrollStrip()
        val jumps = mutableListOf<Int>()
        rule.setContent {
            CompositionLocalProvider(LocalLauncherDrag provides LauncherDragController()) {
                FastScrollThumb(
                    apps = apps,
                    strip = strip,
                    gridState = rememberLazyGridState(),
                    onJump = { jumps += it },
                )
            }
        }
        rule.waitForIdle()
        // A, B, G: three sections.
        assertEquals(3, strip.sections)
        strip.onFraction(0.5f)
        assertEquals(listOf(1), jumps)
        // Same section again: no repeat jump.
        strip.onFraction(0.55f)
        assertEquals(listOf(1), jumps)
    }

    @Test fun thumbWithoutStripComposes() {
        rule.setContent {
            CompositionLocalProvider(LocalLauncherDrag provides LauncherDragController()) {
                FastScrollThumb(
                    apps = apps,
                    strip = null,
                    gridState = rememberLazyGridState(),
                    onJump = {},
                )
            }
        }
    }

    @Test fun letterBubbleShowsLetter() {
        rule.setContent {
            CompositionLocalProvider(LocalLauncherDrag provides LauncherDragController()) {
                LetterBubble(apps = apps, fraction = 0.5f)
            }
        }
        rule.onNodeWithText("B").assertIsDisplayed()
    }

    @Test fun letterBubbleFirstSection() {
        rule.setContent {
            CompositionLocalProvider(LocalLauncherDrag provides LauncherDragController()) {
                LetterBubble(apps = apps, fraction = 0.0f)
            }
        }
        rule.onNodeWithText("A").assertIsDisplayed()
    }
}
