package com.vayunmathur.crypto.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.vayunmathur.crypto.MAIN_NAVBAR_PAGES
import com.vayunmathur.crypto.MaximizedRow
import com.vayunmathur.crypto.NavigationBottomBar
import com.vayunmathur.crypto.PortfolioViewModel
import com.vayunmathur.crypto.SendPage
import com.vayunmathur.crypto.token.TokenInfo
import com.vayunmathur.crypto.token.TokenPriceRepository
import org.sol4k.PublicKey
import java.text.NumberFormat

@Composable
fun SendScreen(viewModel: PortfolioViewModel, backStack: NavBackStack<NavKey>) {
    val tokens by viewModel.normalTokens.collectAsState()
    val sendableTokens = tokens.map { it.tokenInfo }
    var fromAmount by remember { mutableStateOf("") }
    var fromTokenInfo by remember { mutableStateOf(TokenInfo.USDC) }
    var recipientAddress by remember { mutableStateOf("") }
    var showDialog by remember { mutableStateOf(false) }

    val fromToken = tokens.find { it.tokenInfo.mintAddress == fromTokenInfo.mintAddress }

    if (showDialog) {
        val fromAmountDouble = fromAmount.toDoubleOrNull() ?: 0.0
        ConfirmationDialog(
            onConfirm = {
                viewModel.sendToken(
                    fromTokenInfo,
                    PublicKey(recipientAddress),
                    fromAmountDouble
                )
                fromAmount = ""
                recipientAddress = ""
            },
            onDismiss = { showDialog = false },
            title = "Confirm Transaction",
            content = "You are about to send $fromAmount ${fromTokenInfo.symbol} to $recipientAddress. This action cannot be undone."
        )
    }

    Scaffold(bottomBar = {
        NavigationBottomBar(MAIN_NAVBAR_PAGES, SendPage, backStack)
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
                            allTokens = sendableTokens,
                            otherSelectedToken = null
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
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = recipientAddress,
                onValueChange = { recipientAddress = it },
                label = { Text("Recipient Address") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            val fromAmountDouble = fromAmount.toDoubleOrNull() ?: 0.0
            val balance = fromToken?.amount ?: 0.0
            val hasSufficientBalance = fromAmountDouble <= balance
            Button(
                onClick = { showDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                enabled = fromAmountDouble > 0 && hasSufficientBalance && recipientAddress.isNotBlank()
            ) {
                val buttonText = if (fromAmountDouble > balance) "Insufficient Balance" else "Send"
                Text(buttonText, fontSize = 16.sp)
            }
        }
    }
}