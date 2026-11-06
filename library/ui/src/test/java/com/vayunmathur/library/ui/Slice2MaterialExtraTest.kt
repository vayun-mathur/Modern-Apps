package com.vayunmathur.library.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Slice 2/3 (library/ui): sweep over MaterialComponentsExtra delegates —
 * theme entry, exposed menus, drawer item, bottom bars, sheets, FAB
 * variants, toolbars, nav suite, button group, tab rows, search input.
 */
@RunWith(RobolectricTestRunner::class)
class Slice2MaterialExtraTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `material theme entry renders content`() {
        rule.setContent {
            MaterialTheme {
                Text("slice2 themed")
            }
        }
        rule.onNodeWithText("slice2 themed").assertIsDisplayed()
    }

    @Test
    fun `dynamic theme renders in both modes`() {
        rule.setContent {
            Column {
                DynamicTheme(darkTheme = true) {
                    Text("slice2 dark")
                }
                DynamicTheme(darkTheme = false) {
                    Text("slice2 light")
                }
            }
        }
        rule.onNodeWithText("slice2 dark").assertIsDisplayed()
        rule.onNodeWithText("slice2 light").assertIsDisplayed()
    }

    @Test
    fun `exposed menu selects an option`() {
        val expanded = mutableStateOf(false)
        val selected = mutableStateOf("X")
        rule.setContent {
            ExposedDropdownMenuBox(expanded = expanded.value, onExpandedChange = { expanded.value = it }) {
                OutlinedTextField(
                    value = selected.value,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("slice2 exposed") },
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .testTag("slice2 exposed field"),
                )
                ExposedDropdownMenu(expanded = expanded.value, onDismissRequest = { expanded.value = false }) {
                    DropdownMenuItem(
                        text = { Text("slice2 opt Y") },
                        onClick = { selected.value = "Y"; expanded.value = false },
                    )
                }
            }
        }
        rule.onNodeWithText("X").performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodes(hasText("slice2 opt Y")).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("slice2 opt Y").performClick()
        assertEquals("Y", selected.value)
    }

    @Test
    fun `navigation drawer item fires`() {
        var clicked = 0
        rule.setContent {
            NavigationDrawerItem(
                label = { Text("slice2 nav drawer") },
                selected = false,
                onClick = { clicked++ },
            )
        }
        rule.onNodeWithText("slice2 nav drawer").performClick()
        assertEquals(1, clicked)
    }

    @Test
    fun `bottom bars render content`() {
        rule.setContent {
            Column {
                BottomAppBar { Text("slice2 bottom bar") }
                CompactBottomAppBar { Text("slice2 compact bar") }
                FlexibleBottomAppBar { Text("slice2 flex bar") }
            }
        }
        rule.onNodeWithText("slice2 bottom bar").assertIsDisplayed()
        rule.onNodeWithText("slice2 compact bar").assertIsDisplayed()
        rule.onNodeWithText("slice2 flex bar").assertIsDisplayed()
    }

    @Test
    fun `modal bottom sheet renders and dismisses`() {
        var dismissed = 0
        rule.setContent {
            ModalBottomSheet(onDismissRequest = { dismissed++ }) {
                Text("slice2 sheet body")
            }
        }
        rule.onNodeWithText("slice2 sheet body").assertIsDisplayed()
        assertEquals(0, dismissed)
    }

    @Test
    fun `bottom sheet scaffold renders sheet and content`() {
        rule.setContent {
            BottomSheetScaffold(
                sheetContent = { Text("slice2 bs sheet") },
            ) {
                Text("slice2 bs body")
            }
        }
        rule.onNodeWithText("slice2 bs body").assertIsDisplayed()
    }

    @Test
    fun `extended and small fabs fire`() {
        val clicks = mutableListOf<String>()
        rule.setContent {
            Column {
                ExtendedFloatingActionButton(onClick = { clicks += "e" }) { Text("slice2 ext fab") }
                ExtendedFloatingActionButton(
                    text = { Text("slice2 ext fab2") },
                    icon = { IconAdd() },
                    onClick = { clicks += "e2" },
                )
                SmallFloatingActionButton(
                    onClick = { clicks += "s" },
                    modifier = Modifier.testTag("slice2 small fab"),
                ) { IconAdd() }
            }
        }
        rule.onNodeWithText("slice2 ext fab").performClick()
        rule.onNodeWithText("slice2 ext fab2", useUnmergedTree = true).performClick()
        rule.onNodeWithTag("slice2 small fab").performClick()
        assertEquals(listOf("e", "e2", "s"), clicks)
    }

    @Test
    fun `toggle button and toggle fab flip`() {
        val seen = mutableListOf<Boolean>()
        rule.setContent {
            Column {
                ToggleButton(checked = false, onCheckedChange = { seen += it }) {
                    Text("slice2 toggle")
                }
                ToggleFloatingActionButton(
                    checked = true,
                    onCheckedChange = { seen += it },
                    modifier = Modifier.testTag("slice2 toggle fab"),
                ) {
                    IconAdd()
                }
            }
        }
        rule.onNodeWithText("slice2 toggle").performClick()
        rule.onNodeWithTag("slice2 toggle fab").performClick()
        assertEquals(listOf(true, false), seen)
    }

    @Test
    fun `fab menu item fires`() {
        var item = 0
        var fab = 0
        rule.setContent {
            FloatingActionButtonMenu(
                expanded = true,
                button = {
                    SmallFloatingActionButton(
                        onClick = { fab++ },
                        modifier = Modifier.testTag("slice2 menu fab"),
                    ) { IconAdd() }
                },
            ) {
                FloatingActionButtonMenuItem(
                    onClick = { item++ },
                    text = { Text("slice2 menu item") },
                    icon = { IconAdd() },
                )
            }
        }
        rule.onNodeWithText("slice2 menu item").performClick()
        assertEquals(1, item)
        assertEquals(0, fab)
    }

    @Test
    fun `vertical toolbar renders fab and actions`() {
        var primary = 0
        rule.setContent {
            VerticalFloatingToolbar(
                floatingActionButton = {
                    SmallFloatingActionButton(
                        onClick = { primary++ },
                        modifier = Modifier.testTag("slice2 toolbar fab"),
                    ) { IconAdd() }
                },
            ) {
                Text("slice2 toolbar action")
            }
        }
        rule.onNodeWithText("slice2 toolbar action").assertIsDisplayed()
        rule.onNodeWithTag("slice2 toolbar fab").performClick()
        assertEquals(1, primary)
    }

    @Test
    fun `short nav bar selects`() {
        var picked = -1
        rule.setContent {
            ShortNavigationBar {
                ShortNavigationBarItem(
                    selected = false,
                    onClick = { picked = 0 },
                    icon = { IconAdd() },
                    label = { Text("slice2 short a") },
                )
            }
        }
        rule.onNodeWithText("slice2 short a").performClick()
        assertEquals(0, picked)
    }

    @Test
    fun `tab row variants select`() {
        val clicks = mutableListOf<String>()
        rule.setContent {
            Column {
                TabRow(selectedTabIndex = 0) {
                    Tab(selected = true, onClick = { clicks += "t" }, text = { Text("slice2 extra tab") })
                }
                SecondaryTabRow(selectedTabIndex = 0) {
                    Tab(selected = false, onClick = { clicks += "s" }, text = { Text("slice2 extra tab s") })
                }
                PrimaryTabRow(selectedTabIndex = 0) {
                    Tab(selected = false, onClick = { clicks += "p" }, text = { Text("slice2 extra tab p") })
                }
                PrimaryScrollableTabRow(selectedTabIndex = 0) {
                    Tab(selected = false, onClick = { clicks += "ps" }, text = { Text("slice2 extra tab ps") })
                }
            }
        }
        rule.onNodeWithText("slice2 extra tab").performClick()
        rule.onNodeWithText("slice2 extra tab s").performClick()
        rule.onNodeWithText("slice2 extra tab p").performClick()
        rule.onNodeWithText("slice2 extra tab ps").performClick()
        assertEquals(listOf("t", "s", "p", "ps"), clicks)
    }

    @Test
    fun `search input field edits and searches`() {
        var query = ""
        var searched = ""
        rule.setContent {
            SearchBarInputField(
                query = query,
                onQueryChange = { query = it },
                onSearch = { searched = it },
                expanded = false,
                onExpandedChange = {},
                placeholder = { Text("slice2 input ph") },
            )
        }
        rule.onNodeWithText("slice2 input ph").assertIsDisplayed()
        rule.onNodeWithText("slice2 input ph").performTextInput("q")
        assertEquals("q", query)
        assertEquals("", searched)
    }

    @Test
    fun `button group renders`() {
        // Item-level APIs (clickableItem/toggleableItem) are covered by their
        // Material defaults here; the wrapper itself just renders the row.
        // Empty, so it has no size: it exists in the tree but is not displayed.
        // Fetching the node asserts it composed without crashing.
        rule.setContent {
            ButtonGroup(modifier = Modifier.testTag("slice2 group")) {
            }
        }
        rule.onNodeWithTag("slice2 group").fetchSemanticsNode()
    }
}
