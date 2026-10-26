package com.vayunmathur.networklocation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.networklocation.ui.SetupScreen
import com.vayunmathur.networklocation.ui.StatusScreen

@Composable
fun Navigation() {
    val context = LocalContext.current
    val start = remember { if (OfflineDatabases.allPresent(context)) Route.Status else Route.Setup }
    val backStack = rememberNavBackStack<Route>(start)

    MainNavigation(backStack) {
        entry<Route.Setup> {
            // reset() rather than add(): once the databases are installed there is nothing to go
            // back to, and leaving Setup on the stack would let Back return to a finished download.
            SetupScreen(onReady = { backStack.reset(Route.Status) })
        }
        entry<Route.Status> { StatusScreen() }
    }
}
