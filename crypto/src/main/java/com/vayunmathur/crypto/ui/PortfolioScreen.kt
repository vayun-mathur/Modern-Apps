package com.vayunmathur.crypto.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.crypto.LendDetailPage
import com.vayunmathur.crypto.MAIN_NAVBAR_PAGES
import com.vayunmathur.crypto.PortfolioPage
import com.vayunmathur.crypto.util.PortfolioViewModel
import com.vayunmathur.crypto.PrivateKeyPage
import com.vayunmathur.crypto.R
import com.vayunmathur.crypto.StockDetailPage
import com.vayunmathur.crypto.util.displayAmount
import com.vayunmathur.crypto.data.JupiterLendRepository
import com.vayunmathur.crypto.data.Token
import com.vayunmathur.crypto.data.TokenInfo
import com.vayunmathur.crypto.data.TokenPriceRepository
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.round
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioScreen(viewModel: PortfolioViewModel, backStack: NavBackStack<NavKey>) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showDialog by remember { mutableStateOf(false) }
    var showTokenDialog by remember { mutableStateOf(false) }

    if (showDialog) {
        ConfirmationDialog(
            onConfirm = { viewModel.logout() },
            onDismiss = { showDialog = false },
            title = stringResource(R.string.disconnect_wallet),
            content = stringResource(R.string.disconnect_wallet_message)
        )
    }
    val tokens by viewModel.tokens.collectAsState()

    if (showTokenDialog) {
        TokenListDialog(tokens.map(Token::tokenInfo).toSet(), viewModel = viewModel) { showTokenDialog = false}
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.private_key)) },
                    selected = false,
                    onClick = { backStack.add(PrivateKeyPage) }
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.disconnect_wallet)) },
                    selected = false,
                    onClick = { showDialog = true }
                )
            }
        }
    ) {
        Scaffold(bottomBar = {
            BottomNavBar(backStack, MAIN_NAVBAR_PAGES, PortfolioPage)
        }, topBar = {
            TopAppBar(title = { }, navigationIcon = {
                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                    Icon(painterResource(R.drawable.menu_24px), contentDescription = stringResource(R.string.menu_content_description))
                }
            })
        }, floatingActionButton = {
            FloatingActionButton(onClick = { showTokenDialog = true }) {
                IconAdd()
            }
        }) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize().padding(paddingValues)
            ) {
                TokenListScreen(viewModel, backStack)
            }
        }
    }
}

@Composable
fun TokenListScreen(viewModel: PortfolioViewModel, backStack: NavBackStack<NavKey>) {
    val normalTokens by viewModel.normalTokens.collectAsState()
    val stockTokens by viewModel.stockTokens.collectAsState()
    val lendTokens by viewModel.lendTokens.collectAsState()
    val predTokens by viewModel.predTokens.collectAsState()
    val tokens = normalTokens + stockTokens + lendTokens
    val wallet = viewModel.wallet
    val totalValue = tokens.sumOf { it.totalValue }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            Modifier.clickable {
                val clipboardManager =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clipData = ClipData.newPlainText("text", wallet.publicKey.toBase58())
                clipboardManager.setPrimaryClip(clipData)
            }, verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.wallet_label),
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.width(4.dp))
            Icon(painterResource(R.drawable.content_copy_24px), null, Modifier.size(16.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "$${totalValue.round(2)}",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(32.dp))

        LazyColumn {
            item {
                Text(
                    stringResource(R.string.token_positions),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
            items(normalTokens) { token ->
                TokenCard(token = token)
                Spacer(modifier = Modifier.height(8.dp))
            }
            item {
                Spacer(modifier = Modifier.height(32.dp))
                Text(
                    stringResource(R.string.stock_positions),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
            items(stockTokens) { token ->
                TokenCard(token = token) {
                    backStack.add(StockDetailPage(token.tokenInfo.mintAddress))
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            item {
                Spacer(modifier = Modifier.height(32.dp))
                Text(
                    stringResource(R.string.lending_positions),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
            items(lendTokens) { token ->
                TokenCard(token = token) {
                    backStack.add(LendDetailPage(token.tokenInfo.mintAddress))
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            item {
                Spacer(modifier = Modifier.height(32.dp))
                Text(
                    stringResource(R.string.prediction_positions),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
            items(predTokens) { token ->
                TokenCard(token = token) {
                    //backStack.add(P(token.tokenInfo.mintAddress))
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
fun TokenCard(token: Token, onClick: () -> Unit = {}) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable{onClick()},
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(getTokenColor(token.tokenInfo))
            )
            Column(modifier = Modifier.padding(start = 16.dp).weight(1f)) {
                Text(text = token.tokenInfo.name, fontWeight = FontWeight.Bold)
                when(token.tokenInfo.category) {
                    TokenInfo.Companion.Category.NORMAL, TokenInfo.Companion.Category.XSTOCK -> {
                        val tpr = TokenPriceRepository[token.tokenInfo] ?: return@Column
                        Text(
                            text = "$${tpr.price.round(2)} ${if (tpr.change >= 0) "+" else ""}${tpr.change.round(2)}%",
                            color = if (tpr.change >= 0) Color.Green else Color.Red
                        )
                    }
                    TokenInfo.Companion.Category.JUPITER_LEND -> {
                        val apy = JupiterLendRepository[token.tokenInfo]?.apy ?: 0.0
                        Text(text = stringResource(R.string.apy_format, (apy * 100).round(2)), color = Color.Green)
                    }

                    TokenInfo.Companion.Category.PRED_MARKET -> {
                        Text("")
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(text = "$${token.totalValue.round(2)}")
                Text(text = "${token.amount.displayAmount()} ${token.tokenInfo.symbol}")
            }
        }
    }
}