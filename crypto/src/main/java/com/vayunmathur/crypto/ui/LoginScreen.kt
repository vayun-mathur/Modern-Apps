package com.vayunmathur.crypto.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.crypto.PortfolioViewModel
import org.sol4k.Base58
import org.sol4k.Keypair

@Composable
fun LoginScreen(viewModel: PortfolioViewModel) {
    var privateKey by remember { mutableStateOf("") }
    val privateKeyValid by remember {derivedStateOf{
        try {
            Keypair.fromSecretKey(Base58.decode(privateKey))
            true
        } catch(_: Exception) {
            false
        }
    }}

    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize().padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            OutlinedTextField(
                value = privateKey,
                onValueChange = { privateKey = it },
                label = { Text("Enter your private key to restore") }
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { viewModel.initializeWallet(privateKey) },
                enabled = privateKeyValid
            ) {
                Text("Restore Existing Wallet")
            }
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { viewModel.createWallet() }
            ) {
                Text("Create New Wallet")
            }
        }
    }
}
