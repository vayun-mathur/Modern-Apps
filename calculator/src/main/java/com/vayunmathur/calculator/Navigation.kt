package com.vayunmathur.calculator

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.vayunmathur.calculator.platform.CalculatorViewModel
import com.vayunmathur.calculator.ui.CalculatorPage
import com.vayunmathur.calculator.ui.GraphPage
import com.vayunmathur.calculator.ui.UnitConverterPage
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
import androidx.compose.ui.Modifier

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
                MainNavigation(backStack) {
    entry<Route.Calculator>(metadata = SiblingPage()) { CalculatorPage(viewModel) }
    entry<Route.Graph>(metadata = SiblingPage()) { GraphPage(viewModel) }
    entry<Route.Units>(metadata = SiblingPage()) { UnitConverterPage(viewModel) }
            }
            }
        }
    } else {
        MainNavigation(
            backStack,
            bottomBar = { BottomNavBar(backStack, tabs, current) },
        ) {
    entry<Route.Calculator>(metadata = SiblingPage()) { CalculatorPage(viewModel) }
    entry<Route.Graph>(metadata = SiblingPage()) { GraphPage(viewModel) }
    entry<Route.Units>(metadata = SiblingPage()) { UnitConverterPage(viewModel) }
        }
    }
}
