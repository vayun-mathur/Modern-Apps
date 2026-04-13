package com.vayunmathur.crypto.ui.dialogs
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.crypto.util.PortfolioViewModel
import com.vayunmathur.crypto.R
import com.vayunmathur.crypto.data.JupiterLendRepository
import com.vayunmathur.crypto.data.TokenInfo
import com.vayunmathur.library.util.round

@Composable
fun TokenListDialog(alreadyExistingTokens: Set<TokenInfo>, viewModel: PortfolioViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = { Text(stringResource(R.string.add_new_account)) },
        text = {
            LazyColumn {
                TokenInfo.Companion.Category.entries.forEach { category ->
                    if(category == TokenInfo.Companion.Category.PRED_MARKET) return@forEach

                    item {
                        Text(category.displayName, style = MaterialTheme.typography.titleMedium)
                    }

                    items(TokenInfo.byType(category) - alreadyExistingTokens) { tokenInfo ->
                        val onClick = { viewModel.createTokenAccount(tokenInfo); onDismiss() }
                        if(tokenInfo.category == TokenInfo.Companion.Category.JUPITER_LEND) {
                            val apy = JupiterLendRepository[tokenInfo]?.apy ?: 0.0
                            TextButton(onClick, Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.token_name_apy_format, tokenInfo.name, (apy*100).round(2)))
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
        confirmButton = { TextButton(onClick = { onDismiss() }) { Text(stringResource(R.string.cancel)) } }
    )
}