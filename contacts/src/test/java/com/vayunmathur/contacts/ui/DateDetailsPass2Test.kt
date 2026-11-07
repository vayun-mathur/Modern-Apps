package com.vayunmathur.contacts.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.Route
import com.vayunmathur.contacts.data.CDKEvent
import com.vayunmathur.contacts.data.Event
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.LocalNavResultRegistry
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavResultRegistry
import kotlinx.datetime.LocalDate
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Second-pass date-details interactions: add, remove, and custom labels. */
@RunWith(RobolectricTestRunner::class)
class DateDetailsPass2Test {
    @get:Rule val rule = createComposeRule()

    private val options = listOf(CDKEvent.TYPE_ANNIVERSARY, CDKEvent.TYPE_OTHER, CDKEvent.TYPE_CUSTOM)
    private val anniversary = Event(5, LocalDate(2020, 6, 1), CDKEvent.TYPE_ANNIVERSARY)

    private fun render(events: List<Event>, changes: MutableList<List<Event>>): NavBackStack<Route> {
        val backStack = NavBackStack<Route>(arrayOf(Route.Main))
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides NavResultRegistry()) {
                DynamicTheme {
                    Column {
                        EditContactDateDetails(
                            backStack = backStack, details = events,
                            onDetailsChange = { changes += it },
                            icon = { DateDetailsAddIcon() },
                            options = options,
                        )
                    }
                }
            }
        }
        return backStack
    }

    @Test
    fun addDateAppendsDefaultEvent() {
        val changes = mutableListOf<List<Event>>()
        render(listOf(anniversary), changes)
        rule.onNodeWithText(
            androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
                .getString(R.string.add_date),
            substring = true,
        ).performClick()
        assertEquals(1, changes.size)
        assertEquals(2, changes.single().size)
        assertEquals(anniversary, changes.single().first())
    }

    @Test
    fun removeDateDropsIt() {
        val changes = mutableListOf<List<Event>>()
        render(listOf(anniversary), changes)
        // the remove button is the small clickable at the row's trailing edge
        val clickables = rule.onAllNodes(hasClickAction())
        val nodes = clickables.fetchSemanticsNodes()
        val smallest = nodes.indices.minByOrNull {
            val b = nodes[it].boundsInRoot
            (b.right - b.left) * (b.bottom - b.top)
        }!!
        clickables[smallest].performClick()
        assertTrue(changes.any { it.isEmpty() }, "remove should empty the list, changes=$changes")
    }

    @Test
    fun customEventShowsLabelFieldAndEditsIt() {
        val changes = mutableListOf<List<Event>>()
        val custom = Event(6, LocalDate(2021, 1, 1), CDKEvent.TYPE_CUSTOM, "Graduation")
        render(listOf(custom), changes)
        assertTrue(rule.onAllNodesWithText("Graduation", substring = true).fetchSemanticsNodes().isNotEmpty())
        val fields = rule.onAllNodes(hasSetTextAction())
        fields[fields.fetchSemanticsNodes().lastIndex].performTextReplacement("Wedding")
        assertTrue(changes.any { it.single().label == "Wedding" }, "changes=$changes")
    }

    @Test
    fun birthdayRowsAreSkippedByThisSection() {
        val changes = mutableListOf<List<Event>>()
        val birthday = Event(7, LocalDate(1992, 3, 15), CDKEvent.TYPE_BIRTHDAY)
        render(listOf(birthday), changes)
        // only the Add button renders; the birthday has its own section in the page
        rule.onNodeWithText(
            androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
                .getString(R.string.add_date),
            substring = true,
        ).assertExists()
        assertEquals(
            0,
            rule.onAllNodesWithText("1992", substring = true).fetchSemanticsNodes().size,
        )
    }
}
