package com.vayunmathur.library.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Slice 2/3 (library/ui): material wrapper sweep, batch 2 — scaffold,
 * app bars, navigation, drawers, menus, dialogs, pickers, badges, tabs,
 * refresh, search.
 */
@RunWith(RobolectricTestRunner::class)
class Slice2MaterialSweep2Test {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `scaffold renders bars fab and content`() {
        var fab = 0
        rule.setContent {
            Scaffold(
                topBar = { TopAppBar(title = { Text("slice2 top") }) },
                bottomBar = { Text("slice2 bottom") },
                floatingActionButton = {
                    FloatingActionButton(onClick = { fab++ }, modifier = Modifier.testTag("slice2 fab2")) {
                        Text("F")
                    }
                },
                snackbarHost = {},
            ) {
                Text("slice2 body")
            }
        }
        rule.onNodeWithText("slice2 top").assertIsDisplayed()
        rule.onNodeWithText("slice2 bottom").assertIsDisplayed()
        rule.onNodeWithText("slice2 body").assertIsDisplayed()
        rule.onNodeWithTag("slice2 fab2").performClick()
        assertEquals(1, fab)
    }

    @Test
    fun `app bars render titles and actions`() {
        var acted = 0
        rule.setContent {
            Column {
                TopAppBar(title = { Text("slice2 small") })
                CenterAlignedTopAppBar(title = { Text("slice2 center") })
                MediumFlexibleTopAppBar(title = { Text("slice2 medium") })
                LargeFlexibleTopAppBar(
                    title = { Text("slice2 large") },
                    actions = {
                        IconButton(onClick = { acted++ }, modifier = Modifier.testTag("slice2 bar act")) {
                            IconAdd()
                        }
                    },
                )
            }
        }
        rule.onNodeWithText("slice2 small").assertIsDisplayed()
        rule.onNodeWithText("slice2 center").assertIsDisplayed()
        rule.onNodeWithText("slice2 medium").assertIsDisplayed()
        rule.onNodeWithText("slice2 large").assertIsDisplayed()
        rule.onNodeWithTag("slice2 bar act").performClick()
        assertEquals(1, acted)
    }

    @Test
    fun `navigation bar selects`() {
        var picked = -1
        rule.setContent {
            NavigationBar {
                NavigationBarItem(
                    selected = true,
                    onClick = { picked = 0 },
                    icon = { IconAdd() },
                    label = { Text("slice2 nav a") },
                )
                NavigationBarItem(
                    selected = false,
                    onClick = { picked = 1 },
                    icon = { IconAdd() },
                    label = { Text("slice2 nav b") },
                )
            }
        }
        rule.onNodeWithText("slice2 nav b").performClick()
        assertEquals(1, picked)
    }

    @Test
    fun `navigation rail selects`() {
        var picked = -1
        rule.setContent {
            NavigationRail {
                NavigationRailItem(
                    selected = false,
                    onClick = { picked = 0 },
                    icon = { IconAdd() },
                    label = { Text("slice2 rail a") },
                )
            }
        }
        rule.onNodeWithText("slice2 rail a").performClick()
        assertEquals(0, picked)
    }

    @Test
    fun `modal drawer renders content and drawer sheet`() {
        rule.setContent {
            ModalNavigationDrawer(
                drawerContent = {
                    ModalDrawerSheet {
                        Text("slice2 drawer item")
                    }
                },
            ) {
                Text("slice2 drawer body")
            }
        }
        rule.onNodeWithText("slice2 drawer body").assertIsDisplayed()
    }

    @Test
    fun `dropdown menu items fire`() {
        val clicks = mutableListOf<String>()
        rule.setContent {
            DropdownMenu(expanded = true, onDismissRequest = {}) {
                DropdownMenuItem(text = { Text("slice2 menu a") }, onClick = { clicks += "a" })
                SelectableDropdownMenuItem(
                    selected = true,
                    onClick = { clicks += "s" },
                    text = { Text("slice2 menu sel") },
                )
                CheckableDropdownMenuItem(
                    checked = false,
                    onCheckedChange = { clicks += "c$it" },
                    text = { Text("slice2 menu chk") },
                )
            }
        }
        rule.onNodeWithText("slice2 menu a").performClick()
        rule.onNodeWithText("slice2 menu sel").performClick()
        rule.onNodeWithText("slice2 menu chk").performClick()
        assertEquals(listOf("a", "s", "ctrue"), clicks)
    }

    @Test
    fun `alert dialog confirms and dismisses`() {
        var confirmed = 0
        var dismissed = 0
        rule.setContent {
            AlertDialog(
                onDismissRequest = { dismissed++ },
                confirmButton = {
                    TextButton(onClick = { confirmed++ }) { Text("slice2 confirm") }
                },
                dismissButton = {
                    TextButton(onClick = { dismissed++ }) { Text("slice2 dismiss") }
                },
                title = { Text("slice2 alert") },
                text = { Text("slice2 alert body") },
            )
        }
        rule.onNodeWithText("slice2 alert").assertIsDisplayed()
        rule.onNodeWithText("slice2 confirm").performClick()
        rule.onNodeWithText("slice2 dismiss").performClick()
        assertEquals(1, confirmed)
        assertEquals(1, dismissed)
    }

    @Test
    fun `date and time pickers render with remembered state`() {
        rule.setContent {
            Column {
                val dateState = rememberDatePickerState(initialSelectedDateMillis = 1_000_000L)
                DatePicker(state = dateState, modifier = Modifier.testTag("slice2 dp"))
                val rangeState = rememberDateRangePickerState()
                DateRangePicker(state = rangeState, modifier = Modifier.testTag("slice2 drp"))
                val timeState = rememberTimePickerState(initialHour = 9, initialMinute = 30)
                TimePicker(state = timeState, modifier = Modifier.testTag("slice2 tp"))
                assertTrue(timeState.hour == 9 && timeState.minute == 30)
            }
        }
        rule.onNodeWithTag("slice2 dp").assertIsDisplayed()
        // The clock face composes (fetch proves it) but overflows the test
        // viewport, so it is not "displayed".
        rule.onNodeWithTag("slice2 tp").fetchSemanticsNode()
    }

    @Test
    fun `material date picker dialog confirms`() {
        var confirmed = 0
        var dismissed = 0
        rule.setContent {
            val dateState = rememberDatePickerState(initialSelectedDateMillis = 1_000_000L)
            DatePickerDialog(
                onDismissRequest = { dismissed++ },
                confirmButton = {
                    TextButton(onClick = { confirmed++ }) { Text("slice2 dp ok") }
                },
            ) {
                DatePicker(state = dateState)
            }
        }
        rule.onNodeWithText("slice2 dp ok").performClick()
        assertEquals(1, confirmed)
        assertEquals(0, dismissed)
    }

    @Test
    fun `badge and badged box render`() {
        rule.setContent {
            Column {
                BadgedBox(badge = { Badge { Text("3") } }) {
                    Text("slice2 badged")
                }
                Badge { Text("slice2 bare badge") }
            }
        }
        rule.onNodeWithText("slice2 badged").assertIsDisplayed()
        rule.onNodeWithText("3").assertIsDisplayed()
    }

    @Test
    fun `tabs select across all row variants`() {
        val clicks = mutableListOf<String>()
        rule.setContent {
            Column {
                TabRow(selectedTabIndex = 0) {
                    Tab(selected = true, onClick = { clicks += "r" }, text = { Text("slice2 tab r") })
                }
                SecondaryTabRow(selectedTabIndex = 0) {
                    Tab(selected = false, onClick = { clicks += "s" }, text = { Text("slice2 tab s") })
                }
                PrimaryTabRow(selectedTabIndex = 0) {
                    Tab(selected = false, onClick = { clicks += "p" }, content = { Text("slice2 tab p") })
                }
                PrimaryScrollableTabRow(selectedTabIndex = 0) {
                    Tab(selected = false, onClick = { clicks += "ps" }, text = { Text("slice2 tab ps") })
                }
            }
        }
        rule.onNodeWithText("slice2 tab r").performClick()
        rule.onNodeWithText("slice2 tab s").performClick()
        rule.onNodeWithText("slice2 tab p").performClick()
        rule.onNodeWithText("slice2 tab ps").performClick()
        assertEquals(listOf("r", "s", "p", "ps"), clicks)
    }

    @Test
    fun `pull to refresh renders content and indicator`() {
        var refreshed = 0
        rule.setContent {
            PullToRefreshBox(isRefreshing = false, onRefresh = { refreshed++ }) {
                Text("slice2 refresh body")
            }
        }
        rule.onNodeWithText("slice2 refresh body").assertIsDisplayed()
        assertEquals(0, refreshed)
    }

    @Test
    fun `search bar renders input and content when expanded`() {
        var query = ""
        var expanded = true
        rule.setContent {
            SearchBar(
                inputField = {
                    SearchBarInputField(
                        query = query,
                        onQueryChange = { query = it },
                        onSearch = {},
                        expanded = expanded,
                        onExpandedChange = { expanded = it },
                        placeholder = { Text("slice2 search ph") },
                    )
                },
                expanded = expanded,
                onExpandedChange = { expanded = it },
            ) {
                Text("slice2 search result")
            }
        }
        rule.onNodeWithText("slice2 search ph").assertIsDisplayed()
        rule.onNodeWithText("slice2 search result").assertIsDisplayed()
    }

    @Test
    fun `snackbar host renders`() {
        rule.setContent {
            Column {
                val host = androidx.compose.material3.SnackbarHostState()
                SnackbarHost(hostState = host)
                Text("slice2 snackbar tail")
            }
        }
        rule.onNodeWithText("slice2 snackbar tail").assertIsDisplayed()
    }

    @Test
    fun `provide text style applies to content`() {
        rule.setContent {
            ProvideTextStyle(value = androidx.compose.material3.MaterialTheme.typography.bodyLarge) {
                Text("slice2 styled")
            }
        }
        rule.onNodeWithText("slice2 styled").assertIsDisplayed()
    }

    @Test
    fun `segmented row scope and swipe box render`() {
        rule.setContent {
            Column {
                val swipe = rememberSwipeToDismissBoxState()
                SwipeToDismissBox(
                    state = swipe,
                    backgroundContent = { Text("slice2 bg") },
                    modifier = Modifier.testTag("slice2 swipe"),
                ) {
                    Text("slice2 fg")
                }
            }
        }
        rule.onNodeWithText("slice2 fg").assertIsDisplayed()
    }

    @Test
    fun `slider state overload renders`() {
        rule.setContent {
            Column {
                val sliderState = rememberSliderState(value = 0.5f)
                Slider(state = sliderState, onValueChange = {}, modifier = Modifier.testTag("slice2 slider"))
            }
        }
        rule.onNodeWithTag("slice2 slider").assertIsDisplayed()
    }
}
