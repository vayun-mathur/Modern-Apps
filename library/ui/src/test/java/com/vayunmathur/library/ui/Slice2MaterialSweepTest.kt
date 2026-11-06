package com.vayunmathur.library.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Slice 2/3 (library/ui): sweep over the thin Material 3 wrapper delegates —
 * each renders through to Material and forwards its handlers. Batch 1: text,
 * icon, buttons, surfaces, cards, chips, selection controls, dividers,
 * progress, sliders, fields.
 */
@RunWith(RobolectricTestRunner::class)
class Slice2MaterialSweepTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `text renders both string and annotated forms`() {
        rule.setContent {
            Column {
                Text("slice2 plain")
                Text(AnnotatedString("slice2 annotated"))
            }
        }
        rule.onNodeWithText("slice2 plain").assertIsDisplayed()
        rule.onNodeWithText("slice2 annotated").assertIsDisplayed()
    }

    @Test
    fun `icon renders from an image vector`() {
        rule.setContent {
            Icon(Icons.Filled.Add, contentDescription = "slice2 plus")
        }
        rule.onNodeWithContentDescription("slice2 plus").assertIsDisplayed()
    }

    @Test
    fun `icon buttons fire`() {
        var plain = 0
        var filled = 0
        var tonal = 0
        rule.setContent {
            Column {
                IconButton(onClick = { plain++ }, modifier = Modifier.testTag("slice2 icon plain")) { IconAdd() }
                FilledIconButton(onClick = { filled++ }, modifier = Modifier.testTag("slice2 icon filled")) { IconAdd() }
                FilledTonalIconButton(onClick = { tonal++ }, modifier = Modifier.testTag("slice2 icon tonal")) { IconAdd() }
            }
        }
        rule.onNodeWithTag("slice2 icon plain").performClick()
        rule.onNodeWithTag("slice2 icon filled").performClick()
        rule.onNodeWithTag("slice2 icon tonal").performClick()
        assertEquals(1, plain)
        assertEquals(1, filled)
        assertEquals(1, tonal)
    }

    @Test
    fun `buttons fire and show their labels`() {
        val clicks = mutableListOf<String>()
        rule.setContent {
            Column {
                Button(onClick = { clicks += "b" }) { Text("slice2 button") }
                OutlinedButton(onClick = { clicks += "o" }) { Text("slice2 outlined") }
                TextButton(onClick = { clicks += "t" }) { Text("slice2 textbtn") }
                FilledTonalButton(onClick = { clicks += "f" }) { Text("slice2 tonal") }
            }
        }
        rule.onNodeWithText("slice2 button").performClick()
        rule.onNodeWithText("slice2 outlined").performClick()
        rule.onNodeWithText("slice2 textbtn").performClick()
        rule.onNodeWithText("slice2 tonal").performClick()
        assertEquals(listOf("b", "o", "t", "f"), clicks)
    }

    @Test
    fun `fab fires and shows its content`() {
        var clicked = 0
        rule.setContent {
            FloatingActionButton(onClick = { clicked++ }) { Text("slice2 fab") }
        }
        rule.onNodeWithText("slice2 fab").performClick()
        assertEquals(1, clicked)
    }

    @Test
    fun `surfaces render content and the clickable one fires`() {
        var clicked = 0
        rule.setContent {
            Column {
                Surface { Text("slice2 surface") }
                Surface(onClick = { clicked++ }) { Text("slice2 clickable") }
            }
        }
        rule.onNodeWithText("slice2 surface").assertIsDisplayed()
        rule.onNodeWithText("slice2 clickable").performClick()
        assertEquals(1, clicked)
    }

    @Test
    fun `cards render content and the clickable ones fire`() {
        val clicks = mutableListOf<String>()
        rule.setContent {
            Column {
                Card { Text("slice2 card") }
                Card(onClick = { clicks += "c" }) { Text("slice2 card click") }
                ElevatedCard { Text("slice2 elevated") }
                ElevatedCard(onClick = { clicks += "e" }) { Text("slice2 elevated click") }
                OutlinedCard { Text("slice2 outlined card") }
            }
        }
        rule.onNodeWithText("slice2 card").assertIsDisplayed()
        rule.onNodeWithText("slice2 elevated").assertIsDisplayed()
        rule.onNodeWithText("slice2 outlined card").assertIsDisplayed()
        rule.onNodeWithText("slice2 card click").performClick()
        rule.onNodeWithText("slice2 elevated click").performClick()
        assertEquals(listOf("c", "e"), clicks)
    }

    @Test
    fun `chips fire and show their labels`() {
        val clicks = mutableListOf<String>()
        rule.setContent {
            Column {
                InputChip(selected = true, onClick = { clicks += "i" }, label = { Text("slice2 input") })
                FilterChip(selected = false, onClick = { clicks += "f" }, label = { Text("slice2 filter") })
                AssistChip(onClick = { clicks += "a" }, label = { Text("slice2 assist") })
            }
        }
        rule.onNodeWithText("slice2 input").performClick()
        rule.onNodeWithText("slice2 filter").performClick()
        rule.onNodeWithText("slice2 assist").performClick()
        assertEquals(listOf("i", "f", "a"), clicks)
    }

    @Test
    fun `checkbox switch and radio forward their toggles`() {
        val seen = mutableListOf<Boolean>()
        rule.setContent {
            Column {
                Checkbox(checked = false, onCheckedChange = { seen += it ?: false }, modifier = Modifier.testTag("slice2 check"))
                Switch(checked = true, onCheckedChange = { seen += it }, modifier = Modifier.testTag("slice2 switch"))
                RadioButton(selected = false, onClick = { seen += true }, modifier = Modifier.testTag("slice2 radio"))
            }
        }
        rule.onNodeWithTag("slice2 check").performClick()
        rule.onNodeWithTag("slice2 switch").performClick()
        rule.onNodeWithTag("slice2 radio").performClick()
        assertEquals(listOf(true, false, true), seen)
    }

    @Test
    fun `tri-state checkbox fires`() {
        var clicked = 0
        rule.setContent {
            TriStateCheckbox(
                state = androidx.compose.ui.state.ToggleableState.Indeterminate,
                onClick = { clicked++ },
                modifier = Modifier.testTag("slice2 tri"),
            )
        }
        rule.onNodeWithTag("slice2 tri").performClick()
        assertEquals(1, clicked)
    }

    @Test
    fun `dividers render between content`() {
        rule.setContent {
            Column {
                Text("slice2 above")
                HorizontalDivider()
                Text("slice2 below")
                VerticalDivider()
            }
        }
        rule.onNodeWithText("slice2 above").assertIsDisplayed()
        rule.onNodeWithText("slice2 below").assertIsDisplayed()
    }

    @Test
    fun `progress indicators render`() {
        rule.setContent {
            Column {
                CircularProgressIndicator()
                CircularProgressIndicator(progress = { 0.5f })
                LinearProgressIndicator()
                LinearProgressIndicator(progress = { 0.25f })
                LoadingIndicator()
                LoadingIndicator(progress = { 0.75f })
                ContainedLoadingIndicator()
                ContainedLoadingIndicator(progress = { 0.1f })
                Text("slice2 progress tail")
            }
        }
        rule.onNodeWithText("slice2 progress tail").assertIsDisplayed()
    }

    @Test
    fun `sliders render and report changes`() {
        var value = 0f
        var range = 0f..1f
        var stateValue = -1f
        rule.setContent {
            Column {
                Slider(value = value, onValueChange = { value = it })
                RangeSlider(value = range, onValueChange = { range = it })
                val sliderState = rememberSliderState(value = 0.3f)
                Slider(state = sliderState, onValueChange = { stateValue = it })
                Text("slice2 sliders tail")
            }
        }
        rule.onNodeWithText("slice2 sliders tail").assertIsDisplayed()
    }

    @Test
    fun `text fields edit both string and value forms`() {
        var s = ""
        var filled = androidx.compose.ui.text.input.TextFieldValue("a")
        var outlined = androidx.compose.ui.text.input.TextFieldValue("b")
        rule.setContent {
            Column {
                OutlinedTextField(value = s, onValueChange = { s = it }, label = { Text("slice2 out") })
                TextField(value = s, onValueChange = { s = it }, label = { Text("slice2 filled") })
                OutlinedTextField(value = outlined, onValueChange = { outlined = it }, label = { Text("slice2 out v") })
                TextField(value = filled, onValueChange = { filled = it }, label = { Text("slice2 filled v") })
            }
        }
        rule.onNodeWithText("slice2 out").performTextInput("x")
        assertEquals("x", s)
    }

    @Test
    fun `segmented row selects`() {
        var clicked = 0
        rule.setContent {
            SingleChoiceSegmentedButtonRow {
                SegmentedButton(
                    selected = true,
                    onClick = { clicked++ },
                    shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(0, 2),
                    label = { Text("slice2 seg") },
                )
            }
        }
        rule.onNodeWithText("slice2 seg").performClick()
        assertEquals(1, clicked)
    }

    @Test
    fun `list items render both overloads`() {
        var clicked = 0
        rule.setContent {
            Column {
                ListItem(headlineContent = { Text("slice2 headline") })
                ListItem(content = { Text("slice2 interactive") })
                ListItem(headlineContent = { Text("slice2 unused") }, tonalElevation = 0.dp, shadowElevation = 0.dp)
            }
        }
        rule.onNodeWithText("slice2 headline").assertIsDisplayed()
        rule.onNodeWithText("slice2 interactive").assertIsDisplayed()
        assertEquals(0, clicked)
    }
}
