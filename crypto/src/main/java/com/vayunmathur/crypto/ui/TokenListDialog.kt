package com.vayunmathur.crypto.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.vayunmathur.crypto.PortfolioViewModel
import com.vayunmathur.crypto.token.JupiterLendRepository
import com.vayunmathur.crypto.token.TokenInfo

@Composable
fun TokenListDialog(alreadyExistingTokens: Set<TokenInfo>, viewModel: PortfolioViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = { Text("Add a New Account") },
        text = {
            LazyColumn {
                TokenInfo.Companion.Category.entries.forEach { category ->
                    if(category == TokenInfo.Companion.Category.PRED_MARKET) return@forEach

                    item {
                        Text(category.displayName, style = MaterialTheme.typography.titleMedium)
                    }

                    items(TokenInfo.BY_TYPE(category) - alreadyExistingTokens) { tokenInfo ->
                        val onClick = { viewModel.createTokenAccount(tokenInfo); onDismiss() }
                        if(tokenInfo.category == TokenInfo.Companion.Category.JUPITER_LEND) {
                            val apy = JupiterLendRepository[tokenInfo]?.apy ?: 0.0
                            TextButton(onClick, Modifier.fillMaxWidth()) {
                                Text("${tokenInfo.name} - ${String.format("%.2f", apy * 100)}% APY")
                            }
                        } else {
                            TextButton(onClick, Modifier.fillMaxWidth()) {
                                Text(tokenInfo.name)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss() }) { Text("Cancel") } }
    )
}