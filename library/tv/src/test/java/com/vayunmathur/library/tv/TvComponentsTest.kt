package com.vayunmathur.library.tv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.tv.material3.Text
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class TvComponentsTest {
    @get:Rule val rule = createComposeRule()

    private fun clickText(text: String) {
        val node = rule.onNode(androidx.compose.ui.test.hasText(text)).fetchSemanticsNode()
        val ok = node.config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action?.invoke()
        assertTrue(ok == true, "no click action for '$text'")
    }

    private fun themed(content: @androidx.compose.runtime.Composable () -> Unit) {
        rule.setContent { MaTvTheme(Color(0xFF3366FF)) { content() } }
    }

    @Test fun `button primary and disabled, icon button and chip clicks`() {
        var clicks = 0
        themed {
            Column {
                TvButton("Play", onClick = { clicks++ }, primary = true)
                TvButton("Off", onClick = { clicks += 10 }, enabled = false)
                TvChip("Movies", selected = true, onClick = { clicks += 100 })
                TvChip("Music", selected = false, onClick = { clicks += 1000 })
            }
        }
        clickText("Play")
        clickText("Movies")
        assertEquals(101, clicks)
        rule.onNodeWithText("Off").assertExists()
    }

    @Test fun `list row and switch row toggle`() {
        var rowClicks = 0
        var switches = mutableListOf<Boolean>()
        themed {
            Column {
                TvListRow(headline = "Settings row", supporting = "Details here", overline = "Section", onClick = { rowClicks++ })
                TvSwitchRow(headline = "Subtitles", checked = false, onCheckedChange = { switches += it })
            }
        }
        rule.onNodeWithText("Settings row").assertExists()
        rule.onNodeWithText("Details here").assertExists()
        clickText("Settings row")
        assertEquals(1, rowClicks)
        clickText("Subtitles")
        assertEquals(listOf(true), switches)
    }

    @Test fun `media card, info card, badge and progress strip render`() {
        var clicks = 0
        themed {
            Column {
                TvMediaCard(onClick = { clicks++ }, image = { Box {} }, title = "Movie One", subtitle = "2024", progress = 0.5f, badge = "1h 30m")
                TvInfoCard(title = "Info title", subtitle = "Info sub", description = "A description", onClick = { clicks += 10 })
                TvProgressStrip(0.25f)
            }
        }
        rule.onNodeWithText("Movie One").assertExists()
        clickText("Info title")
        assertEquals(10, clicks)
    }

    @Test fun `empty error loading and dialog states`() {
        var dismisses = 0
        themed {
            Column {
                TvEmptyState(title = "Nothing here", body = "Try again later", isError = false, action = { TvButton("Retry", onClick = {}) })
                TvLoading(label = "Loading content")
            }
        }
        rule.onNodeWithText("Nothing here").assertExists()
        rule.onNodeWithText("Loading content").assertExists()
        assertEquals(0, dismisses)
    }

    @Test fun `dialog shows body and routes button clicks`() {
        var dismisses = 0
        rule.setContent {
            MaTvTheme(Color.Blue) {
                TvDialog(title = "Delete?", body = "This cannot be undone", onDismissRequest = { dismisses++ }) {
                    TvButton("Cancel", onClick = { dismisses++ })
                }
            }
        }
        rule.onNodeWithText("Delete?").assertExists()
        rule.onNodeWithText("This cannot be undone").assertExists()
        clickText("Cancel")
        assertEquals(1, dismisses)
    }

    @Test fun `text field shows placeholder and value`() {
        themed { TvTextField(value = "", onValueChange = {}, placeholder = "Search movies") }
        rule.onNodeWithText("Search movies").assertExists()
    }

    @Test fun `text field shows entered value`() {
        rule.setContent {
            MaTvTheme(Color.Blue) { TvTextField(value = "Dune", onValueChange = {}, placeholder = "Search") }
        }
        rule.onNodeWithText("Dune").assertExists()
    }

    @Test fun `grid, shelf, headers and seek row render`() {
        themed {
            Column {
                TvScreenHeader(title = "Home", subtitle = "Welcome back")
                TvShelf(title = "Trending", trailing = "See all") {
                    items(listOf("A", "B")) { Text(it) }
                }
            }
        }
        rule.onNodeWithText("Home").assertExists()
        rule.onNodeWithText("Trending").assertExists()
    }

    @Test fun `grid full width items and seek row render`() {
        rule.setContent {
            MaTvTheme(Color.Blue) {
                Column {
                    TvGrid(columns = 2) {
                        tvFullWidth("header") { Text("Full width") }
                        items(listOf(1, 2)) { Text("Item $it") }
                    }
                    TvSeekRow(elapsed = "10:00", total = "20:00", progress = 0.5f, buffered = 0.8f, active = true, markers = listOf(0.25f))
                }
            }
        }
        rule.onNodeWithText("10:00").assertExists()
        rule.onNodeWithText("20:00").assertExists()
        rule.onNodeWithText("Full width").assertExists()
    }

    @Test fun `app frame renders destinations and content`() {
        var selected = -1
        themed {
            TvAppFrame(
                destinations = listOf(TvDestination("Home") { Text("H") }, TvDestination("Films") { Text("F") }),
                selected = 0,
                onSelect = { selected = it },
            ) { Text("Frame content") }
        }
        rule.onNodeWithText("Frame content").assertExists()
        rule.onNodeWithText("Home").assertExists()
        assertEquals(-1, selected)
    }

    @Test fun `initial focus composable and dimens are sane`() {
        themed {
            Box {
                TvInitialFocus(FocusRequester())
                Text("focused root")
            }
        }
        rule.onNodeWithText("focused root").assertExists()
        assertTrue(TvDimens.Rail.value > TvDimens.ItemGap.value)
        assertTrue(TvAspect.Landscape > 1f)
    }
}
