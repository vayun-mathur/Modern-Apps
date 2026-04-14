package com.vayunmathur.crypto.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.crypto.MAIN_NAVBAR_PAGES
import com.vayunmathur.crypto.R
import com.vayunmathur.crypto.util.MaximizedRow
import com.vayunmathur.crypto.util.PortfolioViewModel
import com.vayunmathur.crypto.PredictionMarketPage
import com.vayunmathur.crypto.util.api.PredictionMarket
import com.vayunmathur.crypto.data.TokenInfo
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.crypto.ui.dialogs.OrderDialog
import com.vayunmathur.library.util.round

@Composable
fun PredictionMarketScreen(viewModel: PortfolioViewModel, backStack: NavBackStack<NavKey>) {

    val markets by viewModel.predictionMarkets.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.updatePredictionMarkers()
    }

    var selectedMarket by remember { mutableStateOf<Pair<PredictionMarket.Event.Market, Boolean>?>(null) }

    Scaffold(bottomBar = {
        BottomNavBar(backStack, MAIN_NAVBAR_PAGES, PredictionMarketPage)
    }) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues).padding(horizontal = 16.dp)
        ) {
            Text(stringResource(R.string.prediction_market_notice), style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(markets.filter { it.anyMarketOpen() }) { market ->
                    PredictionMarketCard(market, backStack, selectedMarket) { selectedMarket = it }
                }
            }
        }
    }

    if(selectedMarket != null) {
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
                        stringResource(R.string.buy_choice, if (isYes) stringResource(R.string.yes) else stringResource(R.string.no)),
                        color = if (isYes) Color(0xFF25D366) else Color(0xFFF44336)
                    )
                    Text(stringResource(R.string.market_subtitle_separator, marketItem.subtitle))
                }
            },
            { outputAmount -> Text(stringResource(R.string.buy_win_format, if (isYes) stringResource(R.string.yes) else stringResource(R.string.no), outputAmount.round(2))) },
            { selectedMarket = null },
        )
    }
}

@Composable
fun PredictionMarketCard(market: PredictionMarket.Event, backStack: NavBackStack<NavKey>, selectedMarket: Pair<PredictionMarket.Event.Market, Boolean>?, setSelectedMarket: (Pair<PredictionMarket.Event.Market, Boolean>) -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable { backStack.add(PredictionMarketDetailPage(market.seriesTicker)) },
    ) {
        Column(Modifier.padding(12.dp)) {
            MaximizedRow {
                Text(market.title, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.live_indicator), color = Color.Green, fontSize = 12.sp)
            }
            Spacer(Modifier.height(12.dp))
            market.markets.filter{it.closeTime > System.currentTimeMillis() / 1000 }.sortedByDescending { it.chance }.take(3).forEach { marketItem ->
                MaximizedRow {
                    Text(marketItem.subtitle, fontSize = 14.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${(marketItem.chance * 100).toInt()}%", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        SingleChoiceSegmentedButtonRow {
                            SegmentedButton(selectedMarket == Pair(marketItem, true), {
                                setSelectedMarket(Pair(marketItem, true))
                            }, SegmentedButtonDefaults.itemShape(0, 2), contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Text(stringResource(R.string.yes))
                            }
                            SegmentedButton(selectedMarket == Pair(marketItem, false), {
                                setSelectedMarket(Pair(marketItem, false))
                            }, SegmentedButtonDefaults.itemShape(1, 2), contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Text(stringResource(R.string.no))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text(stringResource(R.string.show_more), style = MaterialTheme.typography.labelSmall)
                Text(stringResource(R.string.market_volume_format, market.volume),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
