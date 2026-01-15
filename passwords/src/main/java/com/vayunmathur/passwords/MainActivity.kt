package com.vayunmathur.passwords

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavKey
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.passwords.ui.MenuPage
import com.vayunmathur.passwords.ui.PasswordDetailsPage
import com.vayunmathur.passwords.ui.PasswordPage
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                Navigation()
            }
        }
    }
}

@Serializable
data class Password(val id: Long)

@Serializable
sealed interface Route: NavKey {
    @Serializable
    data object Menu: Route

    @Serializable
    data class PasswordPage(val pass: Password): Route

    @Serializable
    data class PasswordDetailsPage(val pass: Password): Route
}


@Composable
fun Navigation() {
    val backStack = rememberNavBackStack<Route>(Route.Menu)
    MainNavigation(backStack) {
        entry<Route.Menu> {
            MenuPage(backStack)
        }
        entry<Route.PasswordPage> {
            PasswordPage(backStack, it.pass)
        }
        entry<Route.PasswordDetailsPage> {
            PasswordDetailsPage(backStack, it.pass)
        }
    }
}