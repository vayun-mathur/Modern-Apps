package com.vayunmathur.calculator.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.calculator.Route
import com.vayunmathur.calculator.domain.AngleMode
import com.vayunmathur.calculator.platform.CalculatorActions
import com.vayunmathur.calculator.platform.CalculatorUiState
import com.vayunmathur.calculator.platform.GraphTabColors
import com.vayunmathur.calculator.domain.FeatureKind
import com.vayunmathur.calculator.platform.GraphActions
import com.vayunmathur.calculator.platform.GraphFunction
import com.vayunmathur.calculator.platform.GraphMarker
import com.vayunmathur.calculator.domain.GraphPoint
import com.vayunmathur.calculator.platform.GraphUiState
import com.vayunmathur.calculator.platform.GraphViewport
import com.vayunmathur.calculator.platform.HistoryEntry
import com.vayunmathur.calculator.platform.UnitConverterActions
import com.vayunmathur.calculator.platform.UnitConverterUiState
import com.vayunmathur.calculator.domain.UnitRegistry
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.IconCalculate
import com.vayunmathur.library.ui.IconFunctions
import com.vayunmathur.library.ui.IconRuler
import com.vayunmathur.library.ui.NavigationRail
import com.vayunmathur.library.ui.NavigationRailItem
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.isCompactHeight
import com.vayunmathur.library.ui.isExpandedWidth
import com.vayunmathur.library.util.BottomBarItem
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.SiblingPage
import com.vayunmathur.library.util.rememberNavBackStack

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/** Desktop-wide (1280dp >= 840dp), so isExpandedWidth() side-by-side branches render. */
private const val EXPANDED = "spec:width=1280dp,height=800dp,dpi=240"

/**
 * Store listing images for `:calculator`, rendered from Compose previews instead of from
 * an instrumented test on a device.
 *
 * `./gradlew :calculator:metadata` renders these and copies the PNGs into
 * per-bucket `metadata_data/photos/calculator/<bucket>/` dirs (`phone/` for the
 * handset previews, `large-tablet/` for the expanded-layout preview — Play has no
 * desktop bucket and large-tablet is the closest Triple-T type), where the release
 * build picks them up.
 *
 * Two things to keep in mind when editing:
 *
 *  - Order matters, and it comes from the function names. The generated PNG filenames
 *    embed the function name, so `Preview1Keypad`/`Preview2Graph`/... sort into listing
 *    order no matter how the plugin formats the rest of the filename. Renumber the
 *    functions if you reorder the listing. Order is per bucket.
 *  - Each preview needs a `group` (the form-factor bucket: `phone`, `tablet`,
 *    `large-tablet`, `tv`, `wear`) as well as @PreviewTest.
 *  - Everything must be a literal. These render with no ViewModel, no database and no
 *    device, so the state below is the whole input — which is also what makes the output
 *    reproducible from a clean checkout.
 *  - Each preview needs @PreviewTest as well as @Preview. @Preview alone renders in
 *    Studio but is not collected as a screenshot test, and the build fails with the
 *    unhelpful "did not discover any tests".
 *  - The previews must be members of a class, not top-level functions. Android Studio
 *    renders top-level previews happily, but the screenshot engine discovers previews as
 *    JUnit tests and needs a real class to attach them to — top-level functions land in a
 *    synthetic `…Kt` facade and are silently skipped, which surfaces as "did not discover
 *    any tests".
 *  - Previews render through [ChromePreview], not bare screens: the bottom bar and
 *    navigation rail live in the app's `Navigation`, above the pages, so a bare
 *    `CalculatorScreen` shot would miss the chrome users actually see. The chrome
 *    here is the real component (`BottomNavBar`, `NavigationRail`) with the app's
 *    real tab list — only the selected tab is literal.
 *
 * Rendering goes through the app's real [DynamicTheme] with `darkTheme = true`, matching
 * the `cmd uimode night yes` the old on-device generator used. Note that Material You
 * sources its palette from the device wallpaper, which does not exist here, so these
 * render with the fallback scheme rather than a user's actual accent colour.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-keypad", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview1Keypad() {
        ChromePreview(Route.Calculator)
    }

    @PreviewTest
    @Preview(name = "2-graph", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview2Graph() {
        ChromePreview(Route.Graph)
    }

    @PreviewTest
    @Preview(name = "3-history", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview3History() {
        ChromePreview(Route.Calculator, showHistory = true)
    }

    @PreviewTest
    @Preview(name = "4-units", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview4Units() {
        ChromePreview(Route.Units)
    }

    @PreviewTest
    @Preview(name = "5-keypad-expanded", device = EXPANDED, group = "large-tablet", showSystemUi = true)
    @Composable
    fun Preview5KeypadExpanded() {
        ChromePreview(Route.Calculator)
    }
}

/**
 * One tab's screen content, factored out of the previews so [ChromePreview] can
 * host every destination behind the real navigation chrome.
 */
@Composable
private fun KeypadContent() {
    CalculatorScreen(
        state = CalculatorUiState(
            input = "sin(45) + 2^10",
            preview = "1024.707",
            memory = 12.5,
            angleMode = AngleMode.DEGREES,
        ),
        actions = CalculatorActions.Noop,
    )
}

@Composable
private fun HistoryContent() {
    CalculatorScreen(
        state = CalculatorUiState(
            input = "12!",
            preview = "479001600",
            angleMode = AngleMode.RADIANS,
            history = listOf(
                HistoryEntry("sin(45) + 2^10", "1024.707"),
                HistoryEntry("nCr(52,5)", "2598960"),
                HistoryEntry("|-17.5| * pi", "54.978"),
                HistoryEntry("log(2,4096)", "12"),
            ),
        ),
        actions = CalculatorActions.Noop,
    )
}

@Composable
private fun GraphContent() {
    val colors = GraphTabColors
    GraphScreen(
        state = GraphUiState(
            functions = listOf(
                GraphFunction(id = 0, text = "sin(x)", color = colors[0]),
                GraphFunction(id = 1, text = "x^2/8 - 2", color = colors[1]),
                GraphFunction(id = 2, text = "cos(x)/2", color = colors[2], enabled = false),
            ),
            markers = listOf(
                GraphMarker(GraphPoint(0.0, 0.0), FeatureKind.INTERSECTION, listOf(0L, 1L)),
                GraphMarker(GraphPoint(-1.571, -1.0), FeatureKind.MINIMUM, listOf(0L)),
            ),
            angleMode = AngleMode.RADIANS,
            viewport = GraphViewport(centerX = 0.0, centerY = 0.0, scale = 42.0),
        ),
        actions = GraphActions.Noop,
    )
}

@Composable
private fun UnitsContent() {
    UnitConverterScreen(
        state = UnitConverterUiState(
            categories = UnitRegistry.categories,
            selectedCategoryIndex = 3, // Temperature
            fromToken = "degC",
            toToken = "degF",
            inputText = "100",
            outputText = "212",
        ),
        actions = UnitConverterActions.Noop,
    )
}

/**
 * The app's real navigation chrome around preview screen content: the same tab
 * list, the same rail-vs-bottom-bar branch, the same [MainNavigation] host as
 * `Navigation` in MainActivity — with a literal back stack standing in for the
 * selected tab, since previews have no ViewModel to drive one.
 *
 * [showHistory] selects the history variant of the keypad tab (Preview3); the
 * selected tab always matches the shown screen.
 */
@Composable
private fun ChromePreview(route: Route, showHistory: Boolean = false) {
    DynamicTheme(darkTheme = true) {
        // NavDisplay requires a NavigationEventDispatcherOwner, which an Activity
        // normally provides — Layoutlib has no Activity, so previews supply a
        // root dispatcher explicitly (null parent).
        CompositionLocalProvider(
            LocalNavigationEventDispatcherOwner provides rememberNavigationEventDispatcherOwner(
                parent = null,
            ),
        ) {
            ChromePreviewContent(route, showHistory)
        }
    }
}

@Composable
private fun ChromePreviewContent(route: Route, showHistory: Boolean = false) {
    val backStack = rememberNavBackStack(route)
    val current = backStack.last()
    val tabs = listOf(
        BottomBarItem("Calculator", Route.Calculator) { IconCalculate() },
        BottomBarItem("Graph", Route.Graph) { IconFunctions() },
        BottomBarItem("Units", Route.Units) { IconRuler() },
    )
    val entries: @Composable () -> Unit = {
        when (current) {
            Route.Calculator -> if (showHistory) HistoryContent() else KeypadContent()
            Route.Graph -> GraphContent()
            Route.Units -> UnitsContent()
        }
    }
    if (isExpandedWidth() || isCompactHeight()) {
        Row(Modifier.fillMaxSize()) {
            NavigationRail(Modifier.fillMaxHeight()) {
                tabs.forEach { tab ->
                    NavigationRailItem(
                        selected = current == tab.route,
                        onClick = {},
                        icon = tab.icon,
                        label = { Text(tab.name) },
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                MainNavigation(backStack) {
                    entry<Route.Calculator>(metadata = SiblingPage()) { entries() }
                    entry<Route.Graph>(metadata = SiblingPage()) { entries() }
                    entry<Route.Units>(metadata = SiblingPage()) { entries() }
                }
            }
        }
    } else {
        MainNavigation(
            backStack,
            bottomBar = { BottomNavBar(backStack, tabs, current) },
        ) {
            entry<Route.Calculator>(metadata = SiblingPage()) { entries() }
            entry<Route.Graph>(metadata = SiblingPage()) { entries() }
            entry<Route.Units>(metadata = SiblingPage()) { entries() }
        }
    }
}
