package com.vayunmathur.crypto.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.crypto.PortfolioViewModel
import com.vayunmathur.crypto.R
import com.vayunmathur.library.ui.IconNavigation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivateKeyScreen(viewModel: PortfolioViewModel, backStack: NavBackStack<*>) {
    val privateKey = viewModel.getPrivateKey()!!
    val context = LocalContext.current

    Scaffold(topBar = {
        TopAppBar(title = { Text("Private Key") }, navigationIcon = {
            IconNavigation(backStack)
        })
    }) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            Text("Please keep this safe and do not share with anyone. We never ask for your recovery phase. It resides locally on your device.")
            Spacer(modifier = Modifier.height(16.dp))
            Text(privateKey, modifier = Modifier.fillMaxWidth().background(Color.DarkGray).padding(16.dp))
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = {
                val clipboardManager =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clipData = ClipData.newPlainText("text", privateKey)
                clipboardManager.setPrimaryClip(clipData)
            }) {
                Text("Copy")
            }
            Spacer(modifier = Modifier.height(16.dp))
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF3B3B00))) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.warning_24px), contentDescription = "Warning", tint = Color.Yellow)
                    Text(
                        "Copying your recovery phrase or private key may expose it to other apps. If unsure, consider writing it down on paper and storing it safely.",
                        modifier = Modifier.padding(start = 8.dp), color = Color.Yellow
                    )
                }
            }
        }
    }
}