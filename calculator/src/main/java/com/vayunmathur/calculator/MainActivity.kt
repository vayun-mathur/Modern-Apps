package com.vayunmathur.calculator

import android.content.Intent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.vayunmathur.calculator.ui.CalculatorPage
import com.vayunmathur.calculator.ui.GraphPage
import com.vayunmathur.calculator.ui.UnitConverterPage
import com.vayunmathur.calculator.util.CalculatorViewModel
import com.vayunmathur.calculator.widget.CalculatorGlanceWidgetReceiver
import com.vayunmathur.calculator.widget.UnitsGlanceWidget
import com.vayunmathur.calculator.widget.UnitsGlanceWidgetReceiver
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.library.ui.IconCalculate
import com.vayunmathur.library.ui.IconFunctions
import com.vayunmathur.library.ui.IconRuler
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.ui.NavigationRail
import com.vayunmathur.library.ui.NavigationRailItem
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.isCompactHeight
import com.vayunmathur.library.ui.isExpandedWidth
import com.vayunmathur.library.util.BottomBarItem
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.EntryProviderScope
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.SiblingPage
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.library.widgets.updateWidgetPreviews
import kotlinx.serialization.Serializable

class MainActivity : MaActivity() {
    private val viewModel: CalculatorViewModel by viewModels()

    /**
     * Counts units deep-links rather than latching a flag: the widget can be tapped again after
     * the user has manually moved to another tab, and an unchanged flag would not re-navigate.
     */
    private var unitsRequest by mutableIntStateOf(0)

    override fun onCreateBeforeContent() {
        // FIRST_PARTY covers api.maproj.org, where the currency-rate proxy lives.
        NetworkClient.init(this, TrustBundle.FIRST_PARTY)
        updateWidgetPreviews(CalculatorGlanceWidgetReceiver::class)
        updateWidgetPreviews(UnitsGlanceWidgetReceiver::class)
        if (intent.opensUnits()) unitsRequest++
    }

    @Composable
    override fun Content() {
        Navigation(viewModel, unitsRequest)
    }

    /**
     * Where widget taps land once the app is running: the units widget's intent carries
     * `FLAG_ACTIVITY_SINGLE_TOP`, and this activity is always top of its own task.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.opensUnits()) unitsRequest++
    }
}

private fun Intent?.opensUnits(): Boolean =
    this?.getBooleanExtra(UnitsGlanceWidget.EXTRA_OPEN_UNITS, false) == true

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Calculator : Route

    @Serializable
    data object Graph : Route

    @Serializable
    data object Units : Route
}

@Composable
fun Navigation(viewModel: CalculatorViewModel, unitsRequest: Int = 0) {
    val backStack = rememberNavBackStack<Route>(if (unitsRequest > 0) Route.Units else Route.Calculator)
    LaunchedEffect(unitsRequest) {
        if (unitsRequest > 0 && backStack.last() != Route.Units) backStack.reset(Route.Units)
    }
    val current = backStack.last()
    val tabs = listOf(
        BottomBarItem("Calculator", Route.Calculator) { IconCalculate() },
        BottomBarItem("Graph", Route.Graph) { IconFunctions() },
        BottomBarItem("Units", Route.Units) { IconRuler() },
    )
    // Wide or short windows get the rail on the left instead of the bottom bar,
    // matching TabbedPagerScaffold's expanded branch (appstore). This frees the
    // full height for the keypad/display instead of spending ~64dp on a bottom
    // bar exactly when vertical space is scarcest.
    val useRail = isExpandedWidth() || isCompactHeight()
    if (useRail) {
        Row(Modifier.fillMaxSize()) {
            NavigationRail(Modifier.fillMaxHeight()) {
                tabs.forEach { tab ->
                    NavigationRailItem(
                        selected = current == tab.route,
                        onClick = { if (backStack.last() != tab.route) backStack.reset(tab.route) },
                        icon = tab.icon,
                        label = { Text(tab.name) },
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                MainNavigation(backStack) { calculatorEntries(viewModel) }
            }
        }
    } else {
        MainNavigation(
            backStack,
            bottomBar = { BottomNavBar(backStack, tabs, current) },
        ) {
            calculatorEntries(viewModel)
        }
    }
}

private fun EntryProviderScope<Route>.calculatorEntries(viewModel: CalculatorViewModel) {
    entry<Route.Calculator>(metadata = SiblingPage()) { CalculatorPage(viewModel) }
    entry<Route.Graph>(metadata = SiblingPage()) { GraphPage(viewModel) }
    entry<Route.Units>(metadata = SiblingPage()) { UnitConverterPage(viewModel) }
}
