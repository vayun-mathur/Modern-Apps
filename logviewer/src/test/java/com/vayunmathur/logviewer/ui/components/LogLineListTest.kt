package com.vayunmathur.logviewer.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.logviewer.domain.LogKind
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class LogLineListTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Composable
    private fun ListUnderTest(lines: List<String>, kind: LogKind, zooms: MutableList<Float>) {
        LogLineList(
            lines = lines,
            kind = kind,
            fontSizeSp = 12f,
            listState = rememberLazyListState(),
            contentPadding = PaddingValues(0.dp),
            onZoom = { zooms += it },
        )
    }

    @Test
    fun `renders every line for an error report verbatim`() {
        val zooms = mutableListOf<Float>()
        composeRule.setContent {
            DynamicTheme {
                ListUnderTest(listOf("alpha", "  indented kept  ", "omega"), LogKind.ErrorReport, zooms)
            }
        }
        composeRule.onNodeWithText("alpha").assertIsDisplayed()
        composeRule.onNodeWithText("omega").assertIsDisplayed()
    }

    @Test
    fun `renders logcat lines trimmed with tabs expanded`() {
        val zooms = mutableListOf<Float>()
        composeRule.setContent {
            DynamicTheme {
                ListUnderTest(listOf("\t07-15 I Tag: hello\tworld  "), LogKind.Logcat, zooms)
            }
        }
        composeRule.onNodeWithText("07-15 I Tag: hello world").assertIsDisplayed()
        assertEquals(emptyList(), zooms)
    }

    @Test
    fun `empty list renders no rows`() {
        val zooms = mutableListOf<Float>()
        composeRule.setContent {
            DynamicTheme { ListUnderTest(emptyList(), LogKind.Logcat, zooms) }
        }
        composeRule.onNodeWithText("alpha").assertDoesNotExist()
    }

    @Test
    fun `many lines render lazily with the first visible`() {
        val zooms = mutableListOf<Float>()
        val lines = (1..500).map { "line number $it" }
        composeRule.setContent {
            DynamicTheme { ListUnderTest(lines, LogKind.ErrorReport, zooms) }
        }
        composeRule.onNodeWithText("line number 1").assertIsDisplayed()
    }
}
