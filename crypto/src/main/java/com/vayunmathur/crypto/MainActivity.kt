package com.vayunmathur.crypto

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FlexibleBottomAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.ViewModelProvider
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.vayunmathur.crypto.ui.LendDetailScreen
import com.vayunmathur.crypto.ui.LoginScreen
import com.vayunmathur.crypto.ui.PortfolioScreen
import com.vayunmathur.crypto.ui.PredictionMarketDetailPage
import com.vayunmathur.crypto.ui.PredictionMarketDetailScreen
import com.vayunmathur.crypto.ui.PredictionMarketScreen
import com.vayunmathur.crypto.ui.PrivateKeyScreen
import com.vayunmathur.crypto.ui.SendScreen
import com.vayunmathur.crypto.ui.StockDetailScreen
import com.vayunmathur.crypto.ui.SwapScreen
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val viewModel = ViewModelProvider(this, PortfolioViewModelFactory(application))[PortfolioViewModel::class.java]

        setContent {
            DynamicTheme {
                Navigation(viewModel)
            }
        }
    }
}

@Serializable
data object LoginPage: NavKey

@Serializable
data object PortfolioPage: NavKey

@Serializable
data class LendDetailPage(val jlTokenMint: String): NavKey

@Serializable
data object PredictionMarketPage: NavKey

@Serializable
data object SwapPage: NavKey

@Serializable
data object SendPage: NavKey

@Serializable
data class StockDetailPage(val stockTokenMint: String): NavKey

@Serializable
data object PrivateKeyPage: NavKey

@Composable
fun Navigation(viewModel: PortfolioViewModel) {
    val walletInitialized by viewModel.walletInitialized.collectAsState()
    val backStack = rememberNavBackStack(if (walletInitialized) PortfolioPage else LoginPage)

    LaunchedEffect(walletInitialized) {
        if (!walletInitialized) {
            backStack.clear()
            backStack.add(LoginPage)
        } else {
            backStack.clear()
            backStack.add(PortfolioPage)
        }
    }

    MainNavigation(backStack) {
        entry<LoginPage> {
            LoginScreen(viewModel)
        }
        entry<PortfolioPage> {
            PortfolioScreen(viewModel, backStack)
        }
        entry<LendDetailPage> { key ->
            LendDetailScreen(viewModel, backStack, key.jlTokenMint)
        }
        entry<PredictionMarketPage> {
            PredictionMarketScreen(viewModel, backStack)
        }
        entry<PredictionMarketDetailPage> { key ->
            PredictionMarketDetailScreen(viewModel, backStack, key.marketId)
        }
        entry<SwapPage> {
            SwapScreen(viewModel, backStack)
        }
        entry<SendPage> {
            SendScreen(viewModel, backStack)
        }
        entry<StockDetailPage> { key ->
            StockDetailScreen(viewModel, backStack, key.stockTokenMint)
        }
        entry<PrivateKeyPage> {
            PrivateKeyScreen(viewModel, backStack)
        }
    }
}


data class NavbarPage(val name: String, val icon: Int, val route: NavKey)

val MAIN_NAVBAR_PAGES = listOf(
    NavbarPage("Portfolio", R.drawable.account_balance_wallet_24px, PortfolioPage),
    NavbarPage("Swap", R.drawable.swap_vert_24px, SwapPage),
    NavbarPage("Send", R.drawable.send_24px, SendPage),
    NavbarPage("Prediction", R.drawable.online_prediction_24px, PredictionMarketPage),
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NavigationBottomBar(pages: List<NavbarPage>, currentPage: NavKey, backStack: NavBackStack<NavKey>) {
    FlexibleBottomAppBar {
        pages.forEach { item ->
            NavigationBarItem(
                selected = currentPage == item.route,
                onClick = {
                    if (backStack.last() != item.route) {
                        backStack.add(item.route)
                    }
                },
                label = { Text(item.name) },
                icon = { Icon(painterResource(item.icon), null) }
            )
        }
    }
}
