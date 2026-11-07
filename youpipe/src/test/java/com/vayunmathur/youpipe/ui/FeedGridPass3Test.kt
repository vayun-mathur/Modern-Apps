package com.vayunmathur.youpipe.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.Text
import com.vayunmathur.youpipe.util.VideoRowState
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith

/** Slice 2/3 pass 3: FeedGrid list rendering and FeedVideoRow click. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class FeedGridPass3Test {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun feedGrid_rendersEveryRowWithItsKeyContent() {
        rule.setContent {
            Column {
                FeedGrid(
                    rows = listOf("Alpha", "Beta"),
                    key = { it },
                ) { item -> Text("row-$item") }
            }
        }
        rule.onNodeWithText("row-Alpha").assertIsDisplayed()
        rule.onNodeWithText("row-Beta").assertIsDisplayed()
    }

    @Test
    fun feedGrid_emptyRendersNothing() {
        rule.setContent {
            FeedGrid(rows = emptyList<String>(), key = { it }) { item -> Text("row-$item") }
        }
        rule.onNodeWithText("row-Alpha").assertDoesNotExist()
    }

    @Test
    fun feedVideoRow_clickOpensItsVideo() {
        var opened: Long? = null
        val row = VideoRowState(77, "Grid Video", "", "Chan", "3 views", null, "chan")
        rule.setContent { FeedVideoRow(row = row, openVideo = { opened = it }) }
        rule.onNodeWithText("Grid Video").assertIsDisplayed()
        rule.onNodeWithText("Grid Video").performClick()
        assertEquals(77L, opened)
    }

    @Test
    fun feedVideoRow_showsAuthorStatsAndReason() {
        val row = VideoRowState(78, "Reason Video", "", "Chan", "3 views", "Because you watched X", "chan", percentWatched = 0.5f)
        rule.setContent { FeedVideoRow(row = row, openVideo = {}) }
        rule.onNodeWithText("Reason Video").assertIsDisplayed()
        rule.onNodeWithText("Chan").assertIsDisplayed()
        rule.onNodeWithText("3 views").assertIsDisplayed()
    }
}
