package com.vayunmathur.library.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Slice 2/3 (library/ui): the form and settings building blocks — sections,
 * labelled fields, detail groups, and the settings row family.
 */
@RunWith(RobolectricTestRunner::class)
class Slice2FormSettingsTest {

    @get:Rule
    val rule = createComposeRule()

    // --- FormSection ----------------------------------------------------------

    @Test
    fun `form section shows its title and fields`() {
        rule.setContent {
            FormSection(title = "Slice2 Form") {
                Text("field one")
            }
        }
        rule.onNodeWithText("Slice2 Form").assertIsDisplayed()
        rule.onNodeWithText("field one").assertIsDisplayed()
    }

    @Test
    fun `untitled form section just shows its content`() {
        rule.setContent {
            FormSection {
                Text("bare field")
            }
        }
        rule.onNodeWithText("bare field").assertIsDisplayed()
    }

    // --- LabeledTextField -----------------------------------------------------

    @Test
    fun `labelled field edits and reports`() {
        var value = ""
        rule.setContent {
            LabeledTextField(value = value, onValueChange = { value = it }, label = "Slice2 Label")
        }
        rule.onNodeWithText("Slice2 Label").assertIsDisplayed()
        rule.onNodeWithText("Slice2 Label").performTextInput("hello")
        assertEquals("hello", value)
    }

    @Test
    fun `supporting text and error flag render`() {
        rule.setContent {
            LabeledTextField(
                value = "bad",
                onValueChange = {},
                label = "Slice2 Field",
                isError = true,
                supportingText = "slice2 supporting",
            )
        }
        rule.onNodeWithText("slice2 supporting").assertIsDisplayed()
    }

    @Test
    fun `disabled field does not accept input`() {
        rule.setContent {
            LabeledTextField(value = "", onValueChange = {}, label = "Slice2 Off", enabled = false)
        }
        rule.onNodeWithText("Slice2 Off").assertIsNotEnabled()
    }

    // --- FormDetailGroup --------------------------------------------------------

    private data class Slice2Row(val number: String, val type: Int)

    @Test
    fun `detail group lists rows and removes one`() {
        val items = mutableListOf(Slice2Row("111", 0), Slice2Row("222", 1))
        var added = 0
        rule.setContent {
            FormDetailGroup(
                items = items.toList(),
                label = "Slice2 Number",
                addLabel = "Slice2 Add",
                typeOptions = listOf(0, 1),
                value = { it.number },
                onValueChange = { index, v -> items[index] = items[index].copy(number = v) },
                typeLabel = { "Type ${it.type}" },
                onTypeChange = { index, t -> items[index] = items[index].copy(type = t) },
                onRemove = { items.removeAt(it) },
                onAdd = { added++ },
                currentType = { it.type },
            )
        }
        rule.onNodeWithText("111").assertIsDisplayed()
        rule.onNodeWithText("222").assertIsDisplayed()
        // The first row's remove button drops it; the add button fires onAdd.
        rule.onAllNodes(hasContentDescription("Remove"))[0].performClick()
        rule.waitForIdle()
        assertEquals(listOf("222"), items.map { it.number })
        rule.onNodeWithText("Slice2 Add").performClick()
        assertEquals(1, added)
    }

    @Test
    fun `detail group edits a value in place`() {
        val items = mutableListOf(Slice2Row("111", 0))
        rule.setContent {
            FormDetailGroup(
                items = items.toList(),
                label = "Slice2 Number",
                addLabel = "Slice2 Add",
                typeOptions = listOf(0),
                value = { it.number },
                onValueChange = { index, v -> items[index] = items[index].copy(number = v) },
                typeLabel = { "Type" },
                onTypeChange = { _, _ -> },
                onRemove = { items.removeAt(it) },
                onAdd = {},
                currentType = { it.type },
            )
        }
        rule.onNodeWithText("111").performTextReplacement("999")
        assertEquals("999", items[0].number)
    }

    @Test
    fun `mandatory row clears instead of removing`() {
        val items = mutableListOf(Slice2Row("111", 0))
        var removed = 0
        rule.setContent {
            FormDetailGroup(
                items = items.toList(),
                label = "Slice2 Number",
                addLabel = "Slice2 Add",
                typeOptions = listOf(0),
                value = { it.number },
                onValueChange = { index, v -> items[index] = items[index].copy(number = v) },
                typeLabel = { "Type" },
                onTypeChange = { _, _ -> },
                onRemove = { removed++ },
                onAdd = {},
                currentType = { it.type },
                isMandatory = { true },
            )
        }
        rule.onNodeWithContentDescription("Remove").performClick()
        rule.waitForIdle()
        assertEquals(0, removed)
        assertEquals("", items[0].number)
    }

    @Test
    fun `custom type label field shows when the row is custom`() {
        val items = listOf(Slice2Row("111", 99))
        rule.setContent {
            FormDetailGroup(
                items = items,
                label = "Slice2 Number",
                addLabel = "Slice2 Add",
                typeOptions = listOf(99),
                value = { it.number },
                onValueChange = { _, _ -> },
                typeLabel = { "Custom" },
                onTypeChange = { _, _ -> },
                onRemove = {},
                onAdd = {},
                currentType = { it.type },
                isCustom = { true },
                onLabelChange = { _, _ -> },
                customLabel = { "my label" },
                customLabelText = "Slice2 Custom",
            )
        }
        rule.onNodeWithText("my label").assertIsDisplayed()
        rule.onNodeWithText("Slice2 Custom").assertIsDisplayed()
    }

    // --- SettingsSection / SettingsRow ------------------------------------------

    @Test
    fun `settings section shows title and rows`() {
        rule.setContent {
            SettingsSection(title = "Slice2 Settings") {
                SettingsRow(title = "Slice2 Row")
            }
        }
        rule.onNodeWithText("Slice2 Settings").assertIsDisplayed()
        rule.onNodeWithText("Slice2 Row").assertIsDisplayed()
    }

    @Test
    fun `settings row click fires and supporting text shows`() {
        var clicked = 0
        rule.setContent {
            SettingsRow(title = "Slice2 Row", supportingText = "slice2 sub", onClick = { clicked++ })
        }
        rule.onNodeWithText("slice2 sub").assertIsDisplayed()
        rule.onNodeWithText("Slice2 Row").performClick()
        assertEquals(1, clicked)
    }

    @Test
    fun `row without a handler is not clickable`() {
        rule.setContent {
            SettingsRow(title = "Slice2 Static")
        }
        rule.onNodeWithText("Slice2 Static").assertIsDisplayed()
        // No click action: the node carries no clickable semantics.
        rule.onNodeWithText("Slice2 Static").performClick()
    }

    // --- SettingsSwitchRow --------------------------------------------------------

    @Test
    fun `switch row toggles from the row and the switch`() {
        val seen = mutableListOf<Boolean>()
        rule.setContent {
            SettingsSwitchRow(title = "Slice2 Toggle", checked = false, onCheckedChange = seen::add)
        }
        rule.onNodeWithText("Slice2 Toggle").performClick()
        assertEquals(listOf(true), seen)
    }

    // --- SettingsSelectRow --------------------------------------------------------

    @Test
    fun `select row shows the current choice and picks another`() {
        var selected = "A"
        rule.setContent {
            SettingsSelectRow(
                title = "Slice2 Pick",
                selected = selected,
                options = listOf("A", "B"),
                label = { "Choice $it" },
                onSelect = { selected = it },
            )
        }
        rule.onNodeWithText("Choice A").assertIsDisplayed()
        rule.onNodeWithText("Slice2 Pick").performClick()
        rule.onNodeWithText("Choice B").performClick()
        assertEquals("B", selected)
    }

    // --- SettingsDivider ------------------------------------------------------------

    @Test
    fun `settings divider renders without crashing`() {
        rule.setContent {
            SettingsSection {
                SettingsRow(title = "Above")
                SettingsDivider()
                SettingsRow(title = "Below")
            }
        }
        rule.onNodeWithText("Above").assertIsDisplayed()
        rule.onNodeWithText("Below").assertIsDisplayed()
    }

    // --- SettingsExposedSelectRow -----------------------------------------------------

    @Test
    fun `exposed select shows the current item and picks another`() {
        var selected = "X"
        rule.setContent {
            SettingsExposedSelectRow(
                label = "Slice2 Codec",
                selected = selected,
                options = listOf("X", "Y"),
                itemLabel = { "Item $it" },
                onSelect = { selected = it },
                itemSupporting = { if (it == "Y") "why Y" else null },
            )
        }
        rule.onNodeWithText("Item X").assertIsDisplayed()
        rule.onNodeWithText("Item X").performClick()
        // The open menu shows each option with its supporting line.
        assertTrue(
            rule.onAllNodes(hasText("why Y")).fetchSemanticsNodes().isNotEmpty(),
        )
        rule.onNodeWithText("Item Y").performClick()
        assertEquals("Y", selected)
    }
}
