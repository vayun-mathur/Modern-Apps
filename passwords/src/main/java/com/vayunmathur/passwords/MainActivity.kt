package com.vayunmathur.passwords

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavKey
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.passwords.data.PasswordDatabase
import com.vayunmathur.passwords.ui.MenuPage
import com.vayunmathur.passwords.ui.PasswordEditPage
import com.vayunmathur.passwords.ui.PasswordPage
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val database = buildDatabase<PasswordDatabase>()
        val viewModel = DatabaseViewModel(Password::class to database.passwordDao())
        setContent {
            DynamicTheme {
                Navigation(viewModel)
            }
        }
    }
}

@Serializable
sealed interface Route: NavKey {
    @Serializable
    data object Menu: Route

    @Serializable
    data class PasswordPage(val id: Long): Route

    @Serializable
    data class PasswordEditPage(val id: Long): Route
}


@Composable
fun Navigation(viewModel: DatabaseViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Menu)
    MainNavigation(backStack) {
        entry<Route.Menu>(metadata = ListPage()) {
            MenuPage(backStack, viewModel)
        }
        entry<Route.PasswordPage>(metadata = ListDetailPage()) {
            PasswordPage(backStack, it.id, viewModel)
        }
        entry<Route.PasswordEditPage>(metadata = ListDetailPage()) {
            PasswordEditPage(backStack, it.id, viewModel)
        }
    }
}