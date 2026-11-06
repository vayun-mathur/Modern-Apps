package com.vayunmathur.launcher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.launcher.domain.CellRect
import com.vayunmathur.launcher.domain.LauncherItemType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

/**
 * Gesture tests (Robolectric + createComposeRule) for [launcherDragInput]:
 * tap does nothing, long-press on an item opens its popup, long-press on
 * empty wallpaper opens options, and a DOWN while a popup is open dismisses.
 */
@RunWith(AndroidJUnit4::class)
class LauncherDragInputTest {

    @get:Rule val rule = createComposeRule()

    private fun payload() = DragPayload(
        itemId = 1L,
        type = LauncherItemType.APPLICATION,
        label = "Mail",
        rect = CellRect(0, 0),
        sourceBounds = Rect.Zero,
    )

    private fun show(
        controller: LauncherDragController,
        onLongPressItem: (DragPayload, Rect) -> Unit = { _, _ -> },
        onLongPressEmpty: (Offset) -> Unit = {},
        onDismissPopup: () -> Unit = {},
        popupOpen: () -> Boolean = { false },
    ) {
        rule.setContent {
            CompositionLocalProvider(LocalLauncherDrag provides controller) {
                controller.registerSource("s", Rect(0f, 0f, 500f, 500f)) { payload() }
                Box(
                    Modifier
                        .size(300.dp)
                        .background(Color.White)
                        .launcherDragInput(
                            controller = controller,
                            onLongPressItem = onLongPressItem,
                            onLongPressEmpty = onLongPressEmpty,
                            popupOpen = popupOpen,
                            onDismissPopup = onDismissPopup,
                        ),
                )
            }
        }
    }

    @Test fun tapDoesNothing() {
        var menued: DragPayload? = null
        var options: Offset? = null
        show(
            LauncherDragController(),
            onLongPressItem = { p, _ -> menued = p },
            onLongPressEmpty = { options = it },
        )
        rule.onRoot().performTouchInput { swipeUp() }
        rule.waitForIdle()
        assertNull(menued)
        assertNull(options)
    }

    @Test fun longPressOnItemOpensPopup() {
        var menued: DragPayload? = null
        var anchor: Rect? = null
        show(
            LauncherDragController(),
            onLongPressItem = { p, a ->
                menued = p
                anchor = a
            },
        )
        rule.onRoot().performTouchInput { longClick(durationMillis = 1000) }
        rule.waitForIdle()
        assertEquals(1L, menued?.itemId)
    }

    @Test fun longPressOnEmptyOpensOptions() {
        var options: Offset? = null
        val controller = LauncherDragController()
        rule.setContent {
            CompositionLocalProvider(LocalLauncherDrag provides controller) {
                // No sources registered: the press lands on empty wallpaper.
                Box(
                    Modifier
                        .size(300.dp)
                        .background(Color.White)
                        .launcherDragInput(
                            controller = controller,
                            onLongPressEmpty = { options = it },
                        ),
                )
            }
        }
        rule.onRoot().performTouchInput { longClick(durationMillis = 1000) }
        rule.waitForIdle()
        assertNotNull(options)
    }

    @Test fun downWhilePopupOpenDismisses() {
        var dismissed = false
        var menued: DragPayload? = null
        show(
            LauncherDragController(),
            popupOpen = { true },
            onDismissPopup = { dismissed = true },
            onLongPressItem = { p, _ -> menued = p },
        )
        rule.onRoot().performTouchInput { longClick() }
        rule.waitForIdle()
        assertEquals(true, dismissed)
        // The dismissing DOWN never becomes a long-press.
        assertNull(menued)
    }

    @Test fun fastScrollStripClaimsDown() {
        val strip = FastScrollStrip()
        var scrolled = false
        val controller = LauncherDragController()
        rule.setContent {
            CompositionLocalProvider(LocalLauncherDrag provides controller) {
                Box(
                    Modifier
                        .size(300.dp)
                        .background(Color.White)
                        .launcherDragInput(
                            controller = controller,
                            fastScroll = strip,
                            onLongPressEmpty = {},
                        ),
                )
            }
        }
        rule.waitForIdle()
        strip.bounds = Rect(0f, 0f, 1000f, 2000f)
        strip.sections = 3
        strip.onFraction = { scrolled = true }
        rule.onRoot().performTouchInput { swipeUp() }
        rule.waitForIdle()
        assertEquals(true, scrolled)
    }
}
