package com.vayunmathur.crypto.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.vayunmathur.crypto.api.JupiterAPI
import com.vayunmathur.crypto.MAIN_NAVBAR_PAGES
import com.vayunmathur.crypto.MaximizedRow
import com.vayunmathur.crypto.NavigationBottomBar
import com.vayunmathur.crypto.PortfolioViewModel
import com.vayunmathur.crypto.R
import com.vayunmathur.crypto.SwapPage
import com.vayunmathur.crypto.api.PendingOrder
import com.vayunmathur.crypto.token.TokenInfo
import com.vayunmathur.crypto.token.TokenPriceRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.pow

@Composable
fun SwapScreen(viewModel: PortfolioViewModel, backStack: NavBackStack<NavKey>) {
    val tokens by viewModel.normalTokens.collectAsState()
    val swappableTokens = tokens.map { it.tokenInfo }
    var fromAmount by remember { mutableStateOf("5") }
    var toTokenInfo by remember { mutableStateOf(TokenInfo.USDC) }
    var fromTokenInfo by remember { mutableStateOf(TokenInfo.SOL) }
    var pendingOrder by remember { mutableStateOf<PendingOrder?>(null) }
    val progress = remember { Animatable(0f) }
    var lastUpdateTime by remember { mutableStateOf(0L) }
    var showDialog by remember { mutableStateOf(false) }

    val fromToken = tokens.find { it.tokenInfo.mintAddress == fromTokenInfo.mintAddress }

    if (showDialog) {
        val fromAmountDouble = fromAmount.toDoubleOrNull() ?: 0.0
        val toAmount = pendingOrder?.let { it.outAmount / 10.0.pow(toTokenInfo.decimals) } ?: 0.0
        ConfirmationDialog(
            onConfirm = {
                pendingOrder?.let { viewModel.placeOrder(it) }
            },
            onDismiss = { showDialog = false },
            title = "Confirm Swap",
            content = "You are about to swap $fromAmountDouble ${fromTokenInfo.symbol} for $toAmount ${toTokenInfo.symbol}."
        )
    }

    LaunchedEffect(fromAmount, fromTokenInfo, toTokenInfo) {
        val amount = fromAmount.toDoubleOrNull() ?: 0.0
        pendingOrder = null
        if (amount > 0) {
            while (isActive) {
                try {
                    pendingOrder = JupiterAPI.createOrder(fromTokenInfo, toTokenInfo, amount, viewModel.wallet)
                    lastUpdateTime = System.currentTimeMillis()
                } catch (e: Exception) {
                    println("Error creating order: $e")
                }
                delay(15000)
            }
        }
    }

    LaunchedEffect(lastUpdateTime) {
        if (lastUpdateTime == 0L) return@LaunchedEffect
        progress.snapTo(1f)
        progress.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = 15000, easing = LinearEasing)
        )
    }

    Scaffold(bottomBar = {
        NavigationBottomBar(MAIN_NAVBAR_PAGES, SwapPage, backStack)
    }) { paddingValues ->
        Column(
            Modifier
                .padding(paddingValues)
                .padding(16.dp)
                .fillMaxWidth()
        ) {

            Card {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TokenSelector(
                            selectedTokenInfo = fromTokenInfo,
                            onTokenSelected = { fromTokenInfo = it },
                            allTokens = swappableTokens,
                            otherSelectedToken = toTokenInfo
                        )
                        Spacer(Modifier.weight(1f))
                        Row {
                            TextButton(onClick = {
                                val balance = fromToken?.amount ?: 0.0
                                fromAmount = (balance * 0.5).toString()
                            }, Modifier.width(70.dp).height(40.dp)) { Text("50%") }
                            TextButton(onClick = {
                                val balance = fromToken?.amount ?: 0.0
                                fromAmount = (balance * 0.75).toString()
                            }, Modifier.width(70.dp).height(40.dp)) { Text("75%") }
                            TextButton(onClick = {
                                fromAmount = (fromToken?.amount ?: 0.0).toString()
                            }, Modifier.width(70.dp).height(40.dp)) { Text("MAX") }
                        }
                    }
                    Row {
                        Text(
                            "Balance: ${fromToken?.amount ?: 0.0}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    MaximizedRow {
                        OutlinedTextField(
                            value = fromAmount,
                            onValueChange = { fromAmount = it },
                            textStyle = LocalTextStyle.current.copy(
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold
                            ),
                        )
                        val fromValue = (fromAmount.toDoubleOrNull()
                            ?: 0.0) * (TokenPriceRepository[fromTokenInfo]?.price ?: 0.0)
                        Text(
                            NumberFormat.getCurrencyInstance().format(fromValue),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            Box(modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                IconButton(onClick = {
                    val temp = fromTokenInfo
                    fromTokenInfo = toTokenInfo
                    toTokenInfo = temp
                    val toAmount = pendingOrder?.let { it.outAmount / 10.0.pow(toTokenInfo.decimals) }
                    fromAmount = toAmount?.toString() ?: ""
                }) {
                    Icon(painterResource(R.drawable.swap_vert_24px), contentDescription = "Swap")
                }
            }

            val toToken = tokens.find { it.tokenInfo.mintAddress == toTokenInfo.mintAddress }
            Card {
                Column(Modifier.padding(16.dp)) {
                    val toAmount = pendingOrder?.let { it.outAmount / 10.0.pow(toTokenInfo.decimals) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TokenSelector(
                            selectedTokenInfo = toTokenInfo,
                            onTokenSelected = { toTokenInfo = it },
                            allTokens = swappableTokens,
                            otherSelectedToken = fromTokenInfo
                        )
                        Spacer(Modifier.weight(1f))
                        Text(toAmount?.let { String.format(Locale.US, "%.6f", it)} ?: "—", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                     Row {
                        Text(
                            "Balance: ${toToken?.amount ?: 0.0}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.weight(1f))
                        val toValue = if(toAmount != null) toAmount * (TokenPriceRepository[toTokenInfo]?.price ?: 0.0) else null
                        Text(
                            toValue?.let { NumberFormat.getCurrencyInstance().format(it) } ?: "—",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            val rate = pendingOrder?.let {
                val outAmount = it.outAmount / 10.0.pow(toTokenInfo.decimals)
                val inAmount = it.inAmount / 10.0.pow(fromTokenInfo.decimals)
                outAmount / inAmount
            }

            if (rate != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        progress = { progress.value },
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        "1 ${fromTokenInfo.symbol} = ${String.format(Locale.US, "%.6f", rate)} ${toTokenInfo.symbol}",
                        modifier = Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }


            Spacer(Modifier.height(16.dp))
            val fromAmountDouble = fromAmount.toDoubleOrNull() ?: 0.0
            val balance = fromToken?.amount ?: 0.0
            val hasSufficientBalance = fromAmountDouble <= balance

            Button(
                onClick = { showDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                enabled = pendingOrder != null && fromAmountDouble > 0 && hasSufficientBalance
            ) {
                val buttonText = if (fromAmountDouble > balance) "Insufficient Balance" else "Swap"
                Text(buttonText, fontSize = 16.sp)
            }
        }
    }
}

@Composable
fun TokenSelector(
    selectedTokenInfo: TokenInfo,
    onTokenSelected: (TokenInfo) -> Unit,
    allTokens: List<TokenInfo>,
    otherSelectedToken: TokenInfo?
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        TextButton(onClick = { expanded = true }) {
            Text(selectedTokenInfo.symbol, style = MaterialTheme.typography.headlineMedium)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            allTokens.forEach { token ->
                DropdownMenuItem(
                    text = { Text(token.symbol) },
                    onClick = {
                        onTokenSelected(token)
                        expanded = false
                    },
                    enabled = token.mintAddress != otherSelectedToken?.mintAddress
                )
            }
        }
    }
}
