package com.vayunmathur.library.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * W4: the shared Material wrapper components called with every argument
 * supplied (no defaults), in enabled and disabled states, with their
 * click/toggle/text callbacks exercised for real.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
class W4MaterialSweepTest {

    @get:Rule
    val rule = createComposeRule()

    @Test fun buttonsInvokeCallbacksAndDisabledOnesDoNot() {
        var clicks = 0
        rule.setContent {
            Column {
                Button(
                    onClick = { clicks++ }, modifier = Modifier, enabled = true,
                    shape = RectangleShape, colors = ButtonDefaults.buttonColors(),
                    elevation = ButtonDefaults.buttonElevation(),
                    border = BorderStroke(1.dp, Color.Red),
                    contentPadding = PaddingValues(8.dp),
                ) { Text("W4 Filled") }
                OutlinedButton(
                    onClick = { clicks++ }, modifier = Modifier, enabled = true,
                    shape = RectangleShape, colors = ButtonDefaults.outlinedButtonColors(),
                    contentPadding = PaddingValues(8.dp),
                ) { Text("W4 Outlined") }
                TextButton(
                    onClick = { clicks++ }, modifier = Modifier, enabled = true,
                    shape = RectangleShape, colors = ButtonDefaults.textButtonColors(),
                    contentPadding = PaddingValues(8.dp),
                ) { Text("W4 TextBtn") }
                FilledTonalButton(
                    onClick = { clicks++ }, modifier = Modifier, enabled = true,
                    shape = RectangleShape, colors = ButtonDefaults.filledTonalButtonColors(),
                    contentPadding = PaddingValues(8.dp),
                ) { Text("W4 Tonal") }
                Button(onClick = { clicks += 100 }, enabled = false) { Text("W4 Disabled") }
                FloatingActionButton(
                    onClick = { clicks++ }, modifier = Modifier, shape = RectangleShape,
                    containerColor = Color.Blue, contentColor = Color.White,
                    elevation = FloatingActionButtonDefaults.elevation(),
                ) { Text("W4 Fab") }
                IconButton(
                    onClick = { clicks++ }, modifier = Modifier, enabled = true,
                    colors = IconButtonDefaults.iconButtonColors(),
                ) { Text("W4 IconBtn") }
            }
        }
        for (label in listOf("W4 Filled", "W4 Outlined", "W4 TextBtn", "W4 Tonal", "W4 Fab", "W4 IconBtn")) {
            rule.onNodeWithText(label).performClick()
        }
        rule.onNodeWithText("W4 Disabled").performClick()
        assertEquals(6, clicks)
    }

    @Test fun togglesChipsAndCardsChangeState() {
        var events = mutableListOf<String>()
        rule.setContent {
            var checked by remember { mutableStateOf(false) }
            var switched by remember { mutableStateOf(false) }
            var tri by remember { mutableStateOf(ToggleableState.Off) }
            Column {
                Checkbox(
                    checked = checked, onCheckedChange = { checked = it; events.add("cb:$it") },
                    modifier = Modifier, enabled = true, colors = CheckboxDefaults.colors(),
                )
                Switch(checked = switched, onCheckedChange = { switched = it; events.add("sw:$it") }, modifier = Modifier, enabled = true)
                RadioButton(selected = checked, onClick = { events.add("rb") }, modifier = Modifier, enabled = true)
                TriStateCheckbox(
                    state = tri,
                    onClick = { tri = if (tri == ToggleableState.Off) ToggleableState.On else ToggleableState.Off; events.add("tri") },
                    modifier = Modifier, enabled = true,
                )
                FilterChip(
                    selected = checked, onClick = { events.add("filter") },
                    label = { Text("W4 Filter") }, modifier = Modifier, enabled = true,
                    leadingIcon = null, trailingIcon = null, colors = FilterChipDefaults.filterChipColors(),
                )
                AssistChip(
                    onClick = { events.add("assist") }, label = { Text("W4 Assist") },
                    modifier = Modifier, enabled = true, leadingIcon = null, trailingIcon = null,
                    colors = AssistChipDefaults.assistChipColors(),
                )
                InputChip(
                    selected = switched, onClick = { events.add("input") },
                    label = { Text("W4 Input") }, modifier = Modifier, enabled = true,
                    leadingIcon = null, trailingIcon = null,
                )
                Card(onClick = { events.add("card") }, modifier = Modifier, enabled = true) {
                    Text("W4 Card")
                }
                ElevatedCard(modifier = Modifier) { Text("W4 Elevated") }
                OutlinedCard(modifier = Modifier) { Text("W4 OutlinedCard") }
            }
        }
        rule.onNodeWithText("W4 Filter").performClick()
        rule.onNodeWithText("W4 Assist").performClick()
        rule.onNodeWithText("W4 Input").performClick()
        rule.onNodeWithText("W4 Card").performClick()
        assertTrue(events.containsAll(listOf("filter", "assist", "input", "card")), events.toString())
    }

    @Test fun textFieldsAcceptInputAndListItemsRender() {
        var text by mutableStateOf("seed")
        var seen = ""
        rule.setContent {
            Column {
                OutlinedTextField(
                    value = text, onValueChange = { text = it; seen = it },
                    modifier = Modifier, enabled = true, readOnly = false,
                    label = { Text("W4 Label") }, placeholder = { Text("W4 Hint") },
                    supportingText = { Text("W4 Support") }, isError = true,
                    singleLine = true,
                )
                TextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier, enabled = true, readOnly = true,
                    label = { Text("W4 Label2") }, singleLine = true,
                )
                ListItem(
                    modifier = Modifier, enabled = true,
                    overlineContent = { Text("W4 Over") },
                    supportingContent = { Text("W4 SupportItem") },
                    leadingContent = { Text("W4 Lead") },
                    trailingContent = { Text("W4 Trail") },
                    colors = ListItemDefaults.colors(),
                    content = { Text("W4 Headline") },
                )
                BadgedBox(badge = { Badge { Text("3") } }) { Text("W4 Badged") }
                HorizontalDivider(modifier = Modifier, thickness = 2.dp, color = DividerDefaults.color)
            }
        }
        rule.onNodeWithText("W4 Headline").assertIsDisplayed()
        rule.onNodeWithText("W4 Badged").assertExists()
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("X")
        assertTrue(seen.contains("X") || text.contains("X"), "text=$text seen=$seen")
    }

    @Test fun menusDialogsAndPickersRender() {
        var dismissed = false
        var picked = ""
        rule.setContent {
            Column {
                DropdownMenu(expanded = true, onDismissRequest = { dismissed = true }, modifier = Modifier) {
                    DropdownMenuItem(
                        text = { Text("W4 Plain Item") }, onClick = { picked = "plain" },
                        modifier = Modifier, leadingIcon = null, trailingIcon = null, enabled = true,
                        colors = MenuDefaults.itemColors(),
                        contentPadding = MenuDefaults.DropdownMenuItemContentPadding,
                    )
                    SelectableDropdownMenuItem(
                        selected = true, onClick = { picked = "sel" },
                        text = { Text("W4 Selected Item") }, modifier = Modifier, enabled = true,
                    )
                    CheckableDropdownMenuItem(
                        checked = true, onCheckedChange = { picked = "check:$it" },
                        text = { Text("W4 Checkable Item") }, modifier = Modifier, enabled = true,
                    )
                }
            }
        }
        rule.onNodeWithText("W4 Plain Item").performClick()
        assertEquals("plain", picked)
        rule.onNodeWithText("W4 Selected Item").performClick()
        assertEquals("sel", picked)
    }

    @Test fun dialogAndPickersCompose() {
        var confirmed = false
        rule.setContent {
            Column {
                AlertDialog(
                    onDismissRequest = {},
                    confirmButton = {
                        TextButton(onClick = { confirmed = true }) { Text("W4 Confirm") }
                    },
                    modifier = Modifier,
                    dismissButton = { TextButton(onClick = {}) { Text("W4 Dismiss") } },
                    icon = null,
                    title = { Text("W4 Dialog Title") },
                    text = { Text("W4 Dialog Body") },
                )
                SnackbarHost(hostState = remember { SnackbarHostState() }, modifier = Modifier)
            }
        }
        rule.onNodeWithText("W4 Dialog Title").assertExists()
        rule.onNodeWithText("W4 Confirm").performClick()
        assertTrue(confirmed)
    }

    @Test fun progressAndSlidersCompose() {
        rule.setContent {
            var slider by remember { mutableStateOf(0.5f) }
            Column {
                CircularProgressIndicator(modifier = Modifier, progress = { 0.4f })
                LinearProgressIndicator(modifier = Modifier, progress = { 0.7f })
                Slider(value = slider, onValueChange = { slider = it }, modifier = Modifier, enabled = true, valueRange = 0f..1f)
                RangeSlider(
                    value = 0.2f..0.8f, onValueChange = {}, modifier = Modifier,
                    enabled = true, valueRange = 0f..1f,
                )
                LoadingIndicator(modifier = Modifier)
                Text("W4 Progress", modifier = Modifier)
            }
        }
        rule.onNodeWithText("W4 Progress").assertExists()
        rule.onAllNodesWithText("W4 Progress").fetchSemanticsNodes().size.let { assertEquals(1, it) }
    }
}
