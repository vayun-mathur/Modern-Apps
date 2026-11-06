package com.vayunmathur.library.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.util.LocalHostScaffoldInfo
import com.vayunmathur.library.util.LocalSnackbarHostState
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Slice 2/3 (library/ui): non-default argument paths and the branches the
 * sweep leaves out — painter icons, explicit styling args, the hosted
 * Scaffold branch, nav suite, and the indicator/divider tab row.
 */
@RunWith(RobolectricTestRunner::class)
class Slice2MaterialArgsTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `icon renders from a painter`() {
        rule.setContent {
            val painter = rememberVectorPainter(image = Icons.Filled.Add)
            Icon(painter = painter, contentDescription = "slice2 painted")
        }
        rule.onNodeWithContentDescription("slice2 painted").assertIsDisplayed()
    }

    @Test
    fun `text honors explicit styling args`() {
        rule.setContent {
            Column {
                Text(
                    text = "slice2 styled text",
                    color = Color.Red,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                )
                Text(
                    text = AnnotatedString("slice2 styled annotated"),
                    color = Color.Blue,
                    fontWeight = FontWeight.Light,
                    maxLines = 1,
                )
            }
        }
        rule.onNodeWithText("slice2 styled text").assertIsDisplayed()
        rule.onNodeWithText("slice2 styled annotated").assertIsDisplayed()
    }

    @Test
    fun `buttons honor explicit styling args`() {
        val clicks = mutableListOf<String>()
        rule.setContent {
            Column {
                Button(
                    onClick = { clicks += "b" },
                    border = BorderStroke(1.dp, Color.Red),
                    content = { Text("slice2 bordered") },
                )
                OutlinedButton(onClick = { clicks += "o" }, enabled = true) {
                    Text("slice2 obtn")
                }
                FloatingActionButton(
                    onClick = { clicks += "f" },
                    containerColor = Color.Green,
                    modifier = Modifier.testTag("slice2 fab arg"),
                ) { Text("F2") }
            }
        }
        rule.onNodeWithText("slice2 bordered").performClick()
        rule.onNodeWithText("slice2 obtn").performClick()
        rule.onNodeWithTag("slice2 fab arg").performClick()
        assertEquals(listOf("b", "o", "f"), clicks)
    }

    @Test
    fun `cards and surfaces honor explicit args`() {
        var clicked = 0
        rule.setContent {
            Column {
                Card(
                    border = BorderStroke(1.dp, Color.Blue),
                    content = { Text("slice2 card border") },
                )
                ElevatedCard(
                    onClick = { clicked++ },
                    enabled = true,
                    content = { Text("slice2 ecard arg") },
                )
                Surface(
                    tonalElevation = 2.dp,
                    shadowElevation = 2.dp,
                    border = BorderStroke(1.dp, Color.Gray),
                    content = { Text("slice2 surface arg") },
                )
            }
        }
        rule.onNodeWithText("slice2 card border").assertIsDisplayed()
        rule.onNodeWithText("slice2 surface arg").assertIsDisplayed()
        rule.onNodeWithText("slice2 ecard arg").performClick()
        assertEquals(1, clicked)
    }

    @Test
    fun `app bars honor colors and scroll behavior`() {
        rule.setContent {
            Column {
                val behavior = appBarScrollBehavior(AppBarSize.Small)
                TopAppBar(
                    title = { Text("slice2 bar colors") },
                    navigationIcon = { IconAdd() },
                    scrollBehavior = behavior,
                )
                CenterAlignedTopAppBar(
                    title = { Text("slice2 center colors") },
                    scrollBehavior = behavior,
                )
            }
        }
        rule.onNodeWithText("slice2 bar colors").assertIsDisplayed()
        rule.onNodeWithText("slice2 center colors").assertIsDisplayed()
    }

    @Test
    fun `hosted scaffold renders content without a snackbar state`() {
        rule.setContent {
            CompositionLocalProvider(LocalHostScaffoldInfo provides true) {
                Scaffold(
                    topBar = { TopAppBar(title = { Text("slice2 hosted") }) },
                ) {
                    Text("slice2 hosted body")
                }
            }
        }
        rule.onNodeWithText("slice2 hosted").assertIsDisplayed()
        rule.onNodeWithText("slice2 hosted body").assertIsDisplayed()
    }

    @Test
    fun `hosted scaffold with a global snackbar state resolves the default host`() {
        rule.setContent {
            val snackbar = androidx.compose.material3.SnackbarHostState()
            CompositionLocalProvider(
                LocalHostScaffoldInfo provides true,
                LocalSnackbarHostState provides snackbar,
            ) {
                Scaffold {
                    Text("slice2 hosted snackbar body")
                }
            }
        }
        rule.onNodeWithText("slice2 hosted snackbar body").assertIsDisplayed()
    }

    @Test
    fun `tab rows honor explicit colors`() {
        val clicks = mutableListOf<String>()
        rule.setContent {
            Column {
                TabRow(selectedTabIndex = 0, containerColor = Color.Red, contentColor = Color.White) {
                    Tab(selected = true, onClick = { clicks += "t" }, text = { Text("slice2 tab c") })
                }
                SecondaryTabRow(selectedTabIndex = 0, containerColor = Color.Blue, contentColor = Color.White) {
                    Tab(selected = false, onClick = { clicks += "s" }, text = { Text("slice2 stab c") })
                }
                PrimaryTabRow(selectedTabIndex = 0, containerColor = Color.Green, contentColor = Color.White) {
                    Tab(selected = false, onClick = { clicks += "p" }, text = { Text("slice2 ptab c") })
                }
                PrimaryScrollableTabRow(selectedTabIndex = 0) {
                    Tab(selected = false, onClick = { clicks += "ps" }, text = { Text("slice2 pst c") })
                }
            }
        }
        rule.onNodeWithText("slice2 tab c").performClick()
        rule.onNodeWithText("slice2 stab c").performClick()
        rule.onNodeWithText("slice2 ptab c").performClick()
        rule.onNodeWithText("slice2 pst c").performClick()
        assertEquals(listOf("t", "s", "p", "ps"), clicks)
    }

    @Test
    fun `indicator tab row renders custom indicator and divider`() {
        var clicked = 0
        rule.setContent {
            SecondaryTabRow(
                selectedTabIndex = 0,
                indicator = { Text("slice2 indicator") },
                divider = { HorizontalDivider() },
            ) {
                Tab(selected = true, onClick = { clicked++ }, text = { Text("slice2 itab") })
            }
        }
        rule.onNodeWithText("slice2 indicator").assertIsDisplayed()
        rule.onNodeWithText("slice2 itab").performClick()
        assertEquals(1, clicked)
    }

    @Test
    fun `navigation suite scaffold renders items and content`() {
        var picked = -1
        rule.setContent {
            NavigationSuiteScaffold(
                navigationSuiteItems = {
                    item(
                        selected = true,
                        onClick = { picked = 0 },
                        icon = { IconAdd() },
                        label = { Text("slice2 suite a") },
                    )
                    item(
                        selected = false,
                        onClick = { picked = 1 },
                        icon = { IconAdd() },
                        label = { Text("slice2 suite b") },
                    )
                },
            ) {
                Text("slice2 suite body")
            }
        }
        rule.onNodeWithText("slice2 suite body").assertIsDisplayed()
        rule.onNodeWithText("slice2 suite b").performClick()
        assertEquals(1, picked)
    }

    @Test
    fun `modal sheet and sheet scaffold honor explicit args`() {
        rule.setContent {
            Column {
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ModalBottomSheet(
                    onDismissRequest = {},
                    sheetState = sheetState,
                    containerColor = Color.White,
                ) {
                    Text("slice2 modal arg")
                }
            }
        }
        rule.onNodeWithText("slice2 modal arg").assertIsDisplayed()
    }

    @Test
    fun `bottom sheet scaffold honors explicit args`() {
        rule.setContent {
            val scaffoldState = rememberBottomSheetScaffoldState()
            BottomSheetScaffold(
                sheetContent = { Text("slice2 bss sheet") },
                scaffoldState = scaffoldState,
                sheetPeekHeight = 64.dp,
                sheetSwipeEnabled = false,
                sheetDragHandle = null,
                topBar = { Text("slice2 bss top") },
            ) {
                Text("slice2 bss body")
            }
        }
        rule.onNodeWithText("slice2 bss top").assertIsDisplayed()
        rule.onNodeWithText("slice2 bss body").assertIsDisplayed()
    }

    @Test
    fun `material theme entry honors explicit args`() {
        rule.setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(primary = Color.Red),
                shapes = androidx.compose.material3.Shapes(),
                typography = androidx.compose.material3.Typography(),
            ) {
                Text("slice2 theme args")
            }
        }
        rule.onNodeWithText("slice2 theme args").assertIsDisplayed()
    }

    @Test
    fun `fields honor shapes and supporting content`() {
        var s = "12"
        rule.setContent {
            Column {
                OutlinedTextField(
                    value = s,
                    onValueChange = { s = it },
                    label = { Text("slice2 shape field") },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                    prefix = { Text("slice2 prefix") },
                    suffix = { Text("slice2 suffix") },
                )
            }
        }
        rule.onNodeWithText("slice2 prefix").assertIsDisplayed()
        rule.onNodeWithText("slice2 suffix").assertIsDisplayed()
    }

    @Test
    fun `drawer item and bottom bar honor args`() {
        var clicked = 0
        rule.setContent {
            Column {
                NavigationDrawerItem(
                    label = { Text("slice2 ndi") },
                    selected = true,
                    onClick = { clicked++ },
                    icon = { IconAdd() },
                    badge = { Text("slice2 badge") },
                )
                BottomAppBar(contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp)) {
                    Text("slice2 bab")
                }
            }
        }
        rule.onNodeWithText("slice2 ndi").performClick()
        rule.onNodeWithText("slice2 badge").assertIsDisplayed()
        rule.onNodeWithText("slice2 bab").assertIsDisplayed()
        assertEquals(1, clicked)
    }
}
