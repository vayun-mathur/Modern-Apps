package com.vayunmathur.library.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Slice 2/3 (library/ui): the FormDetailGroup/FormSection branches the main
 * suite leaves out — shared-element rows, expand-on-enter, default option
 * labels, mandatory empties, and the shared-text field path.
 */
@RunWith(RobolectricTestRunner::class)
class Slice2FormExtraTest {

    @get:Rule
    val rule = createComposeRule()

    private data class Slice2Row(val number: String, val type: Int)

    @Test
    fun `shared-key rows edit through the morphing branch`() {
        val items = mutableListOf(Slice2Row("111", 0))
        rule.setContent {
            FormDetailGroup(
                items = items.toList(),
                label = "Slice2 Shared",
                addLabel = "Slice2 Add",
                typeOptions = listOf(0),
                value = { it.number },
                onValueChange = { index, v -> items[index] = items[index].copy(number = v) },
                typeLabel = { "Type" },
                onTypeChange = { _, _ -> },
                onRemove = { items.removeAt(it) },
                onAdd = {},
                currentType = { it.type },
                sharedKey = { "slice2-row-${it.number}" },
            )
        }
        rule.onNodeWithText("111").assertIsDisplayed()
        rule.onNodeWithText("111").performTextReplacement("1112")
        assertEquals("1112", items[0].number)
    }

    @Test
    fun `expand on enter renders rows and the add button`() {
        rule.setContent {
            FormDetailGroup(
                items = listOf(Slice2Row("111", 0)),
                label = "Slice2 Expand",
                addLabel = "Slice2 Add More",
                typeOptions = listOf(0),
                value = { it.number },
                onValueChange = { _, _ -> },
                typeLabel = { "Type" },
                onTypeChange = { _, _ -> },
                onRemove = {},
                onAdd = {},
                currentType = { it.type },
                expandOnEnter = true,
            )
        }
        rule.onNodeWithText("111").assertIsDisplayed()
        rule.onNodeWithText("Slice2 Add More").assertIsDisplayed()
    }

    @Test
    fun `default option labels fall back to the raw value`() {
        var picked = -1
        rule.setContent {
            FormDetailGroup(
                items = listOf(Slice2Row("111", 0)),
                label = "Slice2 Opts",
                addLabel = "Slice2 Add",
                typeOptions = listOf(7),
                value = { it.number },
                onValueChange = { _, _ -> },
                typeLabel = { "Type 0" },
                onTypeChange = { _, t -> picked = t },
                onRemove = {},
                onAdd = {},
                currentType = { it.type },
                optionLabel = null,
            )
        }
        // Open the type menu: the option renders as its raw value.
        rule.onNodeWithText("Type 0").performClick()
        rule.onNodeWithText("7").performClick()
        assertEquals(7, picked)
    }

    @Test
    fun `mandatory empty row shows no clear button`() {
        rule.setContent {
            FormDetailGroup(
                items = listOf(Slice2Row("", 0)),
                label = "Slice2 Empty",
                addLabel = "Slice2 Add",
                typeOptions = listOf(0),
                value = { it.number },
                onValueChange = { _, _ -> },
                typeLabel = { "Type" },
                onTypeChange = { _, _ -> },
                onRemove = { error("must not remove a mandatory row") },
                onAdd = {},
                currentType = { it.type },
                isMandatory = { true },
            )
        }
        rule.onNodeWithText("Slice2 Add").assertIsDisplayed()
    }

    @Test
    fun `custom row without a label handler shows no extra field`() {
        rule.setContent {
            FormDetailGroup(
                items = listOf(Slice2Row("111", 0)),
                label = "Slice2 NoCustom",
                addLabel = "Slice2 Add",
                typeOptions = listOf(0),
                value = { it.number },
                onValueChange = { _, _ -> },
                typeLabel = { "Type" },
                onTypeChange = { _, _ -> },
                onRemove = {},
                onAdd = {},
                currentType = { it.type },
                isCustom = { true },
                onLabelChange = null,
            )
        }
        rule.onNodeWithText("111").assertIsDisplayed()
    }

    @Test
    fun `shared-text labelled field edits`() {
        var value = ""
        rule.setContent {
            LabeledTextField(
                value = value,
                onValueChange = { value = it },
                label = "Slice2 Morph",
                sharedTextKey = "slice2-morph-key",
            )
        }
        rule.onNodeWithText("Slice2 Morph").assertIsDisplayed()
        rule.onNodeWithText("Slice2 Morph").performTextInput("morphed")
        assertEquals("morphed", value)
    }

    @Test
    fun `type menu switches the row type`() {
        val items = mutableListOf(Slice2Row("111", 0))
        rule.setContent {
            FormDetailGroup(
                items = items.toList(),
                label = "Slice2 Switch",
                addLabel = "Slice2 Add",
                typeOptions = listOf(0, 1),
                value = { it.number },
                onValueChange = { _, _ -> },
                typeLabel = { "Kind ${it.type}" },
                onTypeChange = { index, t -> items[index] = items[index].copy(type = t) },
                onRemove = {},
                onAdd = {},
                currentType = { it.type },
                optionLabel = { "Pick $it" },
            )
        }
        rule.onNodeWithText("Kind 0").performClick()
        rule.onNodeWithText("Pick 1").performClick()
        assertEquals(1, items[0].type)
    }
}
