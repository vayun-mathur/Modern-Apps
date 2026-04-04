package com.vayunmathur.games.alchemist

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import com.vayunmathur.games.alchemist.ui.HomeScreen
import com.vayunmathur.games.alchemist.ui.ItemDetailsScreen
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val ds = DataStoreUtils.getInstance(this)
        Alchemist.init(this)
        setContent {
            DynamicTheme {
                Navigation(ds)
            }
        }
    }
}

@Serializable
sealed interface Route: NavKey {
    @Serializable
    data object Home: Route
    @Serializable
    data class ItemDetails(val item: Int): Route
}

@Composable
fun Navigation(ds: DataStoreUtils) {
    val backStack = rememberNavBackStack<Route>(Route.Home)
    MainNavigation(backStack) {
        entry<Route.Home> {
            HomeScreen(backStack, ds)
        }
        entry<Route.ItemDetails> {
            ItemDetailsScreen(backStack, ds, it.item)
        }
    }
}