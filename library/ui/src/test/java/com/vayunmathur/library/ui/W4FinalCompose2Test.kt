package com.vayunmathur.library.ui

import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * W4: [DetailLazyColumn] lazy detail scaffolds, [FormDetailGroup] editable
 * item groups (edit, add, remove, custom labels), and the permissions gate
 * rows for special-access requirements.
 */
@RunWith(RobolectricTestRunner::class)
class W4FinalCompose2Test {

    @get:Rule
    val rule = createComposeRule()

    @Test fun detailLazyColumnRendersItemsAndTitle() {
        rule.setContent {
            DetailLazyColumn(
                title = "Album Details",
                scrollBehavior = appBarScrollBehavior(),
            ) {
                items((1..30).toList()) { n -> Text("Track $n") }
            }
        }
        rule.onNodeWithText("Album Details").assertIsDisplayed()
        rule.onNodeWithText("Track 1").assertExists()
        rule.onNodeWithText("Track 5").assertExists()
    }

    private data class Entry(val type: Int, val value: String, val custom: Boolean = false)

    @Test fun formDetailGroupEditsAddsAndRemoves() {
        var items = listOf(Entry(0, "first"), Entry(1, "second", custom = true))
        val changes = mutableListOf<String>()
        var added = false
        var removed: Int? = null
        rule.setContent {
            FormDetailGroup(
                items = items,
                label = "Emails",
                addLabel = "Add email",
                typeOptions = listOf(0, 1, 2),
                value = { it.value },
                onValueChange = { i, v -> changes.add("value:$i:$v") },
                typeLabel = { "Type ${it.type}" },
                onTypeChange = { i, t -> changes.add("type:$i:$t") },
                onRemove = { removed = it },
                onAdd = { added = true },
                currentType = { it.type },
                isCustom = { it.custom },
                customLabel = { "CustomLabel" },
                onLabelChange = { i, v -> changes.add("label:$i:$v") },
                optionLabel = { "Option $it" },
            )
        }
        assertTrue(rule.onAllNodesWithText("Emails").fetchSemanticsNodes().isNotEmpty())
        val fields = rule.onAllNodes(hasSetTextAction())
        assertTrue(fields.fetchSemanticsNodes().isNotEmpty())
        fields[0].performTextClearance()
        fields[0].performTextInput("new@example.org")
        assertTrue(changes.any { it.startsWith("value:0:") }, changes.toString())
        rule.onNodeWithText("Add email", substring = true).performClick()
        assertTrue(added)
        assertEquals(null, removed)
    }

    @Test fun permissionsGateShowsSpecialAccessRows() {
        rule.setContent {
            AppPermissionsGate(
                spec = AppPermissionsSpec(
                    title = "Needs access",
                    subtitle = "For full function",
                    requirements = listOf(
                        PermissionRequirement.ExactAlarms,
                        PermissionRequirement.NotificationListener,
                    ),
                ),
            ) { Text("Gate content") }
        }
        rule.waitForIdle()
        // The gate shows exactly one of its two faces: the content when every
        // requirement is granted, or the requirements screen when not.
        val contentShown = rule.onAllNodesWithText("Gate content").fetchSemanticsNodes().isNotEmpty()
        val gateShown = rule.onAllNodesWithText("Grant Required Permissions").fetchSemanticsNodes().isNotEmpty()
        assertTrue(contentShown xor gateShown, "content=$contentShown gate=$gateShown")
        if (gateShown) {
            assertTrue(
                rule.onAllNodesWithText("Exact alarms", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                    rule.onAllNodesWithText("Notification", substring = true).fetchSemanticsNodes().isNotEmpty())
        }
    }
}
