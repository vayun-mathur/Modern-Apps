package com.vayunmathur.crypto.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.vayunmathur.crypto.MAIN_NAVBAR_PAGES
import com.vayunmathur.crypto.MaximizedRow
import com.vayunmathur.crypto.NavigationBottomBar
import com.vayunmathur.crypto.PortfolioViewModel
import com.vayunmathur.crypto.PredictionMarketPage
import com.vayunmathur.crypto.api.PredictionMarket
import com.vayunmathur.crypto.token.TokenInfo
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.util.pop
import kotlinx.serialization.Serializable
import java.text.NumberFormat

@Serializable
data class PredictionMarketDetailPage(val marketId: String): NavKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PredictionMarketDetailScreen(viewModel: PortfolioViewModel, backStack: NavBackStack<NavKey>, marketId: String) {
    val markets by viewModel.predictionMarkets.collectAsState()
    val market = markets.find { it.seriesTicker == marketId }
    var selectedMarket by remember { mutableStateOf<Pair<PredictionMarket.Event.Market, Boolean>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {  },
                navigationIcon = { IconNavigation(backStack) }
            )
        },
        bottomBar = {
            NavigationBottomBar(MAIN_NAVBAR_PAGES,PredictionMarketPage, backStack)
        }
    ) { paddingValues ->
        if (market != null) {
            LazyColumn(
                Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 16.dp)
            ) {
                item {
                    MaximizedRow {
                        Text(
                            market.title,
                            style = MaterialTheme.typography.headlineLarge,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("● Live", color = Color.Green, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(16.dp))
                }

                itemsIndexed(market.markets.sortedByDescending { it.yesPrice }) { idx, marketItem ->
                    if (idx > 0) HorizontalDivider()
                    MaximizedRow(Modifier.padding(vertical = 8.dp)) {
                        Text(
                            marketItem.subtitle,
                            fontSize = 16.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Row(Modifier, Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
                            Text(
                                "${(marketItem.chance * 100).toInt()}%",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Button(
                                modifier = Modifier
                                    .width(70.dp)
                                    .height(35.dp),
                                onClick = {
                                    selectedMarket = marketItem to true
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(
                                        0x3325D366
                                    )
                                )
                            ) {
                                val yesNum = (marketItem.yesPrice * 100).toInt()
                                Text(
                                    "Yes ${yesNum}¢",
                                    color = Color(0xFF25D366),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                            Button(
                                modifier = Modifier
                                    .width(70.dp)
                                    .height(35.dp),
                                onClick = {
                                    selectedMarket = marketItem to false
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(
                                        0x33F44336
                                    )
                                )
                            ) {
                                val noNum = (marketItem.noPrice * 100).toInt()
                                Text(
                                    "No ${noNum}¢",
                                    color = Color(0xFFF44336),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (selectedMarket != null) {
        val (marketItem, isYes) = selectedMarket!!
        val tokenInfo = TokenInfo(
            if (isYes) "YES" else "NO",
            "yesno",
            TokenInfo.Companion.Category.NORMAL,
            if(isYes) marketItem.yesMint else marketItem.noMint,
            6, TokenInfo.SPL_TOKEN
        )
        OrderDialog(
            viewModel,
            { amount -> PredictionMarket.makeOrder(marketItem, isYes, amount, viewModel.wallet.publicKey.toBase58()) },
            TokenInfo.USDC,
            tokenInfo,
            false,
            {
                Row {
                    Text(
                        "Buy ${if (isYes) "Yes" else "No"}",
                        color = if (isYes) Color(0xFF25D366) else Color(0xFFF44336)
                    )
                    Text(" — ${marketItem.subtitle}")
                }
            },
            { outputAmount -> Text("Buy ${if(isYes) "Yes" else "No"} -> Win ${NumberFormat.getCurrencyInstance().format(outputAmount)}") },
            { selectedMarket = null },
        )
    }
}