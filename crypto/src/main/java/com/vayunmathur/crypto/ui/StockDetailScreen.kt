package com.vayunmathur.crypto.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.vayunmathur.crypto.MAIN_NAVBAR_PAGES
import com.vayunmathur.crypto.PortfolioPage
import com.vayunmathur.crypto.PortfolioViewModel
import com.vayunmathur.crypto.api.JupiterAPI
import com.vayunmathur.crypto.displayAmount
import com.vayunmathur.crypto.token.TokenInfo
import com.vayunmathur.crypto.token.TokenPriceRepository
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.round

@Composable
fun StockDetailScreen(viewModel: PortfolioViewModel, backStack: NavBackStack<NavKey>, stockTokenMint: String) {
    val stockTokens by viewModel.stockTokens.collectAsState()
    val stockToken = stockTokens.find { it.tokenInfo.mintAddress == stockTokenMint }!!
    val usdcToken = TokenInfo.USDC

    Scaffold(bottomBar = {
        BottomNavBar(backStack, MAIN_NAVBAR_PAGES, PortfolioPage)
    }) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stockToken.tokenInfo.name,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "$${stockToken.totalValue.round(2)}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(text = "${stockToken.amount.displayAmount()} ${stockToken.tokenInfo.symbol}")

            Spacer(modifier = Modifier.height(32.dp))

            var dialogOption by remember { mutableIntStateOf(0) }

            SingleChoiceSegmentedButtonRow {
                SegmentedButton(dialogOption == 1, {dialogOption = 1}, SegmentedButtonDefaults.itemShape(0, 2)) {
                    Text("Deposit")
                }
                SegmentedButton(dialogOption == 2, {dialogOption = 2}, SegmentedButtonDefaults.itemShape(1, 2)) {
                    Text("Withdraw")
                }
            }

            if (dialogOption == 1) {
                OrderDialog(
                    viewModel,
                    { amount -> JupiterAPI.createOrder(usdcToken, stockToken.tokenInfo, amount, viewModel.wallet) },
                    usdcToken,
                    stockToken.tokenInfo,
                    false,
                    { Text("Deposit ${stockToken.tokenInfo.symbol}") },
                    { outputAmount -> Text("Deposit ${outputAmount.round(2)} ${stockToken.tokenInfo.symbol}") },
                    { dialogOption = 0}
                )
            }
            if (dialogOption == 2) {
                OrderDialog(
                    viewModel,
                    { amount ->
                        val stockTokenPrice = TokenPriceRepository[stockToken.tokenInfo]!!.price
                        val usdcTokenPrice = TokenPriceRepository[usdcToken]!!.price
                        val amt = amount * (usdcTokenPrice / stockTokenPrice)
                        JupiterAPI.createOrder(stockToken.tokenInfo, usdcToken, amt, viewModel.wallet)
                    },
                    stockToken.tokenInfo,
                    usdcToken,
                    true,
                    { Text("Withdraw ${stockToken.tokenInfo.symbol}") },
                    { outputAmount -> Text("Withdraw ${outputAmount.round(2)} ${stockToken.tokenInfo.symbol}") },
                    { dialogOption = 0}
                )
            }
        }
    }
}